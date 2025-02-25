package pl.touk.nussknacker.engine.requestresponse.http

import cats.data.Validated.Invalid
import cats.data.{NonEmptyList, Validated}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import pl.touk.nussknacker.engine.api.RequestResponseMetaData
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError.{EmptyProcess, InvalidRootNode, InvalidTailOfBranch}
import pl.touk.nussknacker.engine.api.context.{ProcessCompilationError, ProcessUncanonizationError}
import pl.touk.nussknacker.engine.api.process.{ComponentUseCase, ProcessName}
import pl.touk.nussknacker.engine.canonize.ProcessCanonizer
import pl.touk.nussknacker.engine.graph.EspProcess
import pl.touk.nussknacker.engine.lite.api.runtimecontext.LiteEngineRuntimeContextPreparer
import pl.touk.nussknacker.engine.requestresponse.FutureBasedRequestResponseScenarioInterpreter.InterpreterType
import pl.touk.nussknacker.engine.requestresponse.deployment._
import pl.touk.nussknacker.engine.requestresponse.RequestResponseInterpreter
import pl.touk.nussknacker.engine.resultcollector.ProductionServiceInvocationCollector
import pl.touk.nussknacker.engine.util.config.CustomFicusInstances._
import pl.touk.nussknacker.engine.util.loader.ModelClassLoader
import pl.touk.nussknacker.engine.{ModelData, canonize}

import java.net.URL
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

object DeploymentService {

  val modelConfigPath = "modelConfig"

  //TODO this is temporary solution, we should keep these processes e.g. in ZK
  //also: how to pass model data around?
  def apply(context: LiteEngineRuntimeContextPreparer, config: Config): DeploymentService = {
    val modelConfig = config.getConfig(modelConfigPath)
    val modelData = ModelData(modelConfig, ModelClassLoader(modelConfig.as[List[URL]]("classPath")))
    new DeploymentService(context, modelData, FileProcessRepository(config.getString("scenarioRepositoryLocation")))
  }

}

class DeploymentService(context: LiteEngineRuntimeContextPreparer, modelData: ModelData, processRepository: ProcessRepository) extends LazyLogging {

  private val processInterpreters: TrieMap[ProcessName, (InterpreterType, RequestResponseDeploymentData)] = TrieMap()

  private val pathToHolder: TrieMap[String, ScenarioHandlerHolder] = TrieMap()

  initProcesses()

  private def initProcesses() : Unit = {
    val deploymentResults = processRepository.loadAll.map { case (id, deploymentData) =>
      (id, deploy(deploymentData)(ExecutionContext.Implicits.global))
    }
    deploymentResults.collect {
      case (id, Left(errors)) => logger.error(s"Failed to deploy $id, errors: $errors")
    }
  }

  def fromUncanonizationError(err: canonize.ProcessUncanonizationError): ProcessUncanonizationError = {
    err match {
      case canonize.EmptyProcess => EmptyProcess
      case canonize.InvalidRootNode(nodeId) => InvalidRootNode(nodeId)
      case canonize.InvalidTailOfBranch(nodeId) => InvalidTailOfBranch(nodeId)
    }
  }

  def deploy(deploymentData: RequestResponseDeploymentData)(implicit ec: ExecutionContext): Either[NonEmptyList[DeploymentError], Unit] = {
    val processName: ProcessName = deploymentData.processVersion.processName

    ProcessCanonizer.uncanonize(deploymentData.processJson).leftMap(_.map(fromUncanonizationError).map(DeploymentError(_))).andThen { process =>
      process.metaData.typeSpecificData match {
        case RequestResponseMetaData(path) =>
          val pathToDeploy = path.getOrElse(processName.value)
          val currentAtPath = pathToHolder.get(pathToDeploy).map(_.id)
          currentAtPath match {
            case Some(oldId) if oldId != processName =>
              Invalid(NonEmptyList.of(DeploymentError(Set(), s"Scenario $oldId is already deployed at path $pathToDeploy")))
            case _ =>
              val interpreter = newInterpreter(process, deploymentData)
              interpreter.foreach { processInterpreter =>
                cancel(processName)
                processRepository.add(processName, deploymentData)
                processInterpreters.put(processName, (processInterpreter, deploymentData))

                val handlerHolder = ScenarioHandlerHolder(processName, pathToDeploy, new RequestResponseHandler(processInterpreter))
                pathToHolder.put(pathToDeploy, handlerHolder)

                processInterpreter.open()
                logger.info(s"Successfully deployed scenario ${processName.value}")
              }
              interpreter.map(_ => ())
          }
        case _ => Invalid(NonEmptyList.of(DeploymentError(Set(), "Wrong scenario type")))
      }
    }.toEither

  }

  def checkStatus(processName: ProcessName): Option[DeploymentStatus] = {
    processInterpreters.get(processName).map { case (_, RequestResponseDeploymentData(_, deploymentTime, processVersion, _)) =>
      DeploymentStatus(processVersion, deploymentTime)
    }
  }

  def cancel(processName: ProcessName): Option[Unit] = {
    processRepository.remove(processName)
    val removed = processInterpreters.remove(processName)

    removed.foreach { _ =>
      pathToHolder.filter(_._2.id == processName).foreach { case (k, _) => pathToHolder.remove(k) }
    }

    removed.foreach(_._1.close())
    removed.map(_ => ())
  }

  def getInterpreterHandlerByPath(path: String): Option[RequestResponseHandler] =
    pathToHolder
      .get(path)
      .map(_.handler)

  private def newInterpreter(process: EspProcess, deploymentData: RequestResponseDeploymentData): Validated[NonEmptyList[DeploymentError], InterpreterType] = {
    import pl.touk.nussknacker.engine.requestresponse.FutureBasedRequestResponseScenarioInterpreter._

    import ExecutionContext.Implicits._

    RequestResponseInterpreter[Future](process, deploymentData.processVersion,
      context, modelData, Nil, ProductionServiceInvocationCollector, ComponentUseCase.EngineRuntime).leftMap(_.map(DeploymentError(_)))
  }

  case class ScenarioHandlerHolder(id: ProcessName, path: String, handler: RequestResponseHandler)

}

case class DeploymentError(nodeIds: Set[String], message: String)

object DeploymentError {
  def apply(error: ProcessCompilationError) : DeploymentError = DeploymentError(error.nodeIds, error.toString)
}
