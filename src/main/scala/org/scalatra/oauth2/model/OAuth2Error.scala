package org.scalatra
package oauth2
package model

import java.net.URI
import OAuth2Imports._
import collection.mutable
import validation.{ FieldName, ErrorCode, ValidationError }

case object LoginFailed extends ErrorCode
case object AlreadyConfirmed extends ErrorCode
case object NotUnique extends ErrorCode
case object InvalidToken extends ErrorCode

object OAuth2Error {

  def ServerError = ValidationError("Internal server error.", org.scalatra.validation.UnknownError)
  def AlreadyConfirmed = ValidationError("This account has already been confirmed", model.AlreadyConfirmed)
  def InvalidToken = ValidationError("The token is invalid.", model.InvalidToken)
  def LoginFailed = ValidationError("Login failed.", model.LoginFailed)
  def NotFound = ValidationError("Not found.", org.scalatra.validation.NotFound)
  def NotUnique(message: String, field: String) = ValidationError(message.format(field), FieldName(field), model.NotUnique)
  def NotImplementedError = ValidationError("Not implemented.", org.scalatra.validation.NotImplemented)
  def BadGatewayError = ValidationError("Bad gateway.", org.scalatra.validation.BadGateway)
  def ServiceUnavailable = ValidationError("Service unavailable.", org.scalatra.validation.ServiceUnavailable)
  def GatewayTimeout = ValidationError("Gateway timeout.", org.scalatra.validation.GatewayTimeout)

  object Code extends Enumeration {
    type Error = Value
    /**
     * The request is missing a required parameter, includes an
     * unsupported parameter or parameter value, or is otherwise
     * malformed.
     */
    val InvalidRequest = Value("invalid_request")
    /**
     * Client authentication failed (e.g. unknown client, no
     * client credentials included, multiple client credentials
     * included, or unsupported credentials type).  The
     * authorization server MAY return an HTTP 401
     * (Unauthorized) status code to indicate which HTTP
     * authentication schemes are supported.  If the client
     * attempted to authenticate via the "Authorization" request
     * header field, the authorization server MUST respond with
     * an HTTP 401 (Unauthorized) status code, and include the
     * "WWW-Authenticate" response header field matching the
     * authentication scheme used by the client.
     */
    val InvalidClient = Value("invalid_client")
    /**
     * The provided authorization grant is invalid, expired,
     * revoked, or does not match the redirection URI used in
     * the authorization request.
     */
    val InvalidGrant = Value("invalid_grant")
    /**
     * The client is not authorized to request an authorization
     * code using this method.
     */
    val UnauthorizedClient = Value("unauthorized_client")
    /**
     * The resource owner or authorization server denied the request.
     */
    val AccessDenied = Value("access_denied")
    /**
     * The authorization server does not support obtaining an
     * authorization code using this method.
     */
    val UnsupportedResponseType = Value("unsupported_response_type")
    /**
     * The authorization grant type is not supported by the
     * authorization server.
     */
    val UnsupportedGrantType = Value("unsupported_grant_type")
    /** The requested scope is invalid, unknown, or malformed. */
    val InvalidScope = Value("invalid_scope")
    /** The token used to access this resource is invalid */
    val ExpiredToken = Value("expired_token")
  }
}

/**
 * Base class for all OAuth errors. These map to error codes in the spec.
 */
abstract class OAuth2Error(
    /** REQUIRED.  A single error code */
    val error: OAuth2Error.Code.Value,
    /**
     * REQUIRED if the "state" parameter was present in the client
     * authorization request.  Set to the exact value received from
     * the client.
     */
    val state: Option[String],
    /**
     * OPTIONAL.  A human-readable text providing additional
     * information, used to assist in the understanding and resolution
     * of the error occurred.
     */
    val description: Option[String] = None,
    /**
     * OPTIONAL.  A URI identifying a human-readable web page with
     * information about the error, used to provide the resource owner
     * with additional information about the error.
     */
    val uri: Option[String] = None,
    /**
     * OPTIONAL.  A URI to which the request should redirect when this error occurs
     */
    val redirectUri: Option[URI] = None) extends Error {

  override def toString = {
    val b = new StringBuilder
    b append "error=" append error.toString.urlEncode
    state foreach { b append "&state=" append _.urlEncode }
    description foreach { b append "&error_description=" append _.urlEncode }
    uri foreach { b append "&error_uri=" append _.urlEncode }
    b.toString()
  }

  def toRedirecUrl(responseType: Option[ResponseType.Value] = None) = {
    val uri = redirectUri.get
    if (responseType.isDefined && responseType.forall(_ == ResponseType.Token)) {
      new URI(uri.getScheme, uri.getAuthority, uri.getPath, uri.getFragment, buildQueryString(Map.empty)).toASCIIString
    } else {
      new URI(uri.getScheme, uri.getAuthority, uri.getPath, buildQueryString(uri.query), uri.getFragment).toASCIIString
    }
  }

  private def buildQueryString(query: Map[String, Seq[String]]) = {
    val m = new mutable.HashMap[String, Seq[String]]
    m += "error" -> List(error.toString)
    state foreach { m += "state" -> List(_) }
    description foreach { m += "error_description" -> List(_) }
    uri foreach { m += "error_uri" -> List(_) }
    (query ++ m) flatMap { case (k, v) ⇒ v.map(vv ⇒ k.urlEncode + "=" + vv.urlEncode) } mkString "&"
  }

  val message = description getOrElse "Unknown OAuth 2 problem"
}

/**
 * The end-user or authorization server denied the request.
 */
case class AccessDeniedError(
  override val state: Option[String],
  override val uri: Option[String] = None,
  override val redirectUri: Option[URI] = None)
    extends OAuth2Error(OAuth2Error.Code.AccessDenied, state, Some("You are not allowed to access this resource."), uri, redirectUri)

/**
 * Access token expired, client expected to request new one using refresh token.
 */
case class ExpiredTokenError(
  override val state: Option[String],
  override val uri: Option[String] = None,
  override val redirectUri: Option[URI] = None)
    extends OAuth2Error(OAuth2Error.Code.ExpiredToken, state, Some("The access token has expired."), uri, redirectUri)

/**
 *  The client identifier provided is invalid, the client failed to
 *  authenticate, the client did not include its credentials, provided
 *  multiple client credentials, or used unsupported credentials type.
 */
case class InvalidClientError(
  override val state: Option[String],
  override val uri: Option[String] = None,
  override val redirectUri: Option[URI] = None)
    extends OAuth2Error(OAuth2Error.Code.InvalidClient, state, Some("Client ID and client secret do not match."), uri, redirectUri)

/**
 * The provided access grant is invalid, expired, or revoked (e.g.  invalid
 * assertion, expired authorization token, bad end-user password credentials,
 * or mismatching authorization code and redirection URI).
 */
case class InvalidGrantError(
  override val state: Option[String],
  override val description: Option[String] = None,
  override val uri: Option[String] = None,
  override val redirectUri: Option[URI] = None)
    extends OAuth2Error(OAuth2Error.Code.InvalidGrant, state, Some(description getOrElse "This access grant is no longer valid."), uri, redirectUri)

/**
 * Invalid request, the request is missing a required parameter, includes an
 * unsupported parameter or parameter value, repeats the same parameter, uses
 * more than one method for including an access token, or is otherwise
 * malformed.
 */
case class InvalidRequestError(
  override val state: Option[String],
  override val description: Option[String] = None,
  override val uri: Option[String] = None,
  override val redirectUri: Option[URI] = None)
    extends OAuth2Error(OAuth2Error.Code.InvalidRequest, state, Some(description getOrElse "The request has the wrong parameters."), uri, redirectUri)

/** The requested scope is invalid, unknown, or malformed. */
case class InvalidScopeError(
  override val state: Option[String],
  override val uri: Option[String] = None,
  override val redirectUri: Option[URI] = None)
    extends OAuth2Error(OAuth2Error.Code.InvalidScope, state, Some("The requested scope is not supported."), uri, redirectUri)

/** The authenticated client is not authorized to use the access grant type provided. */
case class UnauthorizedClientError(
  override val state: Option[String],
  override val uri: Option[String] = None,
  override val redirectUri: Option[URI] = None)
    extends OAuth2Error(OAuth2Error.Code.UnauthorizedClient, state, Some("You are not allowed to access this resource."), uri, redirectUri)

/** This access grant type is not supported by this server. */
case class UnsupportedGrantTypeError(
  override val state: Option[String],
  override val uri: Option[String] = None,
  override val redirectUri: Option[URI] = None)
    extends OAuth2Error(OAuth2Error.Code.UnsupportedGrantType, state, Some("This access grant type is not supported by this server."), uri, redirectUri)

/** The requested response type is not supported by the authorization server. */
case class UnsupportedResponseTypeError(
  override val state: Option[String],
  override val uri: Option[String] = None,
  override val redirectUri: Option[URI] = None)
    extends OAuth2Error(OAuth2Error.Code.UnsupportedResponseType, state, Some("The requested response type is not supported."), uri, redirectUri)
