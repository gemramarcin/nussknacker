package pl.touk.nussknacker.engine.flink.util.transformer.join

import com.typesafe.scalalogging.LazyLogging
import org.apache.flink.api.common.functions.RuntimeContext
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.functions.co.CoProcessFunction
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.runtime.operators.windowing.TimestampedValue
import pl.touk.nussknacker.engine.api._
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError.CustomNodeError
import pl.touk.nussknacker.engine.api.context.transformation._
import pl.touk.nussknacker.engine.api.context.{OutputVar, ValidationContext}
import pl.touk.nussknacker.engine.api.definition._
import pl.touk.nussknacker.engine.api.runtimecontext.EngineRuntimeContext
import pl.touk.nussknacker.engine.api.typed.typing
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, TypingResult, Unknown}
import pl.touk.nussknacker.engine.flink.api.compat.ExplicitUidInOperatorsSupport
import pl.touk.nussknacker.engine.flink.api.process.{FlinkCustomJoinTransformation, FlinkCustomNodeContext}
import pl.touk.nussknacker.engine.flink.api.timestampwatermark.TimestampWatermarkHandler
import pl.touk.nussknacker.engine.flink.util.keyed.{StringKeyedValue, StringKeyedValueMapper}
import pl.touk.nussknacker.engine.flink.util.richflink._
import pl.touk.nussknacker.engine.flink.util.timestamp.TimestampAssignmentHelper
import pl.touk.nussknacker.engine.flink.util.transformer.aggregate.{AggregateHelper, Aggregator}
import pl.touk.nussknacker.engine.api.NodeId
import pl.touk.nussknacker.engine.flink.util.transformer.aggregate.aggregates.EitherAggregator

import java.time.Duration
import java.util.concurrent.TimeUnit
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

class FullOuterJoinTransformer(timestampAssigner: Option[TimestampWatermarkHandler[TimestampedValue[ValueWithContext[AnyRef]]]])
  extends CustomStreamTransformer with JoinGenericNodeTransformation[FlinkCustomJoinTransformation] with ExplicitUidInOperatorsSupport
    with WithExplicitTypesToExtract with LazyLogging {

  import pl.touk.nussknacker.engine.flink.util.transformer.join.FullOuterJoinTransformer._

  override def canHaveManyInputs: Boolean = true

  override type State = Nothing

  override def nodeDependencies: List[NodeDependency] = List(OutputVariableNameDependency)

  override def contextTransformation(contexts: Map[String, ValidationContext], dependencies: List[NodeDependencyValue])(implicit nodeId: NodeId): NodeTransformationDefinition = {
    case TransformationStep(Nil, _) => NextParameters(
      List(KeyParam, AggregatorParam, AggregateByParam, WindowLengthParam).map(_.parameter))

    case TransformationStep(
    (`KeyParamName`, DefinedLazyBranchParameter(_: Map[String, LazyParameter[CharSequence]]@unchecked)) ::
      (`AggregatorParamName`, DefinedEagerBranchParameter(aggregatorByBranchId: Map[String, Aggregator]@unchecked, _)) ::
      (`AggregateByParamName`, DefinedLazyBranchParameter(aggregateByByBranchId: Map[String, Any]@unchecked)) ::
      (`WindowLengthParamName`, _) ::
      Nil, _) =>
      val outName = OutputVariableNameDependency.extract(dependencies)
      val mainCtx = ValidationContext()
      val validAggregateOutputType = aggregatorByBranchId
        .map{case (id, agg) => agg.computeOutputType(aggregateByByBranchId(id).returnType)}
        .map(_.leftMap(CustomNodeError(_, Some(AggregatorParamName))))

      FinalResults.forValidation(mainCtx, validAggregateOutputType.toList.flatMap(_.swap.toList))(
        _.withVariable(OutputVar.customNode(outName), Unknown))
  }

  override def implementation(params: Map[String, Any], dependencies: List[NodeDependencyValue], finalState: Option[State]): FlinkCustomJoinTransformation = {
    val keyByBranchId: Map[String, LazyParameter[CharSequence]] = KeyParam.extractValue(params)
    val aggregatorByBranchId: Map[String, Aggregator] = AggregatorParam.extractValue(params)
    val aggregateByByBranchId: Map[String, LazyParameter[AnyRef]] = AggregateByParam.extractValue(params)
    val window: Duration = WindowLengthParam.extractValue(params)

    val idLeft :: idRight :: _ = keyByBranchId.toList.map(_._1)

    val aggregator = new EitherAggregator(aggregatorByBranchId(idLeft), aggregatorByBranchId(idRight))

    (inputs: Map[String, DataStream[Context]], context: FlinkCustomNodeContext) => {
      val keyedLeftStream = inputs(idLeft)
        .flatMap(new StringKeyedValueMapper(context, keyByBranchId(idLeft), aggregateByByBranchId(idLeft)))
        .map(_.map(_.mapValue(x => Left(x).asInstanceOf[AnyRef])))

      val keyedRightStream = inputs(idRight)
        .flatMap(new StringKeyedValueMapper(context, keyByBranchId(idRight), aggregateByByBranchId(idRight)))
        .map(_.map(_.mapValue(x => Right(x).asInstanceOf[AnyRef])))

      val mainType = aggregateByByBranchId(idLeft).returnType
      val sideType = aggregateByByBranchId(idRight).returnType
      val inputType = Typed.genericTypeClass[Either[_, _]](List(mainType, sideType))

      val storedTypeInfo = context.typeInformationDetection.forType(aggregator.computeStoredTypeUnsafe(inputType))
      val aggregatorFunction = prepareAggregatorFunction(aggregator, FiniteDuration(window.toMillis, TimeUnit.MILLISECONDS), inputType, storedTypeInfo, context.convertToEngineRuntimeContext)(NodeId(context.nodeId))
      val statefulStreamWithUid = keyedLeftStream
        .connect(keyedRightStream)
        .keyBy(v => v.value.key, v => v.value.key)
        .process(aggregatorFunction)
        .setUidWithName(context, ExplicitUidInOperatorsSupport.defaultExplicitUidInStatefulOperators)

      timestampAssigner
        .map(new TimestampAssignmentHelper(_).assignWatermarks(statefulStreamWithUid))
        .getOrElse(statefulStreamWithUid)
    }
  }

  protected def prepareAggregatorFunction(aggregator: Aggregator, stateTimeout: FiniteDuration, aggregateElementType: TypingResult,
                                          storedTypeInfo: TypeInformation[AnyRef], convertToEngineRuntimeContext: RuntimeContext => EngineRuntimeContext)
                                         (implicit nodeId: NodeId):
  CoProcessFunction[ValueWithContext[StringKeyedValue[AnyRef]], ValueWithContext[StringKeyedValue[AnyRef]], ValueWithContext[AnyRef]] =
    new FullOuterJoinAggregatorFunction[SortedMap](aggregator, stateTimeout.toMillis, nodeId, aggregateElementType, storedTypeInfo, convertToEngineRuntimeContext)

  override def typesToExtract: List[typing.TypedClass] = List(Typed.typedClass[BranchType])
}

case object FullOuterJoinTransformer extends FullOuterJoinTransformer(None) {

  val KeyParamName = "key"
  val KeyParam: ParameterWithExtractor[Map[String, LazyParameter[CharSequence]]] = ParameterWithExtractor.branchLazyMandatory[CharSequence](KeyParamName)

  val AggregatorParamName = "aggregator"
  val AggregatorParam: ParameterWithExtractor[Map[String, Aggregator]] = ParameterWithExtractor
    .branchMandatory[Aggregator](AggregatorParamName, _.copy(editor = Some(AggregateHelper.DUAL_EDITOR),
      additionalVariables = Map("AGG" -> AdditionalVariableWithFixedValue(new AggregateHelper))))

  val AggregateByParamName = "aggregateBy"
  val AggregateByParam: ParameterWithExtractor[Map[String, LazyParameter[AnyRef]]] = ParameterWithExtractor.branchLazyMandatory[AnyRef](AggregateByParamName)

  val WindowLengthParamName = "windowLength"
  val WindowLengthParam: ParameterWithExtractor[Duration] = ParameterWithExtractor.mandatory[Duration](WindowLengthParamName)

}
