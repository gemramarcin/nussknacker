package pl.touk.nussknacker.engine.api.typed

import cats.data.NonEmptyList
import pl.touk.nussknacker.engine.api.NodeId
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError.CustomNodeError
import pl.touk.nussknacker.engine.api.typed.typing.{TypedUnion, TypingResult}

trait TypedValidatorErrorPresenter {
  def presentErrors(errors: NonEmptyList[TypedValidatorError])(implicit nodeId: NodeId): CustomNodeError
}

object TypedValidatorErrorPresenter {
  implicit class DisplayingTypingResult(typingResult: TypingResult) {
    def displayType: String = typingResult match {
      case un: TypedUnion if un.isEmptyUnion => "null"
      case _ => typingResult.display
    }
  }
}

object DefaultTypedValidatorErrorPresenter {
  private val ValidationErrorMessageBase = "Provided value does not match scenario output"
  private val ValidationRedundantFieldsErrorMessage = "Redundant fields"
  private val ValidationMissingFieldsErrorMessage = "Missing fields"
  private val ValidationTypeErrorMessage = "Type validation"
}

class DefaultTypedValidatorErrorPresenter(schemaParamName: String) extends TypedValidatorErrorPresenter {

  import DefaultTypedValidatorErrorPresenter._

  override def presentErrors(errors: NonEmptyList[TypedValidatorError])(implicit nodeId: NodeId): CustomNodeError = {
    val missingFieldsError = errors.collect { case e: TypedValidatorMissingFieldsError => e }.flatMap(_.fields)
    val redundantFieldsError = errors.collect { case e: TypedValidatorRedundantFieldsError => e }.flatMap(_.fields)
    val typeFieldsError = errors
      .collect { case e: TypedValidatorTypeError => e }
      .groupBy(err => (err.field, err.actual))
      .map { case ((field, actual), errors) =>
        TypedValidatorGroupTypeError(field, actual, errors.map(_.expected).distinct)
      }.toList

    def makeErrors(messages: List[String], baseMessage: String) =
      if (messages.nonEmpty) messages.mkString(s"$baseMessage: ", ", ", ".") :: Nil else Nil

    val messageTypeFieldErrors = {
      val errorMessages = typeFieldsError.map(err =>
        s"path '${err.field}' actual: '${err.displayActual}' expected: '${err.displayExpected}'"
      )
      makeErrors(errorMessages, ValidationTypeErrorMessage)
    }

    val messageMissingFieldsError = makeErrors(missingFieldsError, ValidationMissingFieldsErrorMessage)
    val messageRedundantFieldsError = makeErrors(redundantFieldsError, ValidationRedundantFieldsErrorMessage)

    val messageErrors = messageTypeFieldErrors ::: messageMissingFieldsError ::: messageRedundantFieldsError

    CustomNodeError(messageErrors.mkString(s"$ValidationErrorMessageBase - errors:\n", ", ", ""), Option(schemaParamName))
  }

  case class TypedValidatorGroupTypeError(field: String, actual: TypingResult, expected: List[TypedValidatorExpected]) {
    import TypedValidatorErrorPresenter.DisplayingTypingResult

    def displayExpected: String = expected.map(_.display).mkString(" | ")
    def displayActual: String = actual.displayType
  }
}

trait TypedValidatorExpected {
  def display: String
}

sealed trait TypedValidatorError

case class TypedValidatorTypeError(field: String, actual: TypingResult, expected: TypedValidatorExpected) extends TypedValidatorError

case class TypedValidatorMissingFieldsError(fields: Set[String]) extends TypedValidatorError

case class TypedValidatorRedundantFieldsError(fields: Set[String]) extends TypedValidatorError
