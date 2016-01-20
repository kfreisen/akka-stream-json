/*
 * Copyright 2015 – 2016 Paul Horn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.knutwalker.akka.http

import de.knutwalker.akka.http.support.CirceHttpSupport
import de.knutwalker.akka.stream.support.CirceStreamSupport

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.HttpCharsets.`UTF-8`
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ HttpEntity, RequestEntity }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.util.ByteString

import io.circe.{ Printer, Encoder, Decoder }
import io.circe.generic.semiauto._
import jawn.ParseException
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.FutureMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll

import scala.concurrent.Await
import scala.concurrent.duration._

case class Foo(bar: String, baz: Int, qux: List[Boolean])
object Foo {
  implicit val decoderFoo: Decoder[Foo] = deriveFor[Foo].decoder
  implicit val encoderFoo: Encoder[Foo] = deriveFor[Foo].encoder
}


object JsonSupportSpec extends Specification with CirceHttpSupport with CirceStreamSupport with FutureMatchers with AfterAll {



  val foo            = Foo("bar", 42, List(true, false))
  val goodJson       = """{"bar":"bar","baz":42,"qux":[true,false]}"""
  val incompleteJson = """{"bar":"bar","baz":42,"qux"""
  val badJson        = """{"bar":"bar"}"""
  val invalidJson    = """{"bar"="bar"}"""
  val prettyJson     = """
                         |{
                         |  "bar" : "bar",
                         |  "baz" : 42,
                         |  "qux" : [
                         |    true,
                         |    false
                         |  ]
                         |}""".stripMargin.trim

  implicit val system = ActorSystem()
  implicit val mat    = ActorMaterializer()

  title("Specification for Jawn Json Support with Circe")

  "JawnArgonautSupportSpec" should {
    import system.dispatcher
    implicit val eenv = ExecutionEnv.fromExecutionContext(system.dispatcher)
    "enable marshalling of an A for which an Encoder[A] exists" in {
      "The marshalled entity" should {
        val entity = Await.result(Marshal(foo).to[RequestEntity], 100.millis)

        "have `application/json` as content type" >> {
          entity.contentType.mediaType must be(`application/json`)
        }

        "have UTF-8 as charset" >> {
          entity.contentType.charsetOption must beSome(`UTF-8`)
        }

        "have the correct body" >> {
          entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String) must be_===(goodJson).await
        }
      }

      "The marshalled entity with overridden Printer" should {
        implicit val makeItPretty: Printer = Printer.spaces2
        val entity = Await.result(Marshal(foo).to[RequestEntity], 100.millis)

        "have `application/json` as content type" >> {
          entity.contentType.mediaType must be(`application/json`)
        }

        "have UTF-8 as charset" >> {
          entity.contentType.charsetOption must beSome(`UTF-8`)
        }

        "have the correct body" >> {
          entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String) must be_===(prettyJson).await
        }
      }
    }

    "enable unmarshalling of an A for which a Decodee[A] exists" in {
      "A valid, strict json entity" should {
        val goodEntity = mkEntity(goodJson, strict = true)
        "produce the proper type" >> {
          Unmarshal(goodEntity).to[Foo] must be_===(foo).await
        }
      }

      "A valid, lazily streamed json entity" should {
        val lazyEntity = mkEntity(goodJson)
        "produce the proper type" >> {
          Unmarshal(lazyEntity).to[Foo] must be_===(foo).await
        }
      }

      "A complete, lazily streamed json entity with superfluous content" should {
        val entity = mkEntity(goodJson + incompleteJson)
        "produce the proper type" >> {
          Unmarshal(entity).to[Foo] must be_===(foo).await
        }
      }

      "Multiple, lazily streamed json entities via a flow" should {
        val entity = mkEntity(goodJson + goodJson + goodJson)
        "produce all values" >> {
          val collect = Sink.fold[Vector[Foo], Foo](Vector())(_ :+ _)
          val parsed = entity.dataBytes.runWith(decode[Foo].toMat(collect)(Keep.right))
          parsed must be_===(Vector(foo, foo, foo)).await
        }
      }

      "A incomplete, lazily streamed json entity" should {
        val incompleteEntity = mkEntity(incompleteJson)
        "produce a parse exception with the message 'exhausted input'" >> {
          Unmarshal(incompleteEntity).to[Foo] must throwA[ParseException]("exhausted input").await
        }
      }

      "A bad json entity" should {
        val badEntity = mkEntity(badJson, strict = true)
        "produce a parse exception with the message 'field missing'" >> {
          Unmarshal(badEntity).to[Foo] must throwA[IllegalArgumentException]("The field \\[baz\\] is missing\\.").await
        }
      }

      "A invalid json entity" should {
        val invalidEntity = mkEntity(invalidJson, strict = true)
        "produce a parse exception with the message 'expected/got'" >> {
          Unmarshal(invalidEntity).to[Foo] must throwA[ParseException]("expected : got =").await
        }
      }
    }
  }

  def mkEntity(s: String, strict: Boolean = false) = {
    if (strict) {
      HttpEntity(`application/json`, s)
    } else {
      val source = Source.fromIterator(() ⇒ s.grouped(8).map(ByteString(_)))
      HttpEntity(`application/json`, s.length.toLong, source)
    }
  }

  def afterAll(): Unit = {
    system.shutdown()
  }
}