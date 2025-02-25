package pl.touk.nussknacker.engine.split

import cats.data.NonEmptyList
import pl.touk.nussknacker.engine.graph.EspProcess
import pl.touk.nussknacker.engine.graph.node.NodeData
import pl.touk.nussknacker.engine.splittedgraph.SplittedNodesCollector.collectNodes
import pl.touk.nussknacker.engine.splittedgraph.part._
import pl.touk.nussknacker.engine.splittedgraph.splittednode._

object NodesCollector {

  def collectNodesInScenario(process: EspProcess): List[SplittedNode[_ <: NodeData]] =
    NodesCollector.collectNodesInAllParts(ProcessSplitter.split(process).sources)

  def collectNodesInAllParts(parts: NonEmptyList[ProcessPart]): List[SplittedNode[_<:NodeData]]
    = parts.toList.flatMap(collectNodesInAllParts)

  def collectNodesInAllParts(part: ProcessPart): List[SplittedNode[_<:NodeData]] =
    part match {
      case source: SourcePart =>
        collectNodes(source.node) ::: source.nextParts.flatMap(collectNodesInAllParts)
      case sink: SinkPart =>
        collectNodes(sink.node)
      case custom:CustomNodePart =>
        collectNodes(custom.node) ::: custom.nextParts.flatMap(collectNodesInAllParts)
    }

}
