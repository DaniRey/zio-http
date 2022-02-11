package example

import zhttp.http._
import zhttp.service.server.ServerChannelFactory
import zhttp.service.server.content.compression.CompressionOptions
import zhttp.service.{EventLoopGroup, Server}
import zio._

import scala.util.Try

object HelloWorldAdvanced extends App {
  // Set a port
  private val PORT = 80

  private val fooBar: HttpApp[Any, Nothing] = Http.collect[Request] {
    case Method.GET -> !! / "foo" => Response.text("bar")
    case Method.GET -> !! / "bar" => Response.text("foo")
  }

  private val app = Http.collectZIO[Request] {
    case Method.GET -> !! / "random"            => random.nextString(10).map(Response.text(_))
    case Method.GET -> !! / "utc"               => clock.currentDateTime.map(s => Response.text(s.toString))
    case req @ Method.GET -> !! / "compression" =>
      req.bodyAsString.map { body =>
        println(body)
        Response.text(body)
      }.orDie
  }

  private val server = Server.port(PORT) ++
    Server.httpCompression(0, IndexedSeq(CompressionOptions.deflate(), CompressionOptions.gzip())) ++
    Server.app(fooBar ++ app)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    // Configure thread count using CLI
    val nThreads: Int = args.headOption.flatMap(x => Try(x.toInt).toOption).getOrElse(0)

    // Create a new server
    server.make
      .use(start =>
        // Waiting for the server to start
        console.putStrLn(s"Server started on port ${start.port}")

        // Ensures the server doesn't die after printing
          *> ZIO.never,
      )
      .provideCustomLayer(ServerChannelFactory.auto ++ EventLoopGroup.auto(nThreads))
      .exitCode
  }
}
