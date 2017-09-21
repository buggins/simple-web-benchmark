package lite

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer

object WebServer {
  def main(args: Array[String]) {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val route =
      path("greeting" / Segment) { user =>
        get {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Hello, " + user))
        }
      } ~
      path("") {
        get {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Hello World!"))
        }
      }

    Http().bindAndHandle(route, "localhost", 3000)
  }
}

