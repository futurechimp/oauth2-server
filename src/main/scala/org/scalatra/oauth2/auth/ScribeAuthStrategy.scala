package org.scalatra
package oauth2
package auth

import scala.util.control.Exception._
import scalaz._
import Scalaz._
import org.scribe.model.Verifier
import org.scribe.oauth.{ OAuth20ServiceImpl, OAuth10aServiceImpl, OAuthService }
import OAuth2Imports._
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import org.scalatra.auth.{ ScentrySupport, ScentryStrategy }
import org.scalatra.validation.ValidationError
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import model.{ Account, AuthSession }
import org.scalatra.auth.ScentryAuthStore.CookieAuthStore
import org.scalatra.json.NativeJsonSupport

object OAuthToken {
  def apply(scribeToken: org.scribe.model.Token): OAuthToken = OAuthToken(scribeToken.getToken, scribeToken.getSecret)
}
case class OAuthToken(token: String, secret: String)

trait ScribeAuthStrategyContext[UserClass >: Null <: AppAuthSession[_ <: AppUser[_]]] {

  def oauthService: OAuthService

  def name: String

  def app: ScalatraBase with FlashMapSupport with ScribeAuthSupport[UserClass]

  def findOrCreateUser(accessToken: OAuthToken): Validation[ValidationError, UserClass]
}

trait ScribeAuthSupport[UserClass >: Null <: AppAuthSession[_ <: AppUser[_]]] extends AuthenticationSupport[UserClass] { self: ScalatraBase with SessionSupport with FlashMapSupport with NativeJsonSupport ⇒

  private[this] val oauthServicesRegistry = new ConcurrentHashMap[String, ScribeAuthStrategyContext[UserClass]].asScala

  private[this] val thisApp = this

  protected def sslRequired: Boolean = true

  def registerOAuthService(name: String, service: OAuthService)(findOrCreateUser: OAuthToken ⇒ Validation[ValidationError, UserClass]) = {
    val nm = name
    val fn = findOrCreateUser
    val ctxt = new ScribeAuthStrategyContext[UserClass] {
      lazy val oauthService = service
      val name = nm
      val app = thisApp
      def findOrCreateUser(accessToken: OAuthToken) = {
        session("oauth.accessToken") = accessToken
        try {
          fn(accessToken)
        } catch {
          case e ⇒
            e.printStackTrace()
            ValidationError("Couldn't fetch the access token").fail[UserClass]
        }
      }
    }
    oauthServicesRegistry += name -> ctxt
  }

  get("/:provider") {
    if (!oauthServicesRegistry.contains(params("provider"))) halt(404, "The provider [" + params("provider") + "] is not available.")

    oauthServicesRegistry get params("provider") flatMap {
      _.oauthService match {
        case svc: OAuth10aServiceImpl ⇒
          val tok = svc.getRequestToken
          if (tok == null) halt(502, "Couldn't obtain a request token for " + params("provider"))
          ScribeAuthStrategy.requestTokens(tok.getToken) = tok
          svc.getAuthorizationUrl(tok).blankOption

        case svc ⇒ svc.getAuthorizationUrl(null).blankOption
      }
    } foreach redirect

    flash("error") = "Couldn't get a authorization url for oauth provider: %s" format params("provider")
    unauthenticated()
  }

  get("/:provider/callback") {
    scentry.authenticate(params("provider"))
    userOption.fold(
      sess ⇒ {
        val u = sess.account
        authService.validate(u.asInstanceOf[Account]).fold(
          errs ⇒ {
            clearUser()
            contentType = "text/html"
            jade("incomplete_oauth", "errors" -> errs.list, "login" -> u.login, "email" -> u.email, "name" -> u.name)
          },
          uu ⇒ loggedIn(sess, uu.login + " logged in from " + params("provider") + "."))

      },
      unauthenticated())
  }

  protected def clearUser() = {
    scentry.user = null
    scentry.store.invalidate
  }

  post("/:provider/callback") {
    if (isAnonymous) unauthenticated()
    else {
      trySavingCompletedProfile().fold(
        errs ⇒ {
          contentType = "text/html"
          jade("incomplete_oauth", "errors" -> errs.list)
        },
        u ⇒ loggedIn(u, u.account.login + " logged in from " + params("provider") + "."))
    }
  }

  protected def trySavingCompletedProfile(): ValidationNEL[ValidationError, UserClass]

  /**
   * Registers authentication strategies.
   */
  override protected def registerAuthStrategies {
    oauthServicesRegistry foreach {
      case (k, v) ⇒ scentry.register(k, _ ⇒ new ScribeAuthStrategy(v))
    }
  }

  protected def authCookieOptions: CookieOptions

  override protected def configureScentry {
    scentry.store = new CookieAuthStore(this)(authCookieOptions)
  }

  def unauthenticated() {
    session(scentryConfig.returnToKey) = request.uri.toASCIIString
    redirect(scentryConfig.failureUrl)
  }

}

object ScribeAuthStrategy {
  private[auth] val requestTokens = new ConcurrentHashMap[String, org.scribe.model.Token].asScala

}
class ScribeAuthStrategy[UserClass >: Null <: AppAuthSession[_ <: AppUser[_]]](context: ScribeAuthStrategyContext[UserClass]) extends ScentryStrategy[UserClass] {

  import ScribeAuthStrategy._
  override val name = context.name
  protected val app = context.app

  override def isValid =
    app.request.requestMethod == Get &&
      app.params.contains("provider") &&
      app.params("provider") == name &&
      matchesForOAuthVersion

  private[this] def matchesForOAuthVersion = context.oauthService match {
    case _: OAuth20ServiceImpl  ⇒ hasKey("code")
    case _: OAuth10aServiceImpl ⇒ hasKey("oauth_token") && hasKey("oauth_verifier")
    case _                      ⇒ false
  }

  private[this] def hasKey(key: String) = app.params.get(key).flatMap(_.blankOption).isDefined
  private[this] def verifier: String = context.oauthService match {
    case _: OAuth20ServiceImpl  ⇒ app.params("code")
    case _: OAuth10aServiceImpl ⇒ app.params("oauth_verifier")
  }

  override def unauthenticated() {
    //    app.unauthenticated()
  }

  def authenticate(): Option[UserClass] =
    (allCatch withApply logError) {
      val reqToken = app.params.get("oauth_token").flatMap(requestTokens.get)
      reqToken foreach (requestTokens -= _.getToken)
      val verif = verifier
      val accessToken = OAuthToken(context.oauthService.getAccessToken(reqToken.orNull, new Verifier(verif)))
      context.findOrCreateUser(accessToken).toOption
    }

  private[this] def logError(ex: Throwable): Option[UserClass] = {
    logger.error("There was a problem authenticating with " + name, ex)
    none[UserClass]
  }

}

