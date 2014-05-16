package com.blinkbox.books.auth

import com.blinkbox.security.jwt._
import org.scalatest.{FunSuite, Matchers}
import org.scalatest.concurrent.{AsyncAssertions, ScalaFutures}
import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global

class TestTokenDecoder extends TokenDecoder(SignaturePolicy.NONE) {
  override def getVerifier(claims: java.util.Map[String, AnyRef]) = signatures.None.getInstance()
}

class ZuulTokenDeserializerTest extends FunSuite with ScalaFutures with AsyncAssertions with Matchers {
  val deserializer = new ZuulTokenDeserializer(new TestTokenDecoder)

  test("A token with a valid subject claim is deserialized") {
    val token = testToken("sub" -> "urn:blinkbox:zuul:user:123")
    whenReady(deserializer(token)) { user =>
      user should be(User(123, None))
    }
  }

  test("A token with valid subject and client claims is deserialized") {
    val token = testToken("sub" -> "urn:blinkbox:zuul:user:284", "bb/cid" -> "urn:blinkbox:zuul:client:2934")
    whenReady(deserializer(token)) { user =>
      user should be(User(284, Some(2934)))
    }
  }

  test("A token with no subject claim is invalid") {
    val w = new Waiter
    val token = testToken("bb/cid" -> "urn:blinkbox:zuul:client:2934")
    deserializer(token) recover { case e => w(throw e) } onComplete { _ => w.dismiss() }
    intercept[InvalidClaimException] { w.await() }
  }

  test("A token with a malformed subject claim is invalid") {
    val w = new Waiter
    val token = testToken("sub" -> "urn:invalid:284")
    deserializer(token) recover { case e => w(throw e) } onComplete { _ => w.dismiss() }
    intercept[InvalidClaimException] { w.await() }
  }

  test("A token with a malformed client claim is invalid") {
    val w = new Waiter
    val token = testToken("sub" -> "urn:blinkbox:zuul:user:284", "bb/cid" -> "urn:invalid:2934")
    deserializer(token) recover { case e => w(throw e) } onComplete { _ => w.dismiss() }
    intercept[InvalidClaimException] { w.await() }
  }

  test("A token with a non-JSON payload is invalid") {
    val w = new Waiter
    val token = testToken("just a plain old string")
    deserializer(token) recover { case e => w(throw e) } onComplete { _ => w.dismiss() }
    intercept[InvalidTokenException] { w.await() }
  }

  private def testToken(claims: (String, AnyRef)*): String =
    new TokenEncoder().encode(claims.toMap, signatures.None.getInstance(), null)

  private def testToken(payload: String): String =
    new TokenEncoder().encode(payload, signatures.None.getInstance(), null)
}
