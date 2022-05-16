package pl.touk.nussknacker.engine.flink.util.transformer.join

import cats.data.Validated
import cats.data.Validated.Invalid
import pl.touk.nussknacker.engine.api.typed.typing
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, TypedClass}
import pl.touk.nussknacker.engine.flink.util.transformer.aggregate.Aggregator

class EitherAggregator(val aggLeft: Aggregator, val aggRight: Aggregator) extends Aggregator {
  override type Aggregate = (aggLeft.Aggregate, aggRight.Aggregate)
  override type Element = Either[aggLeft.Element, aggRight.Element]

  override def zero: Aggregate = (aggLeft.zero, aggRight.zero)

  override def addElement(element: Element, aggregate: Aggregate): Aggregate = element match {
      case Left(x) => (aggLeft.addElement(x, aggregate._1), aggregate._2)
      case Right(x) => (aggregate._1, aggRight.addElement(x, aggregate._2))
    }

  override def mergeAggregates(aggregate1: Aggregate, aggregate2: Aggregate): Aggregate =
    (aggLeft.mergeAggregates(aggregate1._1, aggregate2._1),
      aggRight.mergeAggregates(aggregate1._2, aggregate2._2))

  override def result(finalAggregate: Aggregate): AnyRef =
    (aggLeft.result(finalAggregate._1), aggRight.result(finalAggregate._2))

  override def computeOutputType(input: typing.TypingResult): Validated[String, typing.TypingResult] = input match {
    case TypedClass(x, leftInput :: rightInput :: Nil) => {
      val leftOutputType = aggLeft.computeOutputType(leftInput)
      val rightOutputType = aggRight.computeOutputType(rightInput)
      leftOutputType.product(rightOutputType).map{case (x, y) =>
        Typed.genericTypeClass[(_, _)](List(x, y))
      }
    }
    case TypedClass(_, y) => {
      Invalid(s"parameter list $y does not have 2 elements")
    }
    case _ => {
      Invalid(s"input has invalid type")
    }
  }

  override def computeStoredType(input: typing.TypingResult): Validated[String, typing.TypingResult] =
    computeOutputType(input)
}
