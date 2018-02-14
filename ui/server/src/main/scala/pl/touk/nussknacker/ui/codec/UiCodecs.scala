package pl.touk.nussknacker.ui.codec

import argonaut._
import argonaut.derive.{DerivedInstances, JsonSumCodec, JsonSumCodecFor, SingletonInstances}
import com.typesafe.scalalogging.LazyLogging
import pl.touk.nussknacker.engine.api
import pl.touk.nussknacker.engine.api.{Displayable, TypeSpecificData, UserDefinedProcessAdditionalFields}
import pl.touk.nussknacker.engine.api.deployment.test.{ExpressionInvocationResult, MockedResult, TestResults}
import pl.touk.nussknacker.engine.api.exception.EspExceptionInfo
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.definition.TestingCapabilities
import pl.touk.nussknacker.engine.graph.node
import pl.touk.nussknacker.engine.marshall.ProcessMarshaller
import pl.touk.nussknacker.ui.api.{DisplayableUser, GrafanaSettings, ProcessObjects, ResultsWithCounts}
import pl.touk.nussknacker.ui.db.entity.ProcessEntity.{ProcessType, ProcessingType}
import pl.touk.nussknacker.ui.process.displayedgraph.displayablenode.{EdgeType, NodeAdditionalFields, ProcessAdditionalFields}
import pl.touk.nussknacker.ui.process.displayedgraph._
import pl.touk.nussknacker.ui.process.repository.ProcessActivityRepository.{Comment, ProcessActivity}
import pl.touk.nussknacker.ui.process.repository.ProcessRepository.{BaseProcessDetails, ProcessDetails, ProcessHistoryEntry}
import pl.touk.nussknacker.ui.processreport.NodeCount
import pl.touk.nussknacker.ui.validation.ValidationResults.{NodeValidationErrorType, ValidationResult}
import ArgonautShapeless._
import pl.touk.nussknacker.engine.definition.TypeInfos.ClazzDefinition
import pl.touk.nussknacker.engine.util.json.{BestEffortJsonEncoder, Codecs}

object UiCodecs extends UiCodecs

trait UiCodecs extends Codecs with Argonauts with SingletonInstances with DerivedInstances {

  private implicit def typeFieldJsonSumCodecFor[S]: JsonSumCodecFor[S] =
    JsonSumCodecFor(JsonSumCodec.typeField)

  //not sure why it works, another argonaut issue...
  implicit def typeCodec : CodecJson[TypeSpecificData] = new ProcessMarshaller().typeSpecificEncoder

  //argonaut does not like covariation so wee need to cast
  implicit def nodeAdditionalFieldsOptCodec: CodecJson[Option[node.UserDefinedAdditionalNodeFields]] = {
    CodecJson.derived[Option[NodeAdditionalFields]]
      .asInstanceOf[CodecJson[Option[node.UserDefinedAdditionalNodeFields]]]
  }

  implicit def processAdditionalFieldsOptCodec: CodecJson[Option[UserDefinedProcessAdditionalFields]] = {
    CodecJson.derived[Option[ProcessAdditionalFields]]
      .asInstanceOf[CodecJson[Option[UserDefinedProcessAdditionalFields]]]
  }

  implicit def testingCapabilitiesCodec: CodecJson[TestingCapabilities] = CodecJson.derive[TestingCapabilities]

  implicit def propertiesCodec: CodecJson[ProcessProperties] = CodecJson.derive[ProcessProperties]

  implicit def nodeErrorsCodec = Codecs.enumCodec(NodeValidationErrorType)

  implicit def validationResultEncode = EncodeJson.of[ValidationResult]

  //fixme how to do this automatically?
  implicit def edgeTypeEncode: EncodeJson[EdgeType] = EncodeJson[EdgeType] {
    case EdgeType.FilterFalse => jObjectFields("type" -> jString("FilterFalse"))
    case EdgeType.FilterTrue => jObjectFields("type" -> jString("FilterTrue"))
    case EdgeType.SwitchDefault => jObjectFields("type" -> jString("SwitchDefault"))
    case ns: EdgeType.NextSwitch => jObjectFields("type" -> jString("NextSwitch"), "condition" -> ns.condition.asJson)
    case EdgeType.SubprocessOutput(name) => jObjectFields("type" -> jString("SubprocessOutput"), "name" -> name.asJson)
  }

  implicit def edgeTypeDecode: DecodeJson[EdgeType] = DecodeJson[EdgeType] { c =>
    for {
      edgeType <- (c --\ "type").as[String]
      edgeTypeObj <- {
        if (edgeType == "FilterFalse") DecodeResult.ok(EdgeType.FilterFalse)
        else if (edgeType == "FilterTrue") DecodeResult.ok(EdgeType.FilterTrue)
        else if (edgeType == "SwitchDefault") DecodeResult.ok(EdgeType.SwitchDefault)
        else if (edgeType == "NextSwitch") (c --\ "condition").as[Expression].map(condition => EdgeType.NextSwitch(condition))
        else if (edgeType == "SubprocessOutput") (c --\ "name").as[String].map(name => EdgeType.SubprocessOutput(name))

        else throw new IllegalArgumentException(s"Unknown edge type: $edgeType")
      }
    } yield edgeTypeObj
  }

  implicit val processTypeCodec = Codecs.enumCodec(ProcessType)

  implicit val processingTypeCodec = Codecs.enumCodec(ProcessingType)

  implicit def edgeEncode = EncodeJson.of[displayablenode.Edge]

  implicit def displayableProcessCodec: CodecJson[DisplayableProcess] = CodecJson.derive[DisplayableProcess]

  implicit def validatedDisplayableProcessCodec: CodecJson[ValidatedDisplayableProcess] = CodecJson.derive[ValidatedDisplayableProcess]

  implicit def commentCodec = CodecJson.derived[Comment]

  implicit def processActivityCodec = CodecJson.derive[ProcessActivity]

  implicit def processObjectsEncodeEncode = EncodeJson.of[ProcessObjects]

  implicit def processHistory = EncodeJson.of[ProcessHistoryEntry]

  implicit def processListEncode = EncodeJson.of[List[ProcessDetails]]

  implicit def grafanaEncode = EncodeJson.of[GrafanaSettings]

  implicit def userEncodeEncode = EncodeJson.of[DisplayableUser]

  //unfortunately, this has do be done manually, as argonaut has problems with recursive types...
  implicit val encodeNodeCount : EncodeJson[NodeCount] = EncodeJson[NodeCount] {
    case NodeCount(all, errors, subProcessCounts) => jObjectFields(
      "all" -> jNumber(all),
      "errors" -> jNumber(errors),
      "subprocessCounts" -> jObjectFields(subProcessCounts.mapValues(encodeNodeCount(_)).toList: _*)
    )
  }
  
  val testResultsEncoder : EncodeJson[TestResults] = {

    val variableEncoder = BestEffortJsonEncoder(failOnUnkown = false, {
      case displayable: Displayable =>
        val prettyDisplay = displayable.display.spaces2
        def safeString(a: String) = Option(a).map(jString).getOrElse(jNull)

        displayable.originalDisplay match {
          case None => jObjectFields("pretty" -> safeString(prettyDisplay))
          case Some(original) => jObjectFields("original" -> safeString(original), "pretty" -> safeString(prettyDisplay))
        }
    }).encode _

    implicit def paramsMapEncode = EncodeJson[Map[String, Any]](map => {
      map.filterNot(a => a._2 == None || a._2 == null).mapValues(variableEncoder).asJson
    })

    def safeString(a: String) = Option(a).map(jString).getOrElse(jNull)

    implicit val ctxEncode = EncodeJson[pl.touk.nussknacker.engine.api.Context] {
      case pl.touk.nussknacker.engine.api.Context(id, vars, _, _) => jObjectFields(
        "id" -> safeString(id),
        "variables" -> vars.asJson
      )
    }

    implicit val exprInvocationResult = EncodeJson[ExpressionInvocationResult] {
      case ExpressionInvocationResult(context, name, result) => jObjectFields(
        "context" -> context.asJson,
        "name" -> safeString(name),
        "value" -> variableEncoder(result)
      )
    }

    implicit val mockedResult = EncodeJson[MockedResult] {
      case MockedResult(context, name, result) => jObjectFields(
        "context" -> context.asJson,
        "name" -> safeString(name),
        "value" -> variableEncoder(result)
      )
    }

    implicit val exceptionInfo = EncodeJson[EspExceptionInfo[_ <: Throwable]] {
      case EspExceptionInfo(nodeId, throwable, ctx) => jObjectFields(
        "nodeId" -> nodeId.asJson,
        "exception" -> jObjectFields(
          "message" -> safeString(throwable.getMessage),
          "class" -> safeString(throwable.getClass.getSimpleName)
        ),
        "context" -> ctx.asJson
      )
    }

    EncodeJson[TestResults] {
      case TestResults(nodeResults, invocationResults, mockedResults, exceptions) => jObjectFields(
        "nodeResults" -> nodeResults.asJson,
        "invocationResults" -> invocationResults.asJson,
        "mockedResults" -> mockedResults.asJson,
        "exceptions" -> exceptions.asJson
      )
    }
  }

}