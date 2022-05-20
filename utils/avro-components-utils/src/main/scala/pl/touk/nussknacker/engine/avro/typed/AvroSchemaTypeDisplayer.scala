package pl.touk.nussknacker.engine.avro.typed

import org.apache.avro.Schema
import pl.touk.nussknacker.engine.api.typed.typing.TypedUnion
import pl.touk.nussknacker.engine.avro.schema.AvroStringSettings

object AvroSchemaTypeDisplayer {

  import collection.JavaConverters._

  private val TypesSeparator = " | "

  /**
    * see AvroSchemaTypeDefinitionExtractor | BestEffortAvroEncoder for underlying avro types
    * !when applying changes keep in mind that this Schema.Type pattern matching is duplicated in {@link pl.touk.nussknacker.engine.avro.typed.AvroSchemaTypeDefinitionExtractor}
    */
  def display(schema: Schema): String = {
    schema.getType match {
      case Schema.Type.RECORD =>
        schema
          .getFields
          .asScala
          .map(f =>  s"${f.name()}: ${display(f.schema())}")
          .mkString("{", ", ", "}")
      case Schema.Type.ENUM => List(
          s"Enum[${schema.getEnumSymbols.asScala.mkString(TypesSeparator)}]",
          AvroStringSettings.stringTypingResult.display.capitalize
        ).mkString(TypesSeparator)
      case Schema.Type.ARRAY =>
        s"List[${display(schema.getElementType)}]"
      case Schema.Type.MAP =>
        s"Map[String, ${display(schema.getValueType)}]"
      case Schema.Type.UNION =>
        schema.getTypes.asScala.map(display).mkString(TypesSeparator)
      case Schema.Type.FIXED => s"Fixed[${schema.getFixedSize}]"
      case _ =>
        val typed = AvroSchemaTypeDefinitionExtractor.typeDefinition(schema)
        typed match {
          case t: TypedUnion if t.isEmptyUnion => "null"
          case _ => typed.display.capitalize
        }
    }
  }
}
