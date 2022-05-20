package pl.touk.nussknacker.engine.avro.typed

import cats.data.Validated.condNel
import cats.data.{Validated, ValidatedNel}
import cats.implicits.{catsSyntaxValidatedId, _}
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.{Schema, SchemaBuilder}
import org.apache.avro.Schema.Type
import pl.touk.nussknacker.engine.api.NodeId
import pl.touk.nussknacker.engine.api.typed.typing._
import pl.touk.nussknacker.engine.api.typed.{TypedValidatorError, TypedValidatorExpected, TypedValidatorMissingFieldsError, TypedValidatorRedundantFieldsError, TypedValidatorTypeError}

import scala.collection.immutable.::

object AvroSchemaSubclassDeterminer extends LazyLogging {

  import scala.collection.JavaConverters._

  private val avroTypeDefinitionExtractor = new AvroSchemaTypeDefinitionExtractor(false)

  private val nestedObjects = Set(Type.RECORD, Type.MAP, Type.ARRAY)

  private[typed] val SimpleAvroPath = "Data"

  private val valid = Validated.Valid(())

  def validateTypingResultToSchema(typingResult: TypingResult, parentSchema: Schema)(implicit nodeId: NodeId): ValidatedNel[TypedValidatorError, Unit] =
    validateTypingResultToSchema(typingResult, parentSchema, None)

  final private[avro] def validateTypingResultToSchema(typingResult: TypingResult, schema: Schema, path: Option[String]): ValidatedNel[TypedValidatorError, Unit] = {
    (typingResult, schema.getType) match {
      case (record: TypedObjectTypingResult, Type.RECORD) =>
        validateRecordSchema(record, schema, path)
      case (map: TypedObjectTypingResult, Type.MAP) =>
        validateMapSchema(map, schema, path)
      case (array@TypedClass(cl, _), Type.ARRAY) if classOf[java.util.List[_]].isAssignableFrom(cl) =>
        validateArraySchema(array, schema, path)
      case (union: TypedUnion, _) if union.isEmptyUnion && schema.isNullable => //situation when we pass null as value
        valid
      case (anyTypingResult, Type.UNION) =>
        validateUnionSchema(anyTypingResult, schema, path)
      case (anyTypingResult, _) =>
        //How to present nested avro schema.. Right now TypingResult
        canBeSubclassOf(anyTypingResult, schema, path)
    }
  }

  private def validateRecordSchema(record: TypedObjectTypingResult, schema: Schema, path: Option[String]) = {
    val schemaFields = schema.getFields.asScala.map(field => field.name() -> field).toMap
    val requiredFieldNames = schemaFields.values.filterNot(_.hasDefaultValue).map(_.name())
    val fieldsToValidate: Map[String, TypingResult] = record.fields.filterKeys(schemaFields.contains)

    def prepareFields(fields: Set[String]) = fields.flatMap(buildPath(_, path))

    val requiredFieldsValidation = {
      val missingFields = requiredFieldNames.filterNot(record.fields.contains).toList.sorted.toSet
      condNel(missingFields.isEmpty, (), TypedValidatorMissingFieldsError(prepareFields(missingFields)))
    }

    val schemaFieldsValidation = {
      fieldsToValidate.flatMap{ case (key, value) =>
        val fieldPath = buildPath(key, path)
        schemaFields.get(key).map(f => validateTypingResultToSchema(value, f.schema(), fieldPath))
      }.foldLeft[ValidatedNel[TypedValidatorError, Unit]](().validNel)((a, b) => a combine b)
    }

    val redundantFieldsValidation = {
      val redundantFields = record.fields.keySet.diff(schemaFields.keySet)
      condNel(redundantFields.isEmpty, (), TypedValidatorRedundantFieldsError(prepareFields(redundantFields)))
    }

    val result = requiredFieldsValidation combine schemaFieldsValidation combine redundantFieldsValidation
    result
  }

  private def validateMapSchema(map: TypedObjectTypingResult, schema: Schema, path: Option[String]) = {
    val schemaFieldsValidation = map.fields.map{ case (key, value) =>
      val fieldPath = buildPath(key, path)
      validateTypingResultToSchema(value, schema.getValueType, fieldPath)
    }.foldLeft[ValidatedNel[TypedValidatorError, Unit]](().validNel)((a, b) => a combine b)

    schemaFieldsValidation
  }

  private def validateArraySchema(array: TypedClass, schema: Schema, path: Option[String]) = {
    val params = array.params.distinct

    val nestedVerification = params.collect{
      case TypedClass(cl, _) if classOf[java.util.List[_]].isAssignableFrom(cl) => true //array case - array in array
      case TypedUnion(params) if params.isEmpty => true //null case - typing result doesn't support nullable..
      case _: TypedObjectTypingResult => true //nested object case - array of objects
    }.nonEmpty

    def validateListElements(params: List[TypingResult]) = {
      params
        .zipWithIndex
        .map { case (el, index) =>
          val elementPath = buildPath(index.toString, path, isArray = true)
          validateTypingResultToSchema(el, schema.getElementType, elementPath)
        }
        .foldLeft[ValidatedNel[TypedValidatorError, Unit]](().validNel)((a, b) => a combine b)
    }

    (params, nestedVerification) match {
      case (_ :: Nil, false) =>
        canBeSubclassOf(array, schema, path)
      case (_, _) =>
        validateListElements(array.params)
    }
  }

  private def validateUnionSchema(union: TypingResult, schema: Schema, path: Option[String]) = {
    val nestedObjectsInUnion = schema.getTypes.asScala.exists(sch => nestedObjects.contains(sch.getType))

    val results = (union, nestedObjectsInUnion) match {
      case (TypedClass(_, params), true) if params.isEmpty => // primitive eq union with nested object e.g. String eq [null, List[String]]
        schema.getTypes.asScala.map(validateTypingResultToSchema(union, _, path)).toList
      case (TypedClass(_, TypedClass(_, params) :: Nil), true) if params.isEmpty => // generic with primitive eq union with nested object e.g. List[String] eq [null, List[Double]
        schema.getTypes.asScala.map(validateTypingResultToSchema(union, _, path)).toList
      case (_, true) => // nested objects, we want to go inside object and verify each field.. (we don't verify potential nullable)
        schema.getTypes.asScala.filterNot(_.isNullable).map(validateTypingResultToSchema(union, _, path)).toList
      case (_, false) => // any type eq union without nested object e.g. List[String] | String eq [null, String]
        schema.getTypes.asScala.map(validateTypingResultToSchema(union, _, path)).toList
    }

    if (results.exists(_.isValid)) {
      valid
    } else {
      results.foldLeft[ValidatedNel[TypedValidatorError, Unit]](().validNel)((a, b) => a combine b)
    }
  }

  private def canBeSubclassOf(objTypingResult: TypingResult, schema: Schema, path: Option[String]): ValidatedNel[TypedValidatorError, Unit] = {
    val schemaAsTypedResult = AvroSchemaTypeDefinitionExtractor.typeDefinition(schema)
    condNel(objTypingResult.canBeSubclassOf(schemaAsTypedResult), (),
      TypedValidatorTypeError(path.getOrElse(SimpleAvroPath), objTypingResult, AvroSchemaExpected(schema))
    )
  }

  private def buildPath(key: String, path: Option[String], isArray: Boolean = false) = Some(
    path.map(p => if(isArray) s"$p[$key]" else s"$p.$key").getOrElse(key)
  )
}

case class AvroSchemaExpected(schema: Schema) extends TypedValidatorExpected {
  override def display: String = AvroSchemaTypeDisplayer.display(schema)
}
