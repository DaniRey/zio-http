package zhttp.service

import zhttp.html._
import zhttp.http._
import zhttp.internal.{DynamicServer, HttpGen, HttpRunnableSpec}
import zhttp.service.server._
import zio.stream.{ZPipeline, ZStream}
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._
import zio.{Chunk, ZIO, durationInt}

import java.nio.file.Paths

object ServerSpec extends HttpRunnableSpec {

  private val nonEmptyContent = for {
    data    <- Gen.listOf(Gen.alphaNumericString)
    content <- HttpGen.nonEmptyHttpData(Gen.const(data))
  } yield (data.mkString(""), content)

  private val env =
    EventLoopGroup.nio() ++ ChannelFactory.nio ++ ServerChannelFactory.nio ++ DynamicServer.live

  private val app                 =
    serve(DynamicServer.app, Some(Server.requestDecompression(true) ++ Server.enableObjectAggregator(4096)))
  private val appWithReqStreaming = serve(DynamicServer.app, None)

  def dynamicAppSpec = suite("DynamicAppSpec") {
    suite("success") {
      test("status is 200") {
        val status = Http.ok.deploy.status.run()
        assertM(status)(equalTo(Status.Ok))
      } +
        test("status is 200") {
          val res = Http.text("ABC").deploy.status.run()
          assertM(res)(equalTo(Status.Ok))
        } +
        test("content is set") {
          val res = Http.text("ABC").deploy.bodyAsString.run()
          assertM(res)(containsString("ABC"))
        }
    } +
      suite("not found") {
        val app = Http.empty
        test("status is 404") {
          val res = app.deploy.status.run()
          assertM(res)(equalTo(Status.NotFound))
        } +
          test("header is set") {
            val res = app.deploy.headerValue(HeaderNames.contentLength).run()
            assertM(res)(isSome(equalTo("0")))
          }
      } +
      suite("error") {
        val app = Http.fail(new Error("SERVER_ERROR"))
        test("status is 500") {
          val res = app.deploy.status.run()
          assertM(res)(equalTo(Status.InternalServerError))
        } +
          test("content is set") {
            val res = app.deploy.bodyAsString.run()
            assertM(res)(containsString("SERVER_ERROR"))
          } +
          test("header is set") {
            val res = app.deploy.headerValue(HeaderNames.contentLength).run()
            assertM(res)(isSome(anything))
          }
      } +
      suite("die") {
        val app = Http.die(new Error("SERVER_ERROR"))
        test("status is 500") {
          val res = app.deploy.status.run()
          assertM(res)(equalTo(Status.InternalServerError))
        } +
          test("content is set") {
            val res = app.deploy.bodyAsString.run()
            assertM(res)(containsString("SERVER_ERROR"))
          } +
          test("header is set") {
            val res = app.deploy.headerValue(HeaderNames.contentLength).run()
            assertM(res)(isSome(anything))
          }
      } +
      suite("echo content") {
        val app = Http.collectZIO[Request] { case req =>
          req.bodyAsString.map(text => Response.text(text))
        }

        test("status is 200") {
          val res = app.deploy.status.run()
          assertM(res)(equalTo(Status.Ok))
        } +
          test("body is ok") {
            val res = app.deploy.bodyAsString.run(content = HttpData.fromString("ABC"))
            assertM(res)(equalTo("ABC"))
          } +
          test("empty string") {
            val res = app.deploy.bodyAsString.run(content = HttpData.fromString(""))
            assertM(res)(equalTo(""))
          } +
          test("one char") {
            val res = app.deploy.bodyAsString.run(content = HttpData.fromString("1"))
            assertM(res)(equalTo("1"))
          }
      } +
      suite("headers") {
        val app = Http.ok.addHeader("Foo", "Bar")
        test("headers are set") {
          val res = app.deploy.headerValue("Foo").run()
          assertM(res)(isSome(equalTo("Bar")))
        }
      } + suite("response") {
        val app = Http.response(Response(status = Status.Ok, data = HttpData.fromString("abc")))
        test("body is set") {
          val res = app.deploy.bodyAsString.run()
          assertM(res)(equalTo("abc"))
        }
      } +
      suite("decompression") {
        val app     = Http.collectZIO[Request] { case req => req.bodyAsString.map(body => Response.text(body)) }.deploy
        val content = "some-text"
        val stream  = ZStream.fromChunk(Chunk.fromArray(content.getBytes))

        test("gzip") {
          val res = for {
            body     <- stream.via(ZPipeline.gzip()).runCollect
            response <- app.run(
              content = HttpData.fromChunk(body),
              headers = Headers.contentEncoding(HeaderValues.gzip),
            )
          } yield response
          assertM(res.flatMap(_.bodyAsString))(equalTo(content))
        } +
          test("deflate") {
            val res = for {
              body     <- stream.via(ZPipeline.deflate()).runCollect
              response <- app.run(
                content = HttpData.fromChunk(body),
                headers = Headers.contentEncoding(HeaderValues.deflate),
              )
            } yield response
            assertM(res.flatMap(_.bodyAsString))(equalTo(content))
          }
      }
  }

  def requestSpec = suite("RequestSpec") {
    val app: HttpApp[Any, Nothing] = Http.collect[Request] { case req =>
      Response.text(req.contentLength.getOrElse(-1).toString)
    }
    test("has content-length") {
      checkAll(Gen.alphaNumericString) { string =>
        val res = app.deploy.bodyAsString.run(content = HttpData.fromString(string))
        assertM(res)(equalTo(string.length.toString))
      }
    } +
      test("POST Request.getBody") {
        val app = Http.collectZIO[Request] { case req => req.body.as(Response.ok) }
        val res = app.deploy.status.run(path = !!, method = Method.POST, content = HttpData.fromString("some text"))
        assertM(res)(equalTo(Status.Ok))
      }
  }

  def responseSpec    = suite("ResponseSpec") {
    test("data") {
      checkAll(nonEmptyContent) { case (string, data) =>
        val res = Http.fromData(data).deploy.bodyAsString.run()
        assertM(res)(equalTo(string))
      }
    } +
      test("data from file") {
        val res = Http.fromResource("TestFile.txt").deploy.bodyAsString.run()
        assertM(res)(equalTo("abc\nfoo"))
      } +
      test("content-type header on file response") {
        val res =
          Http
            .fromResource("TestFile2.mp4")
            .deploy
            .headerValue(HeaderNames.contentType)
            .run()
            .map(_.getOrElse("Content type header not found."))
        assertM(res)(equalTo("video/mp4"))
      } +
      test("status") {
        checkAll(HttpGen.status) { case status =>
          val res = Http.status(status).deploy.status.run()
          assertM(res)(equalTo(status))
        }

      } +
      test("header") {
        checkAll(HttpGen.header) { case header @ (name, value) =>
          val res = Http.ok.addHeader(header).deploy.headerValue(name).run()
          assertM(res)(isSome(equalTo(value)))
        }
      } +
      test("text streaming") {
        val res = Http.fromStream(ZStream("a", "b", "c")).deploy.bodyAsString.run()
        assertM(res)(equalTo("abc"))
      } +
      test("echo streaming") {
        val res = Http
          .collectHttp[Request] { case req =>
            Http.fromStream(ZStream.fromZIO(req.body).flattenChunks)
          }
          .deploy
          .bodyAsString
          .run(content = HttpData.fromString("abc"))
        assertM(res)(equalTo("abc"))
      } +
      test("file-streaming") {
        val path = getClass.getResource("/TestFile.txt").getPath
        val res  = Http.fromStream(ZStream.fromPath(Paths.get(path))).deploy.bodyAsString.run()
        assertM(res)(equalTo("abc\nfoo"))
      } +
      suite("html") {
        test("body") {
          val res = Http.html(html(body(div(id := "foo", "bar")))).deploy.bodyAsString.run()
          assertM(res)(equalTo("""<!DOCTYPE html><html><body><div id="foo">bar</div></body></html>"""))
        } +
          test("content-type") {
            val app = Http.html(html(body(div(id := "foo", "bar"))))
            val res = app.deploy.headerValue(HeaderNames.contentType).run()
            assertM(res)(isSome(equalTo(HeaderValues.textHtml.toString)))
          }
      } +
      suite("content-length") {
        suite("string") {
          test("unicode text") {
            val res = Http.text("äöü").deploy.contentLength.run()
            assertM(res)(isSome(equalTo(6L)))
          } +
            test("already set") {
              val res = Http.text("1234567890").withContentLength(4L).deploy.contentLength.run()
              assertM(res)(isSome(equalTo(4L)))
            }
        }
      } +
      suite("memoize") {
        test("concurrent") {
          val size     = 100
          val expected = (0 to size) map (_ => Status.Ok)
          for {
            response <- Response.text("abc").freeze
            actual   <- ZIO.foreachPar(0 to size)(_ => Http.response(response).deploy.status.run())
          } yield assert(actual)(equalTo(expected))
        } +
          test("update after cache") {
            val server = "ZIO-Http"
            for {
              res    <- Response.text("abc").freeze
              actual <- Http.response(res).withServer(server).deploy.headerValue(HeaderNames.server).run()
            } yield assert(actual)(isSome(equalTo(server)))
          }
      }
  }
  def requestBodySpec = suite("RequestBodySpec") {
    test("POST Request stream") {
      val app: Http[Any, Throwable, Request, Response] = Http.collect[Request] { case req =>
        Response(data = HttpData.fromStream(req.bodyAsStream))
      }
      checkAll(Gen.alphaNumericString) { c =>
        assertM(app.deploy.bodyAsString.run(path = !!, method = Method.POST, content = HttpData.fromString(c)))(
          equalTo(c),
        )
      }
    }
  }

  def serverErrorSpec = suite("ServerErrorSpec") {
    val app = Http.fail(new Error("SERVER_ERROR"))
    test("status is 500") {
      val res = app.deploy.status.run()
      assertM(res)(equalTo(Status.InternalServerError))
    } +
      test("content is set") {
        val res = app.deploy.bodyAsString.run()
        assertM(res)(containsString("SERVER_ERROR"))
      } +
      test("header is set") {
        val res = app.deploy.headers.run().map(_.headerValue("Content-Length"))
        assertM(res)(isSome(anything))
      }
  }

  override def spec =
    suite("Server") {
      val spec = dynamicAppSpec + responseSpec + requestSpec + requestBodySpec + serverErrorSpec
      suite("app without request streaming") { app.as(List(spec)).useNow } +
        suite("app with request streaming") { appWithReqStreaming.as(List(spec)).useNow }
    }.provideCustomLayerShared(env) @@ timeout(30 seconds) @@ sequential

}
