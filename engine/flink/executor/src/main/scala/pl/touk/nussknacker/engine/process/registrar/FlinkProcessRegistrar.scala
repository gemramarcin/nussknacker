package pl.touk.nussknacker.engine.process.registrar

import com.typesafe.scalalogging.LazyLogging
import org.apache.flink.api.common.functions.RuntimeContext
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.environment.RemoteStreamEnvironment
import org.apache.flink.streaming.api.scala.{DataStream, OutputTag, StreamExecutionEnvironment}
import org.apache.flink.streaming.api.windowing.time.Time
import pl.touk.nussknacker.engine.InterpretationResult
import pl.touk.nussknacker.engine.api._
import pl.touk.nussknacker.engine.api.component.NodeComponentInfo
import pl.touk.nussknacker.engine.api.context.{JoinContextTransformation, ValidationContext}
import pl.touk.nussknacker.engine.api.typed.typing.Unknown
import pl.touk.nussknacker.engine.compiledgraph.part._
import pl.touk.nussknacker.engine.component.NodeComponentInfoExtractor.fromNodeData
import pl.touk.nussknacker.engine.deployment.DeploymentData
import pl.touk.nussknacker.engine.flink.api.NkGlobalParameters
import pl.touk.nussknacker.engine.flink.api.compat.ExplicitUidInOperatorsSupport
import pl.touk.nussknacker.engine.flink.api.process._
import pl.touk.nussknacker.engine.flink.api.typeinformation.TypeInformationDetection
import pl.touk.nussknacker.engine.graph.EspProcess
import pl.touk.nussknacker.engine.graph.node.{BranchEndDefinition, NodeData}
import pl.touk.nussknacker.engine.process.compiler.{FlinkEngineRuntimeContextImpl, FlinkProcessCompiler, FlinkProcessCompilerData, UsedNodes}
import pl.touk.nussknacker.engine.process.typeinformation.TypeInformationDetectionUtils
import pl.touk.nussknacker.engine.process.util.StateConfiguration.RocksDBStateBackendConfig
import pl.touk.nussknacker.engine.process.{CheckpointConfig, ExecutionConfigPreparer, FlinkCompatibilityProvider}
import pl.touk.nussknacker.engine.resultcollector.{ProductionServiceInvocationCollector, ResultCollector}
import pl.touk.nussknacker.engine.splittedgraph.end.BranchEnd
import pl.touk.nussknacker.engine.splittedgraph.{SplittedNodesCollector, splittednode}
import pl.touk.nussknacker.engine.testmode.{SinkInvocationCollector, TestRunId, TestServiceInvocationCollector}
import pl.touk.nussknacker.engine.util.loader.ScalaServiceLoader
import pl.touk.nussknacker.engine.util.{MetaDataExtractor, ThreadUtils}
import shapeless.syntax.typeable.typeableOps

import java.util.concurrent.TimeUnit
import scala.language.implicitConversions

/*
  This is main class where we translate Nussknacker model to Flink job.

  NOTE: We should try to use *ONLY* core Flink API here, to avoid version compatibility problems.
  Various NK-dependent Flink hacks should be, if possible, placed in StreamExecutionEnvPreparer.
 */
class FlinkProcessRegistrar(compileProcess: (EspProcess, ProcessVersion, DeploymentData, ResultCollector) => (UsedNodes, ClassLoader) => FlinkProcessCompilerData,
                            streamExecutionEnvPreparer: StreamExecutionEnvPreparer) extends LazyLogging {

  import FlinkProcessRegistrar._

  implicit def millisToTime(duration: Long): Time = Time.of(duration, TimeUnit.MILLISECONDS)

  def register(env: StreamExecutionEnvironment, process: EspProcess, processVersion: ProcessVersion, deploymentData: DeploymentData, testRunId: Option[TestRunId] = None): Unit = {
    usingRightClassloader(env) { userClassLoader =>
      //TODO: move creation outside Registrar, together with refactoring SinkInvocationCollector...
      val collector = testRunId.map(new TestServiceInvocationCollector(_)).getOrElse(ProductionServiceInvocationCollector)

      val processCompilation = compileProcess(process, processVersion, deploymentData, collector)
      val processWithDeps = processCompilation(UsedNodes.empty, userClassLoader)

      streamExecutionEnvPreparer.preRegistration(env, processWithDeps, deploymentData)
      val typeInformationDetection = TypeInformationDetectionUtils.forExecutionConfig(env.getConfig, userClassLoader)

      val partCompilation = FlinkProcessRegistrar.partCompilation[FlinkProcessCompilerData](processCompilation) _
      register(env, partCompilation, processWithDeps, testRunId, typeInformationDetection)
      streamExecutionEnvPreparer.postRegistration(env, processWithDeps, deploymentData)
    }
  }

  protected def isRemoteEnv(env: StreamExecutionEnvironment): Boolean = env.getJavaEnv.isInstanceOf[RemoteStreamEnvironment]

  //In remote env we assume FlinkProcessRegistrar is loaded via userClassloader
  protected def usingRightClassloader(env: StreamExecutionEnvironment)(action: ClassLoader => Unit): Unit = {
    if (!isRemoteEnv(env)) {
      val flinkLoaderSimulation = streamExecutionEnvPreparer.flinkClassLoaderSimulation
      ThreadUtils.withThisAsContextClassLoader[Unit](flinkLoaderSimulation) {
        action(flinkLoaderSimulation)
      }
    } else {
      val userLoader = getClass.getClassLoader
      action(userLoader)
    }
  }

  protected def createInterpreter(compiledProcessWithDepsProvider: ClassLoader => FlinkProcessCompilerData): RuntimeContext => FlinkCompilerLazyInterpreterCreator =
    (runtimeContext: RuntimeContext) =>
      new FlinkCompilerLazyInterpreterCreator(runtimeContext, compiledProcessWithDepsProvider(runtimeContext.getUserCodeClassLoader))

  private def register(env: StreamExecutionEnvironment,
                       compiledProcessWithDeps: Option[ProcessPart] => (ClassLoader => FlinkProcessCompilerData),
                       processWithDeps: FlinkProcessCompilerData,
                       testRunId: Option[TestRunId], typeInformationDetection: TypeInformationDetection): Unit = {

    val metaData = processWithDeps.metaData
    val globalParameters = NkGlobalParameters.readFromContext(env.getConfig)

    def nodeContext(nodeComponentId: NodeComponentInfo, validationContext: Either[ValidationContext, Map[String, ValidationContext]]): FlinkCustomNodeContext = {
      val exceptionHandlerPreparer = (runtimeContext: RuntimeContext) =>
        compiledProcessWithDeps(None)(runtimeContext.getUserCodeClassLoader).prepareExceptionHandler(runtimeContext)
      val jobData = processWithDeps.jobData
      FlinkCustomNodeContext(jobData, nodeComponentId.nodeId, processWithDeps.processTimeout,
        convertToEngineRuntimeContext = FlinkEngineRuntimeContextImpl(jobData, _),
        lazyParameterHelper = new FlinkLazyParameterFunctionHelper(nodeComponentId, exceptionHandlerPreparer, createInterpreter(compiledProcessWithDeps(None))),
        signalSenderProvider = processWithDeps.signalSenders,
        exceptionHandlerPreparer = exceptionHandlerPreparer,
        globalParameters = globalParameters,
        validationContext,
        typeInformationDetection,
        processWithDeps.componentUseCase)
    }

    {
      //it is *very* important that source are in correct order here - see ProcessCompiler.compileSources comments
      processWithDeps.compileProcessOrFail().sources.toList.foldLeft(Map.empty[BranchEndDefinition, BranchEndData]) {
        case (branchEnds, next: SourcePart) => branchEnds ++ registerSourcePart(next)
        case (branchEnds, joinPart: CustomNodePart) => branchEnds ++ registerJoinPart(joinPart, branchEnds)
      }
    }

    def registerSourcePart(part: SourcePart): Map[BranchEndDefinition, BranchEndData] = {
      //TODO: get rid of cast (but how??)
      val source = part.obj.asInstanceOf[FlinkSource]

      val contextTypeInformation = typeInformationDetection.forContext(part.validationContext)

      val start = source
        .sourceStream(env, nodeContext(nodeComponentInfoFrom(part), Left(ValidationContext.empty)))
        .process(new SourceMetricsFunction(part.id))(contextTypeInformation)

      val asyncAssigned = registerInterpretationPart(start, part, InterpretationName)

      registerNextParts(asyncAssigned, part)
    }

    //thanks to correct sorting, we know that branchEnds contain all edges to joinPart
    def registerJoinPart(joinPart: CustomNodePart,
                         branchEnds: Map[BranchEndDefinition, BranchEndData]): Map[BranchEndDefinition, BranchEndData] = {
      val inputs: Map[String, (DataStream[Context], ValidationContext)] = branchEnds.collect {
        case (BranchEndDefinition(id, joinId), BranchEndData(validationContext, stream)) if joinPart.id == joinId =>
          id -> (stream.map(_.finalContext)(typeInformationDetection.forContext(validationContext)), validationContext)
      }

      val transformer = joinPart.transformer match {
        case joinTransformer: FlinkCustomJoinTransformation => joinTransformer
        case JoinContextTransformation(_, impl: FlinkCustomJoinTransformation) => impl
        case other =>
          throw new IllegalArgumentException(s"Unknown join node transformer: $other")
      }

      val outputVar = joinPart.node.data.outputVar.get
      val newContextFun = (ir: ValueWithContext[_]) => ir.context.withVariable(outputVar, ir.value)

      val newStart = transformer
        .transform(inputs.mapValues(_._1), nodeContext(nodeComponentInfoFrom(joinPart), Right(inputs.mapValues(_._2))))
        .map(newContextFun)(typeInformationDetection.forContext(joinPart.validationContext))

      val afterSplit = registerInterpretationPart(newStart, joinPart, BranchInterpretationName)
      registerNextParts(afterSplit, joinPart)
    }

    //the method returns all possible branch ends in part, together with DataStream leading to them
    def registerNextParts(start: DataStream[Unit], part: PotentiallyStartPart): Map[BranchEndDefinition, BranchEndData] = {
      val branchesForParts = part.nextParts.map { part =>
        val typeInformationForTi = InterpretationResultTypeInformation.create(typeInformationDetection, part.contextBefore, None)
        val typeInformationForVC = typeInformationDetection.forContext(part.contextBefore)

        registerSubsequentPart(start.getSideOutput(OutputTag[InterpretationResult](part.id)(typeInformationForTi))(typeInformationForTi)
          .map(_.finalContext)(typeInformationForVC), part)
      }.foldLeft(Map[BranchEndDefinition, BranchEndData]()) {
        _ ++ _
      }
      val branchForEnds = part.ends.collect {
        case TypedEnd(be: BranchEnd, validationContext) =>
          val ti = InterpretationResultTypeInformation.create(typeInformationDetection, validationContext, None)
          be.definition -> BranchEndData(validationContext, start.getSideOutput(OutputTag[InterpretationResult](be.nodeId)(ti))(ti))
      }.toMap
      branchesForParts ++ branchForEnds
    }

    def registerSubsequentPart[T](start: DataStream[Context],
                                  processPart: SubsequentPart): Map[BranchEndDefinition, BranchEndData] =
      processPart match {
        case part@SinkPart(sink: FlinkSink, _, contextBefore, _) =>
          registerSinkPark(start, part, sink, contextBefore)
        case part: SinkPart =>
          throw new IllegalArgumentException(s"Scenario can only use flink sinks, instead given: ${part.obj}")
        case part: CustomNodePart =>
          registerCustomNodePart(start, part)
      }

    def registerSinkPark[T](start: DataStream[Context],
                            part: SinkPart,
                            sink: FlinkSink,
                            contextBefore: ValidationContext): Map[BranchEndDefinition, BranchEndData] = {
      val typeInformationForIR = InterpretationResultTypeInformation.create(typeInformationDetection, contextBefore, Some(Unknown))
      val typeInformationForCtx = typeInformationDetection.forContext(contextBefore)
      // TODO: for sinks there are no further nodes to interpret but the function is registered to invoke listeners (e.g. to measure end metrics).
      val afterInterpretation = registerInterpretationPart(start, part, SinkInterpretationName)
        .getSideOutput(OutputTag[InterpretationResult](FlinkProcessRegistrar.EndId)(typeInformationForIR))(typeInformationForIR)
        .map(_.finalContext)(typeInformationForCtx)
      val customNodeContext = nodeContext(nodeComponentInfoFrom(part), Left(contextBefore))
      val withValuePrepared = sink.prepareValue(afterInterpretation, customNodeContext)
      //TODO: maybe this logic should be moved to compiler instead?
      val withSinkAdded = testRunId match {
        case None =>
          sink.registerSink(withValuePrepared, nodeContext(nodeComponentInfoFrom(part), Left(contextBefore)))
        case Some(runId) =>
          val typ = part.node.data.ref.typ
          val collectingSink = SinkInvocationCollector(runId, part.id, typ)
          withValuePrepared
            .map((ds: ValueWithContext[sink.Value]) => ds.map(sink.prepareTestValue))(TypeInformation.of(classOf[ValueWithContext[AnyRef]]))
            //FIXME: ...
            .addSink(new CollectingSinkFunction[AnyRef](compiledProcessWithDeps(None), collectingSink, part.id))
      }

      withSinkAdded.name(operatorName(metaData, part.node, "sink"))
      Map()
    }

    def registerCustomNodePart[T](start: DataStream[Context],
                                  part: CustomNodePart): Map[BranchEndDefinition, BranchEndData] = {
      val transformer = part.transformer match {
        case t: FlinkCustomStreamTransformation => t
        case other =>
          throw new IllegalArgumentException(s"Unknown custom node transformer: $other")
      }

      val customNodeContext = nodeContext(nodeComponentInfoFrom(part), Left(part.contextBefore))
      val newContextFun: ValueWithContext[_] => Context = part.node.data.outputVar match {
        case Some(name) => vwc => vwc.context.withVariable(name, vwc.value)
        case None => _.context
      }
      val transformed = transformer
        .transform(start, customNodeContext)
        .map(newContextFun)(typeInformationDetection.forContext(part.validationContext))
      // TODO: for ending custom nodes there are no further nodes to interpret but the function is registered to invoke listeners (e.g. to measure end metrics).
      val afterInterpretation = registerInterpretationPart(transformed, part, CustomNodeInterpretationName)
      registerNextParts(afterInterpretation, part)
    }

    def registerInterpretationPart(stream: DataStream[Context],
                                   part: ProcessPart,
                                   name: String): DataStream[Unit] = {
      val node = part.node
      val validationContext = part.validationContext
      val outputContexts = part.ends.map(pe => pe.end.nodeId -> pe.validationContext).toMap ++ (part match {
        case e: PotentiallyStartPart => e.nextParts.map(np => np.id -> np.validationContext).toMap
        case _ => Map.empty
      })
      val asyncExecutionContextPreparer = processWithDeps.asyncExecutionContextPreparer
      val metaData = processWithDeps.metaData
      val streamMetaData = MetaDataExtractor.extractTypeSpecificDataOrFail[StreamMetaData](metaData)

      val configParameters = globalParameters.flatMap(_.configParameters)
      val useIOMonad = configParameters.flatMap(_.useIOMonadInInterpreter).getOrElse(true)
      val shouldUseAsyncInterpretation = AsyncInterpretationDeterminer(configParameters, asyncExecutionContextPreparer).determine(node, streamMetaData)

      (if (shouldUseAsyncInterpretation) {
        val asyncFunction = new AsyncInterpretationFunction(compiledProcessWithDeps(Some(part)), node, validationContext, asyncExecutionContextPreparer, useIOMonad)
        ExplicitUidInOperatorsSupport.setUidIfNeed(ExplicitUidInOperatorsSupport.defaultExplicitUidInStatefulOperators(globalParameters), node.id + "-$async")(
          new DataStream(org.apache.flink.streaming.api.datastream.AsyncDataStream.orderedWait(stream.javaStream, asyncFunction,
            processWithDeps.processTimeout.toMillis, TimeUnit.MILLISECONDS, asyncExecutionContextPreparer.bufferSize)))
      } else {
        val ti = InterpretationResultTypeInformation.create(typeInformationDetection, outputContexts)
        stream.flatMap(new SyncInterpretationFunction(compiledProcessWithDeps(Some(part)), node, validationContext, useIOMonad))(ti)
      }).name(interpretationOperatorName(metaData, node, name, shouldUseAsyncInterpretation))
        .process(new SplitFunction(outputContexts, typeInformationDetection))(org.apache.flink.streaming.api.scala.createTypeInformation[Unit])
    }

  }

  private def nodeComponentInfoFrom(processPart: ProcessPart): NodeComponentInfo = {
    fromNodeData(processPart.node.data)
  }
}

object FlinkProcessRegistrar {

  private[registrar] final val EndId = "$end"
  final val InterpretationName = "interpretation"
  final val CustomNodeInterpretationName = "customNodeInterpretation"
  final val SinkInterpretationName = "sinkInterpretation"
  final val BranchInterpretationName = "branchInterpretation"

  import net.ceedubs.ficus.Ficus._
  import net.ceedubs.ficus.readers.ArbitraryTypeReader._

  private def partCompilation[T](original: (UsedNodes, ClassLoader) => T)(part: Option[ProcessPart]): ClassLoader => T = {
    val (nodesToUse, endingParts) = part.map { part =>
      (SplittedNodesCollector.collectNodes(part.node).map(_.data),
        part.cast[PotentiallyStartPart].toList.flatMap(_.nextParts).map(_.id))
    }.getOrElse((Set.empty, Nil))
    original(UsedNodes(nodesToUse, endingParts), _)
  }



  def apply(compiler: FlinkProcessCompiler, prepareExecutionConfig: ExecutionConfigPreparer): FlinkProcessRegistrar = {
    val config = compiler.processConfig

    val checkpointConfig = config.getAs[CheckpointConfig](path = "checkpointConfig")
    val rocksDBStateBackendConfig = config.getAs[RocksDBStateBackendConfig]("rocksDB").filter(_ => compiler.diskStateBackendSupport)

    val defaultStreamExecutionEnvPreparer =
      ScalaServiceLoader.load[FlinkCompatibilityProvider](getClass.getClassLoader)
        .headOption.map(_.createExecutionEnvPreparer(config, prepareExecutionConfig, compiler.diskStateBackendSupport))
        .getOrElse(new DefaultStreamExecutionEnvPreparer(checkpointConfig, rocksDBStateBackendConfig, prepareExecutionConfig))
    new FlinkProcessRegistrar(compiler.compileProcess, defaultStreamExecutionEnvPreparer)
  }

  private[registrar] def operatorName(metaData: MetaData,
                                      splittedNode: splittednode.SplittedNode[NodeData],
                                      operation: String) = {
    s"${metaData.id}-${splittedNode.id}-$operation"
  }

  private[registrar] def interpretationOperatorName(metaData: MetaData,
                                                    splittedNode: splittednode.SplittedNode[NodeData],
                                                    interpretationName: String,
                                                    shouldUseAsyncInterpretation: Boolean): String = {
    interpretationOperatorName(metaData.id, splittedNode.id, interpretationName, shouldUseAsyncInterpretation)
  }

  private[registrar] def interpretationOperatorName(scenarioId: String,
                                                    nodeId: String,
                                                    interpretationName: String,
                                                    shouldUseAsyncInterpretation: Boolean) = {
    s"$scenarioId-$nodeId-$interpretationName${if (shouldUseAsyncInterpretation) "Async" else "Sync"}"
  }

}

case class BranchEndData(validationContext: ValidationContext, stream: DataStream[InterpretationResult])


