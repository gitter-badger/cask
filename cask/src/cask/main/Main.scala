package cask.main

import cask.model._
import cask.internal.Router.EntryPoint
import cask.internal.{DispatchTrie, Router, Util}
import io.undertow.Undertow
import io.undertow.server.{HttpHandler, HttpServerExchange}
import io.undertow.server.handlers.BlockingHandler
import io.undertow.util.HttpString

class MainRoutes extends BaseMain with Routes{
  def allRoutes = Seq(this)
}
class Main(servers0: Routes*) extends BaseMain{
  def allRoutes = servers0.toSeq
}
abstract class BaseMain{
  def mainDecorators = Seq.empty[cask.main.Decorator]
  def allRoutes: Seq[Routes]
  def port: Int = 8080
  def host: String = "localhost"
  def debugMode: Boolean = true

  lazy val routeList = for{
    routes <- allRoutes
    route <- routes.caskMetadata.value.map(x => x: Routes.EndpointMetadata[_])
  } yield (routes, route)


  lazy val routeTries = Seq("get", "put", "post")
    .map { method =>
      method -> DispatchTrie.construct[(Routes, Routes.EndpointMetadata[_])](0,
        for ((route, metadata) <- routeList if metadata.endpoint.methods.contains(method))
        yield (Util.splitPath(metadata.endpoint.path): IndexedSeq[String], (route, metadata), metadata.endpoint.subpath)
      )
    }.toMap

  def writeResponse(exchange: HttpServerExchange, response: Response) = {
    response.headers.foreach{case (k, v) =>
      exchange.getResponseHeaders.put(new HttpString(k), v)
    }
    response.cookies.foreach(c => exchange.setResponseCookie(Cookie.toUndertow(c)))

    exchange.setStatusCode(response.statusCode)
    response.data.write(exchange.getOutputStream)
  }

  def handleNotFound(): Response = {
    Response(
      s"Error 404: ${Status.codesToStatus(404).reason}",
      statusCode = 404
    )
  }


  def defaultHandler = new BlockingHandler(
    new HttpHandler() {
      def handleRequest(exchange: HttpServerExchange): Unit = {
        routeTries(exchange.getRequestMethod.toString.toLowerCase()).lookup(Util.splitPath(exchange.getRequestPath).toList, Map()) match{
          case None => writeResponse(exchange, handleNotFound())
          case Some(((routes, metadata), extBindings, remaining)) =>
            val ctx = ParamContext(exchange, remaining)
            def rec(remaining: List[Decorator],
                    bindings: List[Map[String, Any]]): Router.Result[Response] = try {
              remaining match {
                case head :: rest =>
                  head.wrapFunction(ctx, args => rec(rest, args :: bindings))

                case Nil =>
                  metadata.endpoint.wrapFunction(ctx, epBindings =>
                    metadata.entryPoint
                      .asInstanceOf[EntryPoint[cask.main.Routes, cask.model.ParamContext]]
                      .invoke(routes, ctx, (epBindings ++ extBindings.mapValues(metadata.endpoint.wrapPathSegment)) :: bindings.reverse)
                      .asInstanceOf[Router.Result[Nothing]]
                  )

              }
            // Make sure we wrap any exceptions that bubble up from decorator
            // bodies, so outer decorators do not need to worry about their
            // delegate throwing on them
            }catch{case e: Throwable => Router.Result.Error.Exception(e) }

            rec((metadata.decorators ++ routes.decorators ++ mainDecorators).toList, Nil)match{
              case Router.Result.Success(response: Response) => writeResponse(exchange, response)
              case e: Router.Result.Error => writeResponse(exchange, handleEndpointError(exchange, routes, metadata, e))
            }
        }
      }
    }
  )

  def handleEndpointError(exchange: HttpServerExchange,
                          routes: Routes,
                          metadata: Routes.EndpointMetadata[_],
                          e: Router.Result.Error) = {
    val statusCode = e match {
      case _: Router.Result.Error.Exception => 500
      case _: Router.Result.Error.InvalidArguments => 400
      case _: Router.Result.Error.MismatchedArguments => 400
    }
    Response(
      if (!debugMode) s"Error $statusCode: ${Status.codesToStatus(statusCode).reason}"
      else ErrorMsgs.formatInvokeError(
        routes,
        metadata.entryPoint.asInstanceOf[EntryPoint[cask.main.Routes, _]],
        e
      ),
      statusCode = statusCode
    )

  }


  def main(args: Array[String]): Unit = {
    val server = Undertow.builder
      .addHttpListener(port, host)
      .setHandler(defaultHandler)
      .build
    server.start()
  }
}


