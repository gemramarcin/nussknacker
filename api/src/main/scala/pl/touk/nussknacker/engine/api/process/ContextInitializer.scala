package pl.touk.nussknacker.engine.api.process

import cats.data.ValidatedNel
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError.NodeId
import pl.touk.nussknacker.engine.api.context.{ProcessCompilationError, ValidationContext}
import pl.touk.nussknacker.engine.api.runtimecontext.{ContextIdGenerator, EngineRuntimeContext}
import pl.touk.nussknacker.engine.api.typed.typing.TypingResult
import pl.touk.nussknacker.engine.api.{Context, Lifecycle, VariableConstants}

/**
  * ContextInitializer provides implementation of transformation from raw event generated by source to Context.
  *
  * @tparam Raw - type of raw event that is generated by source (KafkaDeserializationSchema for kafka) in source function.
  */
trait ContextInitializer[Raw] extends Serializable {

  /**
    * Initializes `Context` with raw event value.
    *
    * @param contextIdGenerator - context id generator
    */
  def initContext(contextIdGenerator: ContextIdGenerator): ContextInitializingFunction[Raw]

  /**
    * Enhances validation context with definition of all variables produced by the source.
    *
    * @param context      - `ValidationContext` initialized with global variables, definition of variables available in `Context` scope and their types
    * @return - validation context with initialized "input" variable.
    */
  def validationContext(context: ValidationContext)(implicit nodeId: NodeId): ValidatedNel[ProcessCompilationError, ValidationContext]

}

trait ContextInitializingFunction[Raw] extends (Raw => Context)

/**
  * Basic implementation of context initializer. Used when raw event produced by source does not need further transformations and
  * should be assigned to default "input" variable directly.
  *
  * @tparam Raw - type of raw event that is generated by source (KafkaDeserializationSchema for kafka) in source function.
  */
class BasicContextInitializer[Raw](protected val outputVariableType: TypingResult, protected val outputVariableName: String = VariableConstants.InputVariableName) extends ContextInitializer[Raw] {

  override def initContext(contextIdGenerator: ContextIdGenerator): ContextInitializingFunction[Raw] =
    new BasicContextInitializingFunction(contextIdGenerator, outputVariableName)

  override def validationContext(context: ValidationContext)(implicit nodeId: NodeId): ValidatedNel[ProcessCompilationError, ValidationContext] = {
    context.withVariable(outputVariableName, outputVariableType, None)
  }

}

/**
  * Initialize context of variables based on "input" variable represents the event.
  *
  * @param contextIdGenerator - context id generator
  * @param outputVariableName - name of output variable
  * @tparam Raw - type of raw event that is generated by source (KafkaDeserializationSchema for kafka) in source function.
  */
class BasicContextInitializingFunction[Raw](contextIdGenerator: ContextIdGenerator, outputVariableName: String) extends ContextInitializingFunction[Raw] {

  override def apply(input: Raw): Context =
    newContext.withVariable(outputVariableName, input)

  protected def newContext: Context = Context(contextIdGenerator.nextContextId())

}