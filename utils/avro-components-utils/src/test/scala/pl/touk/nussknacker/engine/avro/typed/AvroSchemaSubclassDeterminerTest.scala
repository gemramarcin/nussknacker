package pl.touk.nussknacker.engine.avro.typed

import cats.data.{NonEmptyList, ValidatedNel}
import cats.data.Validated.{Invalid, Valid}
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData.EnumSymbol
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{FunSuite, Matchers}
import pl.touk.nussknacker.engine.api.NodeId
import pl.touk.nussknacker.engine.api.typed.typing.Typed.{fromInstance, typeMapFields, typedClass}
import pl.touk.nussknacker.engine.api.typed.{DefaultTypedValidatorErrorPresenter, TypedValidatorError, TypedValidatorMissingFieldsError, TypedValidatorTypeError}
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, TypedObjectTypingResult, TypedUnion, TypingResult, Unknown}
import pl.touk.nussknacker.engine.avro.AvroUtils
import pl.touk.nussknacker.engine.avro.schema.AvroStringSettings

import java.lang.{Boolean, Double, Float, Integer, Long, String}
import scala.io.Source
import scala.reflect.ClassTag

class AvroSchemaSubclassDeterminerTest extends FunSuite with Matchers with TableDrivenPropertyChecks {

  import AvroSchemaSubclassDeterminer._
  import AvroTestData._

  implicit private val nodeId: NodeId = NodeId("testNodeId")

  private val schemaParamName = "sink"

  private val valid = Valid(())

  test("should determine avro schema") {
    val testData =  Table(
      ("schema", "data", "result"),
        (nullSchema, typed(null.asInstanceOf[Any]), valid),
        (integerSchema, typed(1), valid),
        (integerSchema, typed(Long.MAX_VALUE), valid), //Is it okey..?
        (stringSchema, typed("1"), valid),
        (doubleSchema, typed(1), valid),
        (doubleSchema, typed(1.0), valid),
        (floatSchema, typed(1.1f), valid),
        (floatSchema, typed(Double.MAX_VALUE), valid), //Is it okey..?
        (booleanSchema, typed(true), valid),

        (nullSchema, typed(nullSchema), valid),
        (integerSchema, typed(integerSchema), valid),
        (longSchema, typed(longSchema), valid),
        (longSchema, typed(integerSchema), valid),
        (integerSchema, typed(longSchema), valid), //Is it okey..?
        (stringSchema, typed(stringSchema), valid),
        (doubleSchema, typed(doubleSchema), valid),
        (doubleSchema, typed(doubleSchema), valid), //Is it okey..?
        (doubleSchema, typed(floatSchema), valid),
        (floatSchema, typed(doubleSchema), valid),
        (booleanSchema, typed(booleanSchema), valid),

        (integerSchema, typed(null.asInstanceOf[Any]), invalid(errorType(SimpleAvroPath, Typed.empty, integerSchema))),
        (integerSchema, typed("2"), invalid(errorType[String](SimpleAvroPath, integerSchema))),
        (integerSchema, typed(true), invalid(errorType[Boolean](SimpleAvroPath, integerSchema))),
        (integerSchema, typed(1.0), invalid(errorType[Double](SimpleAvroPath, integerSchema))),

        (integerSchema, typed(nullSchema), invalid(errorType(SimpleAvroPath, Typed.empty, integerSchema))),
        (integerSchema, typed(stringSchema), invalid(errorType[String](SimpleAvroPath, integerSchema))),
        (integerSchema, typed(booleanSchema), invalid(errorType[Boolean](SimpleAvroPath, integerSchema))),
        (integerSchema, typed(doubleSchema), invalid(errorType[Double](SimpleAvroPath, integerSchema))),
        (integerSchema, typed(floatSchema), invalid(errorType[Float](SimpleAvroPath, integerSchema))),

//      (doubleArraySchema, typed(doubleArraySchema), valid),
//      (doubleArraySchema, typed(baseArray), valid),
//      (baseArray, typed(doubleArraySchema), valid),

//      (recordWithArray, typed(Map("array" -> List(66.66))), valid),
//      (recordWithArray, typed(Map("array" -> doubleArraySchema)), valid),
//      (recordWithArray, typed(Map("array" -> integerArraySchema)), valid),
//      (recordWithArray, typed(Map("array" -> List())), valid),
//      (recordWithArray, typed(Map()), invalid(errorMissingFields("array"))),
//        (recordWithArray, typed(Map("array" -> null)), invalid(
//          errorType("array", Typed.empty, baseArray),
//        )),
//      (recordWithArray, typed(Map("array" -> null)), invalid(
//        errorType("array", Typed.empty, baseArray),
//      )),
//      (recordWithArray, typed(Map("array" -> "1")), invalid(
//        errorType("array", typedClass[String], baseArray),
//      )),
//      (recordWithArray, typed(Map("array" -> stringArraySchema)), invalid(
//        errorType("array", typedArray[String], baseArray),
//      )),
//      (recordWithArray, typed(Map("array" -> List("1", true))), invalid(
//        errorType[String]("array[0]", doubleSchema),
//        errorType[String]("array[0]", integerSchema),
//        errorType[Boolean]("array[1]", doubleSchema),
//        errorType[Boolean]("array[1]", integerSchema),
//      )),
//
//      (recordWithMaybeArray, typed(Map("array" -> List(66.66))), valid),
//      (recordWithMaybeArray, typed(Map("array" -> doubleArraySchema)), valid),
//      (recordWithMaybeArray, typed(Map("array" -> integerArraySchema)), valid),
//      (recordWithMaybeArray, typed(Map("array" -> List())), valid),
//      (recordWithMaybeArray, typed(Map("array" -> null)), valid),
//      (recordWithMaybeArray, typed(Map()), invalid(errorMissingFields("array"))),
//      (recordWithMaybeArray, typed(Map("array" -> "1")), invalid(
//        errorType("array", typedClass[String], nullSchema),
//        errorType("array", typedClass[String], baseArray),
//      )),
//      (recordWithMaybeArray, typed(Map("array" -> stringArraySchema)), invalid(
//        errorType("array", typedArray[String], nullSchema),
//        errorType("array", typedArray[String], baseArray),
//      )),
//      (recordWithMaybeArray, typed(Map("array" -> List("1", true))), invalid(
//        errorType[String]("array[0]", doubleSchema),
//        errorType[String]("array[0]", integerSchema),
//        errorType[Boolean]("array[1]", doubleSchema),
//        errorType[Boolean]("array[1]", integerSchema),
//      )),
//
//      (recordWithOptionalArray, typed(Map("array" -> List(66.66))), valid),
//      (recordWithOptionalArray, typed(Map("array" -> doubleArraySchema)), valid),
//      (recordWithOptionalArray, typed(Map("array" -> integerArraySchema)), valid),
//      (recordWithOptionalArray, typed(Map("array" -> null)), valid),
//      (recordWithOptionalArray, typed(Map("array" -> List())), valid),
//      (recordWithOptionalArray, typed(Map()), valid),
//      (recordWithOptionalArray, typed(Map("array" -> "1")), invalid(
//        errorType("array", typedClass[String], nullSchema),
//        errorType("array", typedClass[String], baseArray),
//      )),
//      (recordWithOptionalArray, typed(Map("array" -> stringArraySchema)), invalid(
//        errorType("array", typedArray[String], nullSchema),
//        errorType("array", typedArray[String], baseArray),
//      )),
//      (recordWithOptionalArray, typed(Map("array" -> List("1", true))), invalid(
//        errorType[String]("array[0]", doubleSchema),
//        errorType[String]("array[0]", integerSchema),
//        errorType[Boolean]("array[1]", doubleSchema),
//        errorType[Boolean]("array[1]", integerSchema),
//      )),
//
//      (recordWithOptionalArrayOfArrays, typed(Map("array" -> List(List(66.66)))), valid),
//      (recordWithOptionalArrayOfArrays, typed(Map("array" -> List(doubleArraySchema))), valid),
//      (recordWithOptionalArrayOfArrays, typed(Map("array" -> List(integerArraySchema))), valid),
//      (recordWithOptionalArrayOfArrays, typed(Map("array" -> null)), valid),
//      (recordWithOptionalArrayOfArrays, typed(Map("array" -> List())), valid),
//      (recordWithOptionalArrayOfArrays, typed(Map()), valid),
//      (recordWithOptionalArrayOfArrays, typed(Map("array" -> "1")), invalid(
//        errorType[String]("array", nullSchema),
//        errorType[String]("array", buildArray(baseArray)),
//      )),
//      (recordWithOptionalArrayOfArrays, typed(Map("array" -> stringArraySchema)), invalid(
//        errorType("array", typedArray[String], nullSchema),
//        errorType("array", typedArray[String], buildArray(baseArray)),
//      )),
//      (recordWithOptionalArrayOfArrays, typed(Map("array" -> List(List("1", true)))), invalid(
//        errorType[String]("array[0][0]", doubleSchema),
//        errorType[String]("array[0][0]", integerSchema),
//        errorType[Boolean]("array[0][1]", doubleSchema),
//        errorType[Boolean]("array[0][1]", integerSchema)
//      )),
//
//      (recordWithOptionalArrayOfRecords, typed(Map("array" -> List(Map("price" -> 66.66)))), valid),
//      (recordWithOptionalArrayOfRecords, typed(Map("array" -> List(Map("price" -> null)))), valid),
//      (recordWithOptionalArrayOfRecords, typed(Map("array" -> List(null))), valid),
//      (recordWithOptionalArrayOfRecords, typed(Map("array" -> List())), valid),
//      (recordWithOptionalArrayOfRecords, typed(Map()), valid),
//      (recordWithOptionalArrayOfRecords, typed(Map("array" -> "1")), invalid(
//        errorType[String]("array", nullSchema),
//        errorType[String]("array", buildArray(nullSchema, avroPriceRecord)),
//      )),
//      (recordWithOptionalArrayOfRecords, typed(Map("array" -> List(Map("price" -> "66.66")))), invalid(
//        errorType[String]("array[0].price", nullSchema),
//        errorType[String]("array[0].price", doubleSchema),
//      )),
//
//      (doubleMapSchema, typed(baseMapSchema), valid),
//      (baseMapSchema, typed(doubleMapSchema), valid),
//      (recordWithMap, typed(Map("map" -> Map("price" -> 1.0))), valid),
//      (recordWithMap, typed(Map("map" -> Map("price" -> doubleSchema))), valid),
//      (recordWithMap, typed(Map("map"-> Map())), valid),
//      (recordWithMap, typed(Map()), invalid(errorMissingFields("map"))),
//      (recordWithMap, typed(Map("map" -> null)), invalid(
//        errorType("map", Typed.empty, baseMapSchema),
//      )),
//      (recordWithMap, typed(Map("map" -> "1")), invalid(
//        errorType[String]("map", baseMapSchema),
//      )),
//      (recordWithMap, typed(Map("map" -> Map("price" -> "str", "price2" -> 3.0, "price3" -> true))), invalid(
//        errorType[String]("map.price", doubleSchema),
//        errorType[String]("map.price", integerSchema),
//        errorType[Boolean]("map.price3", doubleSchema),
//        errorType[Boolean]("map.price3", integerSchema),
//      )),
//      (recordWithMaybeMap, typed(Map("map" -> Map("price" -> 1.0))), valid),
//      (recordWithMaybeMap, typed(Map("map" -> Map("price" -> doubleSchema))), valid),
//      (recordWithMaybeMap, typed(Map("map"-> Map())), valid),
//      (recordWithMaybeMap, typed(Map()), invalid(errorMissingFields("map"))),
//      (recordWithMaybeMap, typed(Map("map" -> null)), valid),
//      (recordWithMaybeMap, typed(Map("map" -> "1")), invalid(
//        errorType[String]("map", nullSchema),
//        errorType[String]("map", baseMapSchema),
//      )),
//      (recordWithMaybeMap, typed(Map("map" -> Map("price" -> "str", "price2" -> 3.0, "price3" -> true))), invalid(
//        errorType[String]("map.price", doubleSchema),
//        errorType[String]("map.price", integerSchema),
//        errorType[Boolean]("map.price3", doubleSchema),
//        errorType[Boolean]("map.price3", integerSchema),
//      )),
//

        //TODO: Record in Record and some nullable / default / optional test
//      (recordSchema, Map("record" -> Map("price" -> 99.99), "maybeRecord" -> Map("price" -> 99.99), "optionalRecord" -> Map("price" -> 99.99)), true),
//      (recordSchema, Map("record" -> Map("price" -> 99.99), "maybeRecord" -> null), true),
//      (recordSchema, Map("record" -> Map("price" -> 99.99), "maybeRecord" -> null, "optionalRecord" -> Map("price" -> 99)), true),
//
//      (suitEnumSchema, typed(Map("suit" -> "SPADES", "maybeSuit" -> "HEARTS", "defaultSuit" -> "DIAMONDS", "optionalSuit" -> "DIAMONDS")), valid),
//      (suitEnumSchema, typed(Map("suit" -> "SPADES", "maybeSuit" -> null)), valid),
//      (
//        suitEnumSchema,
//        typed(Map("suit" -> "SPADES", "maybeSuit" -> null, "optionalSuit" -> 1)),
//        invalid(
//          errorType[Integer]("optionalSuit", nullSchema),
//          errorType[Integer]("optionalSuit", suitEnumSchema),
//        )
//      ),
//
//      (fixedSchema, Map("md5" -> "c4ca4238a0b923820dcc509a6f75849b", "maybeMd5" -> "c81e728d9d4c2f636f067f89cc14862c", "optionalMd5" -> "eccbc87e4b5ce2fe28308fd9f2a7baf3").asJava, valid),
//      (fixedSchema, Map("md5" -> "c4ca4238a0b923820dcc509a6f75849b", "maybeMd5" -> null).asJava, valid),
//      (fixedSchema, Map("md5" -> "c4ca4238a0b923820dcc509a6f75849b", "maybeMd5" -> null, "optionalMd5" -> "eccbc87e4b5ce2fe28308fd9f2a7baf3c45f").asJava, false),
    )

    forAll(testData) { (schema: Schema, data: TypingResult, expected: ValidatedNel[TypedValidatorError, Unit]) =>
      val result = AvroSchemaSubclassDeterminer.validateTypingResultToSchema(data, schema, None)

      result.leftMap(errors => {
        val str = new DefaultTypedValidatorErrorPresenter(schemaParamName).presentErrors(errors)
        str
      })

      result shouldBe expected
    }
  }

  private def typed(data: Any): TypingResult = {
    data match {
      case map: Map[String@unchecked, _] =>
        val fields = map.map {
          case (k, v) => k -> typed(v)
        }.toList
        TypedObjectTypingResult(fields)
      case list: List[_] =>
        val params = list.map(typed)
        Typed.typedClass(classOf[ java.util.List[_]], params)
      case schema: Schema => typed(schema)
      case _ => Typed.fromInstance(data)
    }
  }

  private def errorType[A: ClassTag](path: String, expected: Schema): TypedValidatorTypeError = errorType(path, Typed.typedClass[A], expected)

  private def errorType(path: String, actual: TypingResult, expected: Schema): TypedValidatorTypeError = TypedValidatorTypeError(path, actual, AvroSchemaExpected(expected))

  private def errorMissingFields(fields: String*) = TypedValidatorMissingFieldsError(fields.toSet)

  private def typedArray[E: ClassTag] = Typed.genericTypeClass[java.util.List[_]](List(Typed.typedClass[E]))

  private def typedMap(fields: (String, TypingResult)*) = TypedObjectTypingResult(fields.toList)

  private def typed(schema: Schema): TypingResult = AvroSchemaTypeDefinitionExtractor.typeDefinition(schema)

  private def invalid(errors: TypedValidatorError*): Invalid[NonEmptyList[TypedValidatorError]] =
    Invalid(NonEmptyList.fromListUnsafe(errors.toList))

}
