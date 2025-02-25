package pl.touk.nussknacker.engine.lite.requestresponse

import akka.event.Logging
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, ResponseEntity, StatusCodes}
import akka.http.scaladsl.server.directives.DebuggingDirectives
import akka.http.scaladsl.server.{Directive0, Directive1, Directives, Route}
import akka.stream.Materializer
import cats.data.{EitherT, NonEmptyList}
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.JsonCodec
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}
import pl.touk.nussknacker.engine.api.Context
import pl.touk.nussknacker.engine.api.component.NodeComponentInfo
import pl.touk.nussknacker.engine.api.exception.NuExceptionInfo
import pl.touk.nussknacker.engine.lite.api.commonTypes.ErrorType
import pl.touk.nussknacker.engine.requestresponse.DefaultResponseEncoder
import pl.touk.nussknacker.engine.requestresponse.FutureBasedRequestResponseScenarioInterpreter.InterpreterType
import pl.touk.nussknacker.engine.requestresponse.api.{RequestResponseGetSource, RequestResponsePostSource}
import pl.touk.nussknacker.engine.requestresponse.metrics.InvocationMetrics

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ScenarioRoute(processInterpreters: scala.collection.Map[String, RequestResponseAkkaHttpHandler]) extends Directives with LazyLogging {

  protected def logDirective(scenarioName: String): Directive0 = DebuggingDirectives.logRequestResult((s"request-response-$scenarioName", Logging.DebugLevel))

  def route(implicit ec: ExecutionContext, mat: Materializer): Route =
    path("scenario" / Segment) { scenarioPath =>
      logDirective(scenarioPath) {
        handlePath(scenarioPath)
      }
      //TODO place openApi endpoint
    } ~ path("healthCheck") {
      get {
        complete {
          HttpResponse(status = StatusCodes.OK)
        }
      }
    }

  private def toEntity[T: Encoder](value: T): ResponseEntity = HttpEntity(contentType = `application/json`, string = value.asJson.noSpacesSortKeys)

  private def handlePath(scenarioPath: String) = processInterpreters.get(scenarioPath) match {
    case None =>
      complete {
        HttpResponse(status = StatusCodes.NotFound)
      }
    case Some(processInterpreter) => processInterpreter.invoke {
      case Left(errors) => complete {
        logErrors(scenarioPath, errors)
        HttpResponse(status = StatusCodes.InternalServerError, entity = toEntity(errors.toList.map(info => NuError(info.nodeComponentInfo.map(_.nodeId), Option(info.throwable.getMessage)))))
      }
      case Right(results) => complete {
        HttpResponse(status = StatusCodes.OK, entity = toEntity(results))
      }
    }
  }

  private def logErrors(scenarioPath: String, errors: NonEmptyList[NuExceptionInfo[_ <: Throwable]]): Unit = {
    logger.info(s"Failed to invoke: $scenarioPath with errors: ${errors.map(_.throwable.getMessage)}")
    errors.toList.foreach { error =>
      logger.debug(s"Invocation failed $scenarioPath, error in ${error.nodeComponentInfo.map(_.nodeId)}: ${error.throwable.getMessage}", error.throwable)
    }
  }

  @JsonCodec case class NuError(nodeId: Option[String], message: Option[String])

}

//this class handles parsing, displaying and invoking interpreter. This is the only place we interact with model, hence
//only here we care about context classloaders
class RequestResponseAkkaHttpHandler(requestResponseInterpreter: InterpreterType) extends Directives {

  val invoke: Directive1[Either[NonEmptyList[ErrorType], Json]] =
    extractExecutionContext.flatMap { implicit ec =>
      extractInput.map(invokeWithEncoding).flatMap(onSuccess(_))
    }

  private val source = requestResponseInterpreter.source
  private val encoder = source.responseEncoder.getOrElse(DefaultResponseEncoder)
  private val invocationMetrics = new InvocationMetrics(requestResponseInterpreter.context)

  private val extractInput: Directive1[() => Any] = source match {
    case a: RequestResponsePostSource[Any] =>
      post & entity(as[Array[Byte]]).map(k => () => a.parse(k))
    case a: RequestResponseGetSource[Any] =>
      get & parameterMultiMap.map(k => () => a.parse(k))
  }

  //TODO: refactor responseEncoder/source API
  private def invokeWithEncoding(inputParse: () => Any)(implicit ec: ExecutionContext) = {
    (for {
      input <- tryInvoke(inputParse())
      rawResult <- EitherT(invokeInterpreter(input))
      encoderResult <- tryInvoke(encoder.toJsonResponse(input, rawResult))
    } yield encoderResult).value
  }

  private def invokeInterpreter(input: Any)(implicit ec: ExecutionContext) = invocationMetrics.measureTime {
    requestResponseInterpreter.invokeToOutput(input)
  }.map(_.toEither)

  private def tryInvoke[T](value: =>T)(implicit ec: ExecutionContext): EitherT[Future, NonEmptyList[ErrorType], T] =
    EitherT.fromEither[Future](Try(value).toEither.left.map(ex => NonEmptyList.one(
      NuExceptionInfo(Some(NodeComponentInfo(requestResponseInterpreter.sourceId.value, None)), ex, Context("")))))

}