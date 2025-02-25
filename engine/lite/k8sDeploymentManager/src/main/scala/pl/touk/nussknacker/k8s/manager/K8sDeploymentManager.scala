package pl.touk.nussknacker.k8s.manager

import akka.actor.ActorSystem
import cats.data.Validated
import com.typesafe.config.ConfigValueFactory.fromAnyRef
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import io.circe.syntax._
import pl.touk.nussknacker.engine.ModelData.BaseModelDataExt
import pl.touk.nussknacker.engine.api.deployment._
import pl.touk.nussknacker.engine.api.process.ProcessName
import pl.touk.nussknacker.engine.api.queryablestate.QueryableClient
import pl.touk.nussknacker.engine.api.test.TestData
import pl.touk.nussknacker.engine.api.{CirceUtil, LiteStreamMetaData, ProcessVersion}
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.canonize.ProcessCanonizer
import pl.touk.nussknacker.engine.deployment.{DeploymentData, ExternalDeploymentId, User}
import pl.touk.nussknacker.engine.lite.kafka.KafkaTransactionalScenarioInterpreter
import pl.touk.nussknacker.engine.testmode.TestProcess
import pl.touk.nussknacker.engine.util.config.ConfigEnrichments.RichConfig
import pl.touk.nussknacker.engine.version.BuildInfo
import pl.touk.nussknacker.engine.{BaseModelData, DeploymentManagerProvider, TypeSpecificInitialData}
import pl.touk.nussknacker.k8s.manager.K8sDeploymentManager._
import pl.touk.nussknacker.k8s.manager.K8sUtils.{sanitizeLabel, sanitizeObjectName, shortHash}
import pl.touk.nussknacker.k8s.manager.deployment.{DeploymentPreparer, K8sScalingConfig, K8sScalingOptionsDeterminer, MountableResources}
import skuber.LabelSelector.Requirement
import skuber.LabelSelector.dsl._
import skuber.apps.v1.Deployment
import skuber.json.format._
import skuber.{ConfigMap, LabelSelector, ListResource, ObjectMeta, Pod, ResourceQuotaList, Secret, k8sInit}
import sttp.client.{NothingT, SttpBackend}

import java.util.Collections
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.language.reflectiveCalls
import scala.util.Using

/*
  Each scenario is deployed as Deployment+ConfigMap
  ConfigMap contains: model config with overrides for execution and scenario
 */
class K8sDeploymentManagerProvider extends DeploymentManagerProvider {
  override def createDeploymentManager(modelData: BaseModelData, config: Config)
                                      (implicit ec: ExecutionContext, actorSystem: ActorSystem,
                                       sttpBackend: SttpBackend[Future, Nothing, NothingT],
                                       deploymentService: ProcessingTypeDeploymentService): DeploymentManager = {
    K8sDeploymentManager(modelData.asInvokableModelData, config)
  }

  override def createQueryableClient(config: Config): Option[QueryableClient] = None

  override def typeSpecificInitialData: TypeSpecificInitialData = TypeSpecificInitialData(LiteStreamMetaData(Some(1)))

  override def supportsSignals: Boolean = false

  override def name: String = "streaming-lite-k8s"
}

case class K8sDeploymentManagerConfig(dockerImageName: String = "touk/nussknacker-lite-kafka-runtime",
                                      dockerImageTag: String = BuildInfo.version,
                                      scalingConfig: Option[K8sScalingConfig] = None,
                                      configExecutionOverrides: Config = ConfigFactory.empty(),
                                      k8sDeploymentConfig: Config = ConfigFactory.empty(),
                                      nussknackerInstanceName: Option[String] = None,
                                      logbackConfigPath: Option[String] = None,
                                      commonConfigMapForLogback: Option[String] = None,
                                     )

class K8sDeploymentManager(modelData: BaseModelData, config: K8sDeploymentManagerConfig)
                          (implicit ec: ExecutionContext, actorSystem: ActorSystem) extends BaseDeploymentManager with LazyLogging {

  //TODO: how to use dev-application.conf with not k8s config?
  private lazy val k8s = k8sInit
  private lazy val k8sUtils = new K8sUtils(k8s)
  private val deploymentPreparer = new DeploymentPreparer(config)
  private val scalingOptionsDeterminer = K8sScalingOptionsDeterminer(config.scalingConfig)

  private val serializedModelConfig = {
    val inputConfig = modelData.inputConfigDuringExecution
    //TODO: should overrides apply only to model or to whole config??
    val withOverrides = config.configExecutionOverrides.withFallback(wrapInModelConfig(inputConfig.config.withoutPath("classPath")))
    inputConfig.copy(config = withOverrides).serialized
  }

  private lazy val defaultLogbackConfig = Using.resource(Source.fromResource("runtime/default-logback.xml"))(_.mkString)
  private def logbackConfig: String = config.logbackConfigPath.map(path => Using.resource(Source.fromFile(path))(_.mkString)).getOrElse(defaultLogbackConfig)


  override def validate(processVersion: ProcessVersion, deploymentData: DeploymentData, canonicalProcess: CanonicalProcess): Future[Unit] = {
    val scalingOptions = determineScalingOptions(canonicalProcess)
    for {
      resourceQuotas <- k8s.list[ResourceQuotaList]()
      oldDeployment <- k8s.getOption[Deployment](objectNameForScenario(processVersion, config.nussknackerInstanceName, None))
      validationResult: Validated[Throwable, Unit] = K8sPodsResourceQuotaChecker.hasReachedQuotaLimit(oldDeployment.flatMap(_.spec.flatMap(_.replicas)), resourceQuotas, scalingOptions.replicasCount)
      _ <- Future.fromTry(validationResult.toEither.toTry)
    } yield ()
  }

  override def deploy(processVersion: ProcessVersion, deploymentData: DeploymentData,
                      canonicalProcess: CanonicalProcess,
                      savepointPath: Option[String]): Future[Option[ExternalDeploymentId]] = {
    val scalingOptions = determineScalingOptions(canonicalProcess)
    for {
      configMap <- k8sUtils.createOrUpdate(configMapForData(processVersion, canonicalProcess, config.nussknackerInstanceName)(Map(
        "scenario.json" -> canonicalProcess.asJson.noSpaces,
        "deploymentConfig.conf" -> ConfigFactory.empty().withValue("tasksCount", fromAnyRef(scalingOptions.noOfTasksInReplica)).root().render()
      )))
      loggingConfigMap <- k8sUtils.createOrUpdate(configMapForData(processVersion, canonicalProcess, config.nussknackerInstanceName)(
        Map("logback.xml" -> logbackConfig), additionalLabels = Map(resourceTypeLabel -> "logging-conf"), overrideName = config.commonConfigMapForLogback))
      //modelConfig.conf often contains confidential data e.g passwords, so we put it in secret, not configmap
      secret <- k8sUtils.createOrUpdate(secretForData(processVersion, canonicalProcess, config.nussknackerInstanceName)(Map("modelConfig.conf" -> serializedModelConfig)))
      mountableResources = MountableResources(commonConfigConfigMap = configMap.name, loggingConfigConfigMap = loggingConfigMap.name, modelConfigSecret = secret.name)
      deployment <- k8sUtils.createOrUpdate(deploymentPreparer.prepare(processVersion, mountableResources, scalingOptions.replicasCount))
      //we don't wait until deployment succeeds before deleting old map, but for now we don't rollback anyway
      //https://github.com/kubernetes/kubernetes/issues/22368#issuecomment-790794753
      _ <- k8s.deleteAllSelected[ListResource[ConfigMap]](LabelSelector(
        requirementForName(processVersion.processName),
        configMapIdLabel isNotIn List(configMap, loggingConfigMap).map(_.name)
      ))
      _ <- k8s.deleteAllSelected[ListResource[Secret]](LabelSelector(
        requirementForName(processVersion.processName),
        secretIdLabel isNot secret.name
      ))
    } yield {
      logger.info(s"Deployed ${processVersion.processName.value}, with deployment: ${deployment.name}, configmap: ${configMap.name}")
      logger.trace(s"K8s deployment name: ${deployment.name}: $deployment")
      logger.trace(s"K8s deployment name: ${deployment.name}: K8sDeploymentConfig: ${config.k8sDeploymentConfig}")
      None
    }
  }

  private def determineScalingOptions(canonicalProcess: CanonicalProcess) = {
    val parallelism = canonicalProcess.metaData.typeSpecificData.asInstanceOf[LiteStreamMetaData].parallelism.getOrElse(defaultParallelism)
    scalingOptionsDeterminer.determine(parallelism)
  }

  override def cancel(name: ProcessName, user: User): Future[Unit] = {
    val selector: LabelSelector = requirementForName(name)
    //We wait for deployment removal before removing configmaps,
    //in case of crash it's better to have unnecessary configmaps than deployments without config
    for {
      deployments <- k8s.deleteAllSelected[ListResource[Deployment]](selector)
      configMaps <- k8s.deleteAllSelected[ListResource[ConfigMap]](selector)
      secrets <- k8s.deleteAllSelected[ListResource[Secret]](selector)
    } yield {
      logger.debug(s"Canceled ${name.value}, removed deployments: ${deployments.itemNames}, configmaps: ${configMaps.itemNames}, secrets: ${secrets.itemNames}")
      ()
    }
  }

  override def test[T](name: ProcessName, canonicalProcess: CanonicalProcess, testData: TestData, variableEncoder: Any => T): Future[TestProcess.TestResults[T]] = {
    Future {
      modelData.asInvokableModelData.withThisAsContextClassLoader {
        val espProcess = ProcessCanonizer.uncanonizeUnsafe(canonicalProcess)
        KafkaTransactionalScenarioInterpreter.testRunner.runTest(modelData.asInvokableModelData, testData, espProcess, variableEncoder)
      }
    }
  }

  override def findJobStatus(name: ProcessName): Future[Option[ProcessState]] = {
    val mapper = new K8sDeploymentStatusMapper(processStateDefinitionManager)
    for {
      deployments <- k8s.listSelected[ListResource[Deployment]](requirementForName(name)).map(_.items)
      pods <- k8s.listSelected[ListResource[Pod]](requirementForName(name)).map(_.items)
    } yield mapper.findStatusForDeploymentsAndPods(deployments, pods)
  }

  private def configMapForData(processVersion: ProcessVersion, canonicalProcess: CanonicalProcess, nussknackerInstanceName: Option[String])
                                (data: Map[String, String], additionalLabels: Map[String, String] = Map.empty, overrideName: Option[String] = None): ConfigMap = {
    val scenario = canonicalProcess.asJson.spaces2
    //we append serialized data to name so we can guarantee pods will be restarted.
    //They *probably* will restart anyway, as scenario version is in label, but e.g. if only model config is changed?
    val objectName = overrideName.getOrElse(objectNameForScenario(processVersion, config.nussknackerInstanceName, Some(scenario + data.toString())))
    val identificationLabels: Map[String, String] = overrideName match {
      case Some(_) => Map.empty // when name is overridden, we do not want DeploymentManager to have control over whole
      // lifecycle of CM (e.g. delete it on scenario canceling)
      case None => labelsForScenario(processVersion, nussknackerInstanceName)
    }
    // TODO: extract lite-kafka-runtime-api module with LiteKafkaRuntimeDeploymentConfig class and use here
    ConfigMap(
      metadata = ObjectMeta(
        name = objectName,
        labels = identificationLabels + (configMapIdLabel -> objectName) ++ additionalLabels
      ), data = data
    )
  }

  private def secretForData(processVersion: ProcessVersion, canonicalProcess: CanonicalProcess, nussknackerInstanceName: Option[String])
                             (data: Map[String, String], additionalLabels: Map[String, String] = Map.empty): Secret = {
    val scenario = canonicalProcess.asJson.spaces2
    val objectName = objectNameForScenario(processVersion, config.nussknackerInstanceName, Some(scenario + data.toString()))
    Secret(
      metadata = ObjectMeta(
        name = objectName,
        labels = labelsForScenario(processVersion, nussknackerInstanceName) + (secretIdLabel -> objectName) ++ additionalLabels
      ), data = data.mapValues(_.getBytes)
    )
  }

  override def processStateDefinitionManager: ProcessStateDefinitionManager = K8sProcessStateDefinitionManager

  private def wrapInModelConfig(config: Config): Config = {
    ConfigFactory.parseMap(Collections.singletonMap("modelConfig", config.root()))
  }

}

object K8sDeploymentManager {

  import K8sScalingConfig.valueReader
  import net.ceedubs.ficus.Ficus._
  import net.ceedubs.ficus.readers.ArbitraryTypeReader._

  val defaultParallelism = 1

  val nussknackerInstanceNameLabel: String = "nussknacker.io/nussknackerInstanceName"

  val scenarioNameLabel: String = "nussknacker.io/scenarioName"

  val scenarioIdLabel: String = "nussknacker.io/scenarioId"

  val scenarioVersionLabel: String = "nussknacker.io/scenarioVersion"

  val scenarioVersionAnnotation: String = "nussknacker.io/scenarioVersion"

  val configMapIdLabel: String = "nussknacker.io/configMapId"

  val secretIdLabel: String = "nussknacker.io/secretId"

  val resourceTypeLabel: String = "nussknacker.io/resourceType"

  def apply(modelData: BaseModelData, config: Config)(implicit ec: ExecutionContext, actorSystem: ActorSystem): K8sDeploymentManager = {
    new K8sDeploymentManager(modelData, config.rootAs[K8sDeploymentManagerConfig])
  }

  /*
    We use name label to find deployment to cancel/findStatus, we append short hash to reduce collision risk
    if sanitized names are similar (e.g. they differ only in truncated part or non-alphanumeric characters
    TODO: Maybe it would be better to just pass ProcessId in findJobStatus/cancel?
   */
  private[manager] def scenarioNameLabelValue(processName: ProcessName) = {
    val name = processName.value

    sanitizeLabel(name, s"-${shortHash(name)}")
  }

  private[manager] def requirementForName(processName: ProcessName): Requirement = scenarioNameLabel is scenarioNameLabelValue(processName)

  /*
    Labels contain scenario name, scenario id and version.
   */
  private[manager] def labelsForScenario(processVersion: ProcessVersion, nussknackerInstanceName: Option[String]) = Map(
    scenarioNameLabel -> scenarioNameLabelValue(processVersion.processName),
    scenarioIdLabel -> processVersion.processId.value.toString,
    scenarioVersionLabel -> processVersion.versionId.value.toString
  ) ++ nussknackerInstanceName.map(nussknackerInstanceNameLabel -> _)

  /*
    Id of both is created with scenario id + sanitized name. This is to:
    - guarantee uniqueness - id is sufficient for that, sanitized name - not necessarily, as replacement/shortening may lead to duplicates
      (other way to mitigate this would be to generate some hash, but it's a bit more complex...)
    - ensure some level of readability - only id would be hard to match name to scenario
   */
  private[manager] def objectNameForScenario(processVersion: ProcessVersion, nussknackerInstanceName: Option[String], hashInput: Option[String]): String = {
    //we simulate (more or less) --append-hash kubectl behaviour...
    val hashToAppend = hashInput.map(input => "-" + shortHash(input)).getOrElse("")
    val plainScenarioName = s"scenario-${processVersion.processId.value}-${processVersion.processName.value}"
    val scenarioName = nussknackerInstanceName.map(in => s"$in-$plainScenarioName").getOrElse(plainScenarioName)
    sanitizeObjectName(scenarioName, hashToAppend)
  }

  private[manager] def parseVersionAnnotation(deployment: Deployment): Option[ProcessVersion] = {
    deployment.metadata.annotations.get(scenarioVersionAnnotation).flatMap(CirceUtil.decodeJson[ProcessVersion](_).toOption)
  }

}
