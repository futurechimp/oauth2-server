package org.scalatra
package oauth2

import auth.{ DefaultAuthenticationSupport, ForgotPasswordAuthSupport, PasswordAuthSupport }
import commands._
import model.AuthSession
import org.scalatra.scalate.ScalateSupport
import akka.actor.ActorSystem
import javax.servlet.http.{ HttpServletRequestWrapper, HttpServletResponse, HttpServletRequest }
import _root_.scalaz._
import Scalaz._
import org.json4s._
import OAuth2Imports._
import java.io.PrintWriter
import databinding.{ NativeJsonParsing, CommandSupport }
import service.AuthenticationService
import org.scalatra.json.NativeJsonSupport

trait OAuth2CommandSupport { self: ScalatraBase with NativeJsonSupport with NativeJsonParsing ⇒

  protected def oauth: OAuth2Extension
  /**
   * Create and bind a [[org.scalatra.command.Command]] of the given type with the current Scalatra params.
   *
   * For every command type, creation and binding is performed only once and then stored into
   * a request attribute.
   */
  def oauth2Command[T <: OAuth2Command[_]](args: Any*)(implicit mf: Manifest[T], system: ActorSystem): T = {
    getCommand(commands.get[T](oauth, args: _*))
  }

  /**
   * Create and bind a [[org.scalatra.command.Command]] of the given type with the current Scalatra params.
   *
   * For every command type, creation and binding is performed only once and then stored into
   * a request attribute.
   */
  def getCommand[T <: OAuth2Command[_]](factory: ⇒ T)(implicit mf: Manifest[T], system: ActorSystem): T = {
    commandOption[T] getOrElse {
      val newCommand = factory
      bindCommand(newCommand)
    }
  }

  protected def bindCommand[T <: OAuth2Command[_]](command: T)(implicit mf: Manifest[T]): T = {
    format match {
      case "json" | "xml" ⇒
        logger.debug("Binding from json")
        command.bindTo(parsedBody, multiParams, request.headers)
      case _ ⇒
        logger.debug("Binding from params")
        command.bindTo(multiParams, multiParams, request.headers)
    }
    request("_command_" + mf.erasure.getName) = command
    command
  }

}

trait AuthenticationApp[UserClass >: Null <: AppAuthSession[_ <: AppUser[_]]]
    extends PasswordAuthSupport[UserClass]
    with ForgotPasswordAuthSupport[UserClass] {
  self: ScalatraBase with NativeJsonSupport with FlashMapSupport with CookieSupport with ScalateSupport with DefaultAuthenticationSupport[UserClass] with OAuth2CommandSupport ⇒

  protected def forgotCommand: ForgotCommand = new ForgotCommand(oauth)

  protected def resetCommand: ResetCommand = new ResetCommand(oauth, request.remoteAddress)

  protected def registerCommand: RegisterCommand = new RegisterCommand(oauth, request.remoteAddress)

  protected def activateCommand: ActivateAccountCommand = new ActivateAccountCommand(oauth, request.remoteAddress)
}

/**
 * Mixin for clients that only support a limited set of HTTP verbs.  If the
 * request is a POST and the `_method` request parameter is set, the value of
 * the `_method` parameter is treated as the request's method.
 */
trait OAuth2MethodOverride extends Handler {
  abstract override def handle(req: HttpServletRequest, res: HttpServletResponse) {
    val req2 = req.requestMethod match {
      case Post | Get ⇒ requestWithMethod(req, methodOverride(req))
      case _          ⇒ req
    }
    super.handle(req2, res)
  }

  /**
   * Returns a request identical to the current request, but with the
   * specified method.
   *
   * For backward compatibility, we need to transform the underlying request
   * type to pass to the super handler.
   */
  protected def requestWithMethod(req: HttpServletRequest, method: Option[String]): HttpServletRequest =
    new HttpServletRequestWrapper(req) {
      override def getMethod(): String =
        method getOrElse req.getMethod
    }

  private def methodOverride(req: HttpServletRequest) = {
    import MethodOverride._
    (req.parameters.get(ParamName) orElse req.headers.get(HeaderName(0)))
  }
}

trait OAuth2ServerBaseApp extends ScalatraServlet
    with CookieSupport
    with XsrfTokenSupport
    with OAuth2ResponseSupport
    with OAuth2MethodOverride
    with FlashMapSupport
    with NativeJsonSupport
    with OAuth2CommandSupport
    with ScalateSupport
    with CorsSupport
    with LoadBalancedSslRequirement
    with DefaultAuthenticationSupport[AuthSession]
    with NativeJsonParsing
    with TypedParamSupport
    with OAuth2RendererSupport {

  implicit protected def system: ActorSystem
  implicit val jsonFormats: Formats = new OAuth2Formats
  override protected lazy val jsonVulnerabilityGuard: Boolean = true

  val oauth = OAuth2Extension(system)

  val userManifest = manifest[AuthSession]

  protected def authService: AuthenticationService = oauth.authService
  //
  //  before() {
  //    logger.info("Requesting path: " + requestPath)
  //    logger.info("Request format: " + format)
  //  }

  protected def loginCommand: LoginCommand = new LoginCommand(oauth, this.remoteAddress)

  /**
   * Builds a full URL from the given relative path. Takes into account the port configuration, https, ...
   *
   * @param path a relative path
   *
   * @return the full URL
   */
  protected def buildFullUrl(path: String) = {
    if (path.startsWith("http")) path
    else oauth.web.appUrl + url(path)
  }

  notFound {
    serveStaticResource() getOrElse resourceNotFound()
  }

  override def environment = oauth.environment

  override protected def createRenderContext(req: HttpServletRequest, resp: HttpServletResponse, out: PrintWriter) = {
    val ctx = super.createRenderContext(req, resp, out)
    ctx.attributes("title") = "Scalatra OAuth2"
    ctx.attributes.update("system", system)
    ctx
  }

  override protected def createTemplateEngine(config: ConfigT) = {
    val eng = super.createTemplateEngine(config)
    eng.importStatements :::=
      "import scalaz._" ::
      "import scalaz.Scalaz._" ::
      "import org.scalatra.oauth2._" ::
      "import org.scalatra.oauth2.OAuth2Imports._" ::
      "import org.scalatra.oauth2.model._" ::
      Nil
    eng
  }

  protected def inferFromJValue: ContentTypeInferrer = {
    case _: JValue if format == "xml" ⇒ formats("xml")
    case _: JValue                    ⇒ formats("json")
  }

  override protected def transformRequestBody(body: JValue) = body.camelizeKeys

  override protected def contentTypeInferrer = inferFromFormats orElse inferFromJValue orElse super.contentTypeInferrer

  override protected def renderPipeline = renderValidation orElse renderOAuth2Response orElse super.renderPipeline

  override protected def isScalateErrorPageEnabled = isDevelopmentMode

  /**
   * Redirect to full URL build from the given relative path.
   *
   * @param path a relative path
   */
  override def redirect(path: String) = {
    val url = buildFullUrl(path)
    super.redirect(url)
  }

  override def url(path: String, params: Iterable[(String, Any)] = Iterable.empty): String = {
    val newPath = path match {
      case x if x.startsWith("/") ⇒ ensureSlash(contextPath) + ensureSlash(path)
      case _                      ⇒ ensureSlash(routeBasePath) + ensureSlash(path)
    }
    val pairs = params map { case (key, value) ⇒ key.urlEncode + "=" + value.toString.urlEncode }
    val queryString = if (pairs.isEmpty) "" else pairs.mkString("?", "&", "")
    addSessionId((newPath.startsWith("/") ? newPath.substring(1) | newPath) + queryString)
  }

  private def ensureSlash(candidate: String) = {
    (candidate.startsWith("/"), candidate.endsWith("/")) match {
      case (true, true)   ⇒ candidate.dropRight(1)
      case (true, false)  ⇒ candidate
      case (false, true)  ⇒ "/" + candidate.dropRight(1)
      case (false, false) ⇒ "/" + candidate
    }
  }

}
