package pl.touk.nussknacker.ui.api


import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Directive, Directives, Route}
import akka.stream.Materializer
import argonaut._
import cats.data.Validated.{Invalid, Valid}
import cats.instances.future._
import cats.data.EitherT
import cats.syntax.either._
import pl.touk.nussknacker.engine.api.deployment.GraphProcess
import pl.touk.nussknacker.ui.api.ProcessesResources.{UnmarshallError, WrongProcessId}
import pl.touk.nussknacker.ui.process.displayedgraph.{DisplayableProcess, ProcessStatus, ValidatedDisplayableProcess}
import pl.touk.nussknacker.ui.process.marshall.{ProcessConverter, UiProcessMarshaller}
import pl.touk.nussknacker.ui.process.repository.{FetchingProcessRepository, ProcessActivityRepository, WriteProcessRepository}
import pl.touk.nussknacker.ui.process.repository.ProcessRepository._
import pl.touk.nussknacker.ui.util._
import pl.touk.nussknacker.ui._
import EspErrorToHttp._
import pl.touk.nussknacker.ui.codec.UiCodecs
import pl.touk.nussknacker.ui.validation.ProcessValidation
import pl.touk.nussknacker.ui.db.entity.ProcessEntity.ProcessingType.ProcessingType
import pl.touk.nussknacker.ui.process.{JobStatusService, NewProcessPreparer, ProcessToSave, ProcessTypesForCategories}
import pl.touk.http.argonaut.Argonaut62Support
import pl.touk.nussknacker.ui.process.repository.WriteProcessRepository.UpdateProcessAction
import pl.touk.nussknacker.ui.security.api.{LoggedUser, Permission}

import scala.concurrent.{ExecutionContext, Future}

class ProcessesResources(repository: FetchingProcessRepository,
                         writeRepository: WriteProcessRepository,
                         jobStatusService: JobStatusService,
                         processActivityRepository: ProcessActivityRepository,
                         processValidation: ProcessValidation,
                         typesForCategories: ProcessTypesForCategories,
                         newProcessPreparer: NewProcessPreparer)
                        (implicit ec: ExecutionContext, mat: Materializer)
  extends Directives with Argonaut62Support with EspPathMatchers with RouteWithUser {

  import UiCodecs._

  def route(implicit user: LoggedUser): Route = {
    def authorizeMethod = extractMethod.flatMap[Unit] {
      case HttpMethods.POST | HttpMethods.PUT | HttpMethods.DELETE => authorize(user.hasPermission(Permission.Write))
      case HttpMethods.GET => authorize(user.hasPermission(Permission.Read))
      case _ => Directive.Empty
    }

    authorizeMethod {
      path("processes") {
        get {
          complete {
            validateAll(repository.fetchProcessesDetails())
          }
        }
      } ~ path("subProcesses") {
        get {
          complete {
            validateAll(repository.fetchSubProcessesDetails())
          }
        }
      } ~ path("processes" / "status") {
        get {
          complete {
            repository.fetchProcessesDetails().flatMap(fetchProcessStatesForProcesses)
          }
        }

      } ~ path("processes" / Segment) { processId =>
        parameter('businessView ? false) { (businessView) =>
          get {
            complete {
              repository.fetchLatestProcessDetailsForProcessId(processId, businessView).map[ToResponseMarshallable] {
                case Some(process) => validate(process, businessView)
                case None => HttpResponse(status = StatusCodes.NotFound, entity = "Process not found")
              }
            }
          }
        } ~ delete {
          complete {
            writeRepository.deleteProcess(processId).map(toResponse(StatusCodes.OK))
          }
        }
      } ~ path("processes" / Segment / LongNumber) { (processId, versionId) =>
        parameter('businessView ? false) { (businessView) =>
          get {
            complete {
              repository.fetchProcessDetailsForId(processId, versionId, businessView).map[ToResponseMarshallable] {
                case Some(process) => validate(process,  businessView)
                case None => HttpResponse(status = StatusCodes.NotFound, entity = "Process not found")
              }
            }
          }
        }
      } ~ path("processes" / Segment) { processId =>
        put {
          entity(as[ProcessToSave]) { processToSave =>
            complete {
              val displayableProcess = processToSave.process
              val canonical = ProcessConverter.fromDisplayable(displayableProcess)
              val json = UiProcessMarshaller.toJson(canonical, PrettyParams.nospace)
              val deploymentData = GraphProcess(json)

              (for {
                validation <- EitherT.fromEither[Future](processValidation.validate(displayableProcess).saveNotAllowedAsError)
                result <- EitherT(writeRepository.updateProcess(UpdateProcessAction(processId, deploymentData, processToSave.comment)))
              } yield validation).value.map(toResponse)
            }
          }
        }
      } ~ path("processes" / Segment / Segment) { (processId, category) =>
        authorize(user.categories.contains(category)) {
          parameter('isSubprocess ? false) { (isSubprocess) =>
            post {
              complete {
                typesForCategories.getTypeForCategory(category) match {
                  case Some(processingType) =>
                    val emptyProcess = makeEmptyProcess(processId, processingType, isSubprocess)
                    writeRepository.saveNewProcess(processId, category, emptyProcess, processingType, isSubprocess)
                        .map(toResponse(StatusCodes.Created))
                  case None => Future(HttpResponse(status = StatusCodes.BadRequest, entity = "Process category not found"))
                }
              }
            }
          }
        }
      } ~ path("processes" / Segment / "status") { processId =>
        get {
          complete {
            repository.fetchLatestProcessDetailsForProcessId(processId).flatMap[ToResponseMarshallable] {
              case Some(process) =>
                findJobStatus(process.name, process.processingType).map {
                  case Some(status) => status
                  case None => HttpResponse(status = StatusCodes.OK, entity = "Process is not running")
                }
              case None =>
                Future.successful(HttpResponse(status = StatusCodes.NotFound, entity = "Process not found"))
            }
          }
        }
      } ~ path("processes" / "category" / Segment / Segment) { (processId, category) =>
        post {
          complete {
            writeRepository.updateCategory(processId = processId, category = category).map(toResponse(StatusCodes.OK))
          }
        }
      } ~ path("processes" / Segment / LongNumber / "compare" / LongNumber) { (processId, thisVersion, otherVersion) =>
        parameter('businessView ? false) { (businessView) =>
          get {
            complete {
              withJson(processId, thisVersion, businessView) { thisDisplayable =>
                withJson(processId, otherVersion, businessView) { otherDisplayable =>
                  implicit val codec = ProcessComparator.codec
                  ProcessComparator.compare(thisDisplayable, otherDisplayable)
                }
              }
            }
          }
        }
      } ~ path("processes" / "import" / Segment) { processId =>
        post {
          fileUpload("process") { case (metadata, byteSource) =>
            complete {
              MultipartUtils.readFile(byteSource).map[ToResponseMarshallable] { json =>
                (UiProcessMarshaller.fromJson(json) match {
                  case Valid(process) if process.metaData.id != processId => Invalid(WrongProcessId(processId, process.metaData.id))
                  case Valid(process) => Valid(process)
                  case Invalid(unmarshallError) => Invalid(UnmarshallError(unmarshallError.msg))
                }) match {
                  case Valid(process) =>
                    repository.fetchLatestProcessDetailsForProcessIdEither(processId).map { detailsXor =>
                      val validatedProcess = detailsXor.map(details =>
                        ProcessConverter.toDisplayable(process, details.processingType).validated(processValidation)
                      )
                      toResponseXor(validatedProcess)
                    }

                  case Invalid(error) => espErrorToHttp(error)
                }
              }
            }
          }
        }
      }
    }
  }

  private def fetchProcessStatesForProcesses(processes: List[ProcessDetails])(implicit user: LoggedUser): Future[Map[String, Option[ProcessStatus]]] = {
    import cats.instances.future._
    import cats.instances.list._
    import cats.syntax.traverse._
    processes.map(process => findJobStatus(process.name, process.processingType).map(status => process.name -> status)).sequence.map(_.toMap)
  }

  private def findJobStatus(processName: String, processingType: ProcessingType)(implicit ec: ExecutionContext, user: LoggedUser): Future[Option[ProcessStatus]] = {
    jobStatusService.retrieveJobStatus(processName)
  }

  private def makeEmptyProcess(processId: String, processingType: ProcessingType, isSubprocess: Boolean) = {
    val emptyCanonical = newProcessPreparer.prepareEmptyProcess(processId, processingType, isSubprocess)
    GraphProcess(UiProcessMarshaller.toJson(emptyCanonical, PrettyParams.nospace))
  }

  private def withJson(processId: String, version: Long, businessView: Boolean)
                      (process: DisplayableProcess => ToResponseMarshallable)(implicit user: LoggedUser): ToResponseMarshallable
  = repository.fetchProcessDetailsForId(processId, version, businessView).map { maybeProcess =>
      maybeProcess.flatMap(_.json) match {
        case Some(displayable) => process(displayable)
        case None => HttpResponse(status = StatusCodes.NotFound, entity = s"Process $processId in version $version not found"): ToResponseMarshallable
      }
  }

  private def validate(processDetails: ProcessDetails, businessView: Boolean): Future[ValidatedProcessDetails] = {
    if (businessView) Future.successful(processDetails.mapProcess(_.withSuccessValidation())) else validate(processDetails)
  }

  private def validate(processDetails: ProcessDetails) : Future[ValidatedProcessDetails] = {
    Future.successful(processDetails.mapProcess(_.validated(processValidation)))
  }

  private def validateAll(processDetails: Future[List[ProcessDetails]]) : Future[List[ValidatedProcessDetails]] = {
    processDetails.flatMap(all => Future.sequence(all.map(validate)))
  }

}

object ProcessesResources {

  case class UnmarshallError(message: String) extends Exception(message) with FatalError

  case class WrongProcessId(processId: String, givenId: String) extends Exception(s"Process has id $givenId instead of $processId") with BadRequestError

  case class ProcessNotInitializedError(id: String) extends Exception(s"Process $id is not initialized") with NotFoundError

  case class NodeNotFoundError(processId: String, nodeId: String) extends Exception(s"Node $nodeId not found inside process $processId") with NotFoundError

}