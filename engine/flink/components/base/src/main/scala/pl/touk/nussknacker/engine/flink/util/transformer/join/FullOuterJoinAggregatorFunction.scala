package pl.touk.nussknacker.engine.flink.util.transformer.join

import org.apache.flink.api.common.functions.RuntimeContext
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.functions.co.CoProcessFunction
import org.apache.flink.util.Collector
import pl.touk.nussknacker.engine.api._
import pl.touk.nussknacker.engine.api.runtimecontext.EngineRuntimeContext
import pl.touk.nussknacker.engine.api.typed.typing.TypingResult
import pl.touk.nussknacker.engine.flink.api.state.LatelyEvictableStateCoFunction
import pl.touk.nussknacker.engine.flink.util.keyed.StringKeyedValue
import pl.touk.nussknacker.engine.flink.util.orderedmap.FlinkRangeMap
import pl.touk.nussknacker.engine.flink.util.transformer.aggregate.{Aggregator, AggregatorFunctionMixin}
import pl.touk.nussknacker.engine.api.NodeId

import scala.language.higherKinds

class FullOuterJoinAggregatorFunction[MapT[_, _]](protected val aggregator: Aggregator, protected val timeWindowLengthMillis: Long,
                                                  override val nodeId: NodeId,
                                                  protected val aggregateElementType: TypingResult,
                                                  override protected val aggregateTypeInformation: TypeInformation[AnyRef],
                                                  val convertToEngineRuntimeContext: RuntimeContext => EngineRuntimeContext)
                                                 (implicit override val rangeMap: FlinkRangeMap[MapT])
  extends LatelyEvictableStateCoFunction[ValueWithContext[StringKeyedValue[AnyRef]], ValueWithContext[StringKeyedValue[AnyRef]], ValueWithContext[AnyRef], MapT[Long, AnyRef]]
    with AggregatorFunctionMixin[MapT] {

  type FlinkCtx = CoProcessFunction[ValueWithContext[StringKeyedValue[AnyRef]], ValueWithContext[StringKeyedValue[AnyRef]], ValueWithContext[AnyRef]]#Context

  def processElement(in: ValueWithContext[StringKeyedValue[AnyRef]], ctx: FlinkCtx, out: Collector[ValueWithContext[AnyRef]]) : AnyRef = {
    addElementToState(in, ctx.timestamp(), ctx.timerService(), out)
    val current: MapT[Long, aggregator.Aggregate] = readStateOrInitial()
    val res = computeFinalValue(current, ctx.timestamp())
    res
  }

  override def processElement1(in1: ValueWithContext[StringKeyedValue[AnyRef]], ctx: FlinkCtx, out: Collector[ValueWithContext[AnyRef]]): Unit = {
    val v = processElement(in1, ctx, out) match {
      case (_, x) => x
      case _ => assert(false)
    }
    out.collect(ValueWithContext(v.asInstanceOf[AnyRef], in1.context))
  }

  override def processElement2(in2: ValueWithContext[StringKeyedValue[AnyRef]], ctx: FlinkCtx, out: Collector[ValueWithContext[AnyRef]]): Unit = {
    val v = processElement(in2, ctx, out) match {
      case (x, _) => x.asInstanceOf[AnyRef]
      case _ => assert(false)
    }
    out.collect(ValueWithContext(v.asInstanceOf[AnyRef], in2.context))
  }

}