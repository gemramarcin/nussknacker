package pl.touk.nussknacker.engine.graph

import io.circe.generic.extras.ConfiguredJsonCodec
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.api.CirceUtil._

//unstable, may change in the future...
@ConfiguredJsonCodec sealed abstract class EdgeType {
  def mustBeUnique: Boolean = true
}
object EdgeType {
  sealed trait FilterEdge extends EdgeType
  case object FilterTrue extends FilterEdge
  case object FilterFalse extends FilterEdge
  sealed trait SwitchEdge extends EdgeType
  case class NextSwitch(condition: Expression) extends SwitchEdge {
    override def mustBeUnique: Boolean = false
  }
  case object SwitchDefault extends SwitchEdge
  case class SubprocessOutput(name: String) extends EdgeType
}