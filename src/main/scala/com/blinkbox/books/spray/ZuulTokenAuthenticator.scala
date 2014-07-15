package com.blinkbox.books.spray

import com.blinkbox.books.auth.Elevation._
import com.blinkbox.books.auth.{TokenDeserializer, TokenElevationChecker, User}
import com.blinkbox.security.jwt.TokenException
import spray.http.HttpHeaders.{Authorization, `WWW-Authenticate`}
import spray.http._
import spray.routing.AuthenticationFailedRejection.{CredentialsMissing, CredentialsRejected}
import spray.routing.authentication._
import spray.routing.{AuthenticationFailedRejection, Rejection, RequestContext}
import spray.util._

import scala.concurrent.{ExecutionContext, Future}

class ZuulTokenAuthenticator(deserializer: TokenDeserializer, elevationChecker: TokenElevationChecker, val elevation: Elevation = Critical)(implicit val executionContext: ExecutionContext)
  extends ContextAuthenticator[User] {
  import com.blinkbox.books.spray.ZuulTokenAuthenticator._

  def withElevation(e: Elevation) = new ZuulTokenAuthenticator(deserializer, elevationChecker, e)

  override def apply(ctx: RequestContext): Future[Either[Rejection, User]] =
    ctx.request.headers.findByType[`Authorization`] match {
      case Some(Authorization(OAuth2BearerToken(token))) => authenticate(token)
      case _ => Future.successful(Left(AuthenticationFailedRejection(CredentialsMissing, credentialsMissingHeaders)))
    }

  private def authenticate(token: String): Future[Either[Rejection, User]] =
    deserializer(token) flatMap { user =>
      if (elevation == Unelevated) {
        Future.successful(Right(user))
      } else {
        elevationChecker(token) map (_ >= elevation) map {
          case true => Right(user)
          case false => Left(AuthenticationFailedRejection(CredentialsRejected, insufficientElevationHeaders))
        }
      }
    } recover {
      case _: TokenException => Left(AuthenticationFailedRejection(CredentialsRejected, credentialsInvalidHeaders))
    }
}

object ZuulTokenAuthenticator {
  private[spray] val credentialsMissingHeaders = `WWW-Authenticate`(new ZuulHttpChallenge) :: Nil
  private[spray] val credentialsInvalidHeaders = `WWW-Authenticate`(new ZuulHttpChallenge(params = Map("error" -> "invalid_token", "error_description" -> "The access token is invalid"))) :: Nil
  private[spray] val insufficientElevationHeaders = `WWW-Authenticate`(new ZuulHttpChallenge(params = Map("error" -> "invalid_token", "error_reason" -> "unverified_identity", "error_description" -> "You need to re-verify your identity"))) :: Nil
}

private class ZuulHttpChallenge(params: Map[String, String] = Map.empty) extends HttpChallenge(scheme = "Bearer", realm = "", params) {
  override def render[R <: Rendering](r: R): r.type = {
    r ~~ scheme
    if (params.nonEmpty) params.foreach { case (k, v) => r ~~ " " ~~ k ~~ '=' ~~#! v }
    r
  }
}