package io.backchat.oauth2

import auth.{ OAuthToken, ScribeAuthSupport }
import akka.actor.ActorSystem
import model.{ BCryptPassword, ResourceOwner }
import org.scalatra.{ SessionSupport, CookieSupport, FlashMapSupport, ScalatraServlet }
import scentry.ScentrySupport
import org.scribe.builder.ServiceBuilder
import dispatch._
import dispatch.oauth._
import dispatch.liftjson.Js._
import net.liftweb.json._
import scalaz._
import Scalaz._
import java.security.SecureRandom
import org.apache.commons.codec.binary.Hex
import OAuth2Imports._
import org.scribe.builder.api.{ TwitterApi, FacebookApi }

class FacebookApiCalls(accessToken: OAuthToken)(implicit formats: Formats) {
  private val urlBase = "https://graph.facebook.com/"
  private val atParams = Map("access_token" -> accessToken.token)

  def getProfile(id: Option[Int] = None): JValue = {
    Http(url(urlBase + "/" + (id some (_.toString) none "me")) <<? atParams ># identity)
  }
}

class TwitterApiCalls(accessToken: OAuthToken, provider: OAuthProvider)(implicit formats: Formats) {
  import OAuth._
  private val consumer = dispatch.oauth.Consumer(provider.clientId, provider.clientSecret)
  private val token = dispatch.oauth.Token(accessToken.token, accessToken.secret)

  private val urlBase = "https://api.twitter.com/1/"

  def getProfile(id: Option[Int] = None): JValue = {
    Http(url(urlBase + "/account/verify_credentials.json") <@ (consumer, token) ># identity)
  }
}

class OAuthAuthentication(implicit system: ActorSystem)
    extends ScalatraServlet with FlashMapSupport with CookieSupport with ScribeAuthSupport[ResourceOwner] {

  val oauth = OAuth2Extension(system)
  protected val authProvider = oauth.userProvider
  implicit val jsonFormats: Formats = DefaultFormats

  override protected def registerAuthStrategies {
    val facebookProvider = oauth.providers("facebook") // requires scope email at least for facebook
    val facebookService = facebookProvider.service[FacebookApi](callbackUrlFormat)

    registerOAuthService(facebookProvider.name, facebookService) { token ⇒
      val fbUser = new FacebookApiCalls(token).getProfile()
      val fbEmail = (fbUser \ "email").extract[String]
      val foundUser = authProvider.findByLoginOrEmail(fbEmail)
      (foundUser getOrElse {
        val usr = ResourceOwner(
          login = (fbUser \ "username").extract[String],
          email = fbEmail,
          name = (fbUser \ "name").extract[String],
          password = BCryptPassword(randomPassword).encrypted,
          confirmedAt = DateTime.now)
        authProvider.loggedIn(usr, request.remoteAddress)
        usr
      }).success[model.Error]
    }

    val twitterProvider = oauth.providers("twitter")
    val twitterService = twitterProvider.service[TwitterApi](callbackUrlFormat)

    registerOAuthService(twitterProvider.name, twitterService) { token ⇒
      val twitterUser = new TwitterApiCalls(token, twitterProvider).getProfile()

      null
    }
  }

  private[this] def randomPassword = {
    val rand = new SecureRandom
    val pwdBytes = Array.ofDim[Byte](8)
    rand.nextBytes(pwdBytes)
    Hex.encodeHexString(pwdBytes)
  }

  private[this] def urlWithContextPath(path: String, params: Iterable[(String, Any)] = Iterable.empty): String = {
    val newPath = path match {
      case x if x.startsWith("/") ⇒ contextPath + path
      case _                      ⇒ path
    }
    val pairs = params map { case (key, value) ⇒ key.urlEncode + "=" + value.toString.urlEncode }
    val queryString = if (pairs.isEmpty) "" else pairs.mkString("?", "&", "")
    newPath + queryString
  }

  private[this] def callbackUrlFormat = {
    "http%s://%s%s".format(
      if (oauth.web.sslRequired) "s" else "",
      oauth.web.domainWithPort,
      urlWithContextPath("%s/callback"))
  }

  /**
   * Builds a full URL from the given relative path. Takes into account the port configuration, https, ...
   *
   * @param path a relative path
   *
   * @return the full URL
   */
  protected def buildFullUrl(path: String) = {
    if (path.startsWith("http")) path else {
      "http%s://%s%s".format(
        if (oauth.web.sslRequired || this.isHttps) "s" else "",
        oauth.web.domainWithPort,
        this.url(path))
    }
  }

}