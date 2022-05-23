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
import pl.touk.nussknacker.engine.util.Implicits.RichScalaMap

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
      List(BranchTypeParam, KeyParam, AggregatorParam, WindowLengthParam).map(_.parameter))
    case TransformationStep(
    (`BranchTypeParamName`, DefinedEagerBranchParameter(branchTypeByBranchId: Map[String, BranchType]@unchecked, _)) ::
      (`KeyParamName`, _) :: (`AggregatorParamName`, _) :: (`WindowLengthParamName`, _) :: Nil, _) =>
      val error = if (branchTypeByBranchId.values.toList.sorted != BranchType.values().toList)
        List(CustomNodeError(s"Has to be exactly one MAIN and JOINED branch, got: ${branchTypeByBranchId.values.mkString(", ")}", Some(BranchTypeParamName)))
      else
        Nil
      val joinedVariables = joinedId(branchTypeByBranchId).map(contexts).getOrElse(ValidationContext())
        .localVariables.mapValuesNow(AdditionalVariableProvidedInRuntime(_))
      val res = NextParameters(List(AggregateByParam.parameter), error)
      res

    case TransformationStep(
    (`BranchTypeParamName`, DefinedEagerBranchParameter(branchTypeByBranchId: Map[String, BranchType]@unchecked, _)) ::
      (`KeyParamName`, _) :: (`AggregatorParamName`, DefinedEagerParameter(aggregator: Aggregator, _)) :: (`WindowLengthParamName`, _) ::
      (`AggregateByParamName`, aggregateBy) :: Nil, _) =>
      val outName = OutputVariableNameDependency.extract(dependencies)
      val mainCtx = mainId(branchTypeByBranchId).map(contexts).getOrElse(ValidationContext())
      val validAggregateOutputType = aggregator.computeOutputType(aggregateBy.returnType).leftMap(CustomNodeError(_, Some(AggregatorParamName)))
      FinalResults.forValidation(mainCtx, validAggregateOutputType.swap.toList)(
        _.withVariable(OutputVar.customNode(outName), validAggregateOutputType.getOrElse(Unknown)))
  }

  override def implementation(params: Map[String, Any], dependencies: List[NodeDependencyValue], finalState: Option[State]): FlinkCustomJoinTransformation = {
    val branchTypeByBranchId: Map[String, BranchType] = BranchTypeParam.extractValue(params)
    val keyByBranchId: Map[String, LazyParameter[CharSequence]] = KeyParam.extractValue(params)
    val aggregatorBase: Aggregator = AggregatorParam.extractValue(params)
    val window: Duration = WindowLengthParam.extractValue(params)
    val aggregateBy: Map[String, LazyParameter[AnyRef]] = params(AggregateByParamName).asInstanceOf[Map[String, LazyParameter[AnyRef]]]

    val aggregator = new EitherAggregator(aggregatorBase, aggregatorBase)

    (inputs: Map[String, DataStream[Context]], context: FlinkCustomNodeContext) => {
      val keyedMainBranchStream = inputs(mainId(branchTypeByBranchId).get)
        .flatMap(new StringKeyedValueMapper(context, keyByBranchId(mainId(branchTypeByBranchId).get), aggregateBy(mainId(branchTypeByBranchId).get)))
        .map(_.map(_.mapValue(x => Left(x).asInstanceOf[AnyRef])))

      val keyedJoinedStream = inputs(joinedId(branchTypeByBranchId).get)
        .flatMap(new StringKeyedValueMapper(context, keyByBranchId(joinedId(branchTypeByBranchId).get), aggregateBy(joinedId(branchTypeByBranchId).get)))
        .map(_.map(_.mapValue(x => Right(x).asInstanceOf[AnyRef])))

      val mainType = aggregateBy(mainId(branchTypeByBranchId).get).returnType
      val sideType = aggregateBy(joinedId(branchTypeByBranchId).get).returnType
      val inputType = Typed.genericTypeClass[Either[_, _]](List(mainType, sideType))

      val storedTypeInfo = context.typeInformationDetection.forType(aggregator.computeStoredTypeUnsafe(inputType))
      val aggregatorFunction = prepareAggregatorFunction(aggregator, FiniteDuration(window.toMillis, TimeUnit.MILLISECONDS), inputType, storedTypeInfo, context.convertToEngineRuntimeContext)(NodeId(context.nodeId))
      val statefulStreamWithUid = keyedMainBranchStream
        .connect(keyedJoinedStream)
        .keyBy(v => v.value.key, v => v.value.key)
        .process(aggregatorFunction)
        .setUidWithName(context, ExplicitUidInOperatorsSupport.defaultExplicitUidInStatefulOperators)

      timestampAssigner
        .map(new TimestampAssignmentHelper(_).assignWatermarks(statefulStreamWithUid))
        .getOrElse(statefulStreamWithUid)
    }
  }

  private def mainId(branchTypeByBranchId: Map[String, BranchType]) = {
    branchTypeByBranchId.find(_._2 == BranchType.MAIN).map(_._1)
  }

  private def joinedId(branchTypeByBranchId: Map[String, BranchType]) = {
    branchTypeByBranchId.find(_._2 == BranchType.JOINED).map(_._1)
  }

  protected def prepareAggregatorFunction(aggregator: Aggregator, stateTimeout: FiniteDuration, aggregateElementType: TypingResult,
                                          storedTypeInfo: TypeInformation[AnyRef], convertToEngineRuntimeContext: RuntimeContext => EngineRuntimeContext)
                                         (implicit nodeId: NodeId):
  CoProcessFunction[ValueWithContext[StringKeyedValue[AnyRef]], ValueWithContext[StringKeyedValue[AnyRef]], ValueWithContext[AnyRef]] =
    new FullOuterJoinAggregatorFunction[SortedMap](aggregator, stateTimeout.toMillis, nodeId, aggregateElementType, storedTypeInfo, convertToEngineRuntimeContext)

  override def typesToExtract: List[typing.TypedClass] = List(Typed.typedClass[BranchType])
}

case object FullOuterJoinTransformer extends FullOuterJoinTransformer(None) {

  val BranchTypeParamName = "branchType"
  val BranchTypeParam: ParameterWithExtractor[Map[String, BranchType]] = ParameterWithExtractor.branchMandatory[BranchType](BranchTypeParamName)

  val KeyParamName = "key"
  val KeyParam: ParameterWithExtractor[Map[String, LazyParameter[CharSequence]]] = ParameterWithExtractor.branchLazyMandatory[CharSequence](KeyParamName)

  val AggregatorParamName = "aggregator"
  val AggregatorParam: ParameterWithExtractor[Aggregator] = ParameterWithExtractor
    .mandatory[Aggregator](AggregatorParamName, _.copy(editor = Some(AggregateHelper.DUAL_EDITOR),
      additionalVariables = Map("AGG" -> AdditionalVariableWithFixedValue(new AggregateHelper))))

  val WindowLengthParamName = "windowLength"
  val WindowLengthParam: ParameterWithExtractor[Duration] = ParameterWithExtractor.mandatory[Duration](WindowLengthParamName)

  val AggregateByParamName = "aggregateBy"
  val AggregateByParam: ParameterWithExtractor[Map[String, LazyParameter[AnyRef]]] = ParameterWithExtractor.branchLazyMandatory[AnyRef](AggregateByParamName)

}
