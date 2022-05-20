package pl.touk.nussknacker.engine.avro.typed

import org.apache.avro.Schema
import pl.touk.nussknacker.engine.avro.AvroUtils

import scala.util.Random

private[typed] object  AvroTestData {

  val suitEnumSchema: Schema = AvroUtils.parseSchema("""{"name":"Suit","type":"enum","symbols":["SPADES","HEARTS","DIAMONDS","CLUBS"]}""")
  val nullSchema: Schema = AvroUtils.parseSchema("""{"type":"null"}""")
  val integerSchema: Schema = AvroUtils.parseSchema("""{"type":"int"}""")
  val longSchema: Schema = AvroUtils.parseSchema("""{"type":"long"}""")
  val doubleSchema: Schema = AvroUtils.parseSchema("""{"type":"double"}""")
  val floatSchema: Schema = AvroUtils.parseSchema("""{"type":"float"}""")
  val stringSchema: Schema = AvroUtils.parseSchema("""{"type":"string"}""")
  val booleanSchema: Schema = AvroUtils.parseSchema("""{"type":"boolean"}""")

  val doubleArraySchema: Schema = buildArray(doubleSchema)
  val integerArraySchema: Schema = buildArray(integerSchema)
  val stringArraySchema: Schema = buildArray(stringSchema)

  val doubleMapSchema: Schema = buildMap(doubleSchema)
  val stringMapSchema: Schema = buildMap(stringSchema)

  val avroPriceRecord: Schema = buildRecord(buildField("price", None, nullSchema, doubleSchema))

  //Avro array schemas..
  val baseArray: Schema = buildArray(doubleSchema, integerSchema)

  val recordWithArray: Schema = buildRecord(buildField("array", None, baseArray))

  val recordWithMaybeArray: Schema = buildRecord(buildField("array", None, nullSchema, baseArray))

  val recordWithOptionalArray: Schema = buildRecord(buildField("array", Some("null"),
    nullSchema, baseArray
  ))

  val recordWithOptionalArrayOfArrays: Schema = buildRecord(buildField("array", Some("null"),
    nullSchema, buildArray(baseArray)
  ))

  val recordWithOptionalArrayOfRecords: Schema = buildRecord(buildField("array", Some("null"),
    nullSchema, buildArray(nullSchema, avroPriceRecord)
  ))

  //Avro map schemas..
  val baseMapSchema: Schema = buildMap(doubleSchema, integerSchema)

  val recordWithMap: Schema = buildRecord(buildField("map", None,
    baseMapSchema
  ))

  val recordWithMaybeMap: Schema = buildRecord(buildField("map", None,
    nullSchema, baseMapSchema
  ))

  val recordWithOptionalMap: Schema = buildRecord(buildField("map", Some("null"),
    nullSchema, baseMapSchema
  ))

  val recordWithMapMap: Schema = buildRecord(buildField("map", Some("null"),
    nullSchema, buildMap(nullSchema, baseMapSchema)
  ))



  def buildRecord(fields: String*): Schema = AvroUtils.parseSchema(
    s"""
       |{
       |  "type": "record",
       |  "name": "AvroTestRecord_${Math.abs(Random.nextLong())}",
       |  "fields": [
       |    ${fields.mkString(",")}
       |  ]
       |}
       |""".stripMargin)

  def buildField(name: String, default: Option[String], schemas: Schema*): String =
    s"""
       |{
       |  "name": "$name",
       |  ${default.map(d => s""""default":$d,""").getOrElse("")}
       |  "type":[${schemas.map(_.toString()).mkString(",")}]
       |}
       |""".stripMargin

  def buildArray(schemas: Schema*): Schema = AvroUtils.parseSchema(s"""{"type":"array","items": [${schemas.map(_.toString()).mkString(",")}]}""")

  def buildMap(schemas: Schema*): Schema = AvroUtils.parseSchema(s"""{"type":"map","values": [${schemas.map(_.toString()).mkString(",")}]}""")

}
