package pl.touk.nussknacker.engine.avro.schemaregistry.confluent.serialization.jsonpayload

import io.circe.Decoder
import org.apache.avro.Conversions.UUIDConversion
import org.apache.avro.generic.GenericRecord
import org.apache.avro.specific.SpecificRecordBase
import org.apache.avro.{AvroRuntimeException, AvroTypeException, LogicalTypes, Schema}
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.serialization.jsonpayload.JsonPayloadToAvroConverter._
import tech.allegro.schema.json2avro.converter.types.AvroTypeConverter.Incompatible
import tech.allegro.schema.json2avro.converter.types.{AvroTypeConverter, BytesDecimalConverter, IntDateConverter, IntTimeMillisConverter, LongTimeMicrosConverter, LongTimestampMicrosConverter, LongTimestampMillisConverter}
import tech.allegro.schema.json2avro.converter.{CompositeJsonToAvroReader, JsonAvroConverter, PathsPrinter}

import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, LocalTime}
import java.util
import scala.collection.JavaConverters._
import scala.util.Try

class JsonPayloadToAvroConverter(specificClass: Option[Class[SpecificRecordBase]]) {

  private val converter = new JsonAvroConverter(
    new CompositeJsonToAvroReader(
      List[AvroTypeConverter](
        LogicalTypeIntDateConverter, LogicalTypeIntTimeMillisConverter, LogicalTypeLongTimeMicrosConverter, LogicalTypeLongTimestampMillisConverter, LogicalTypeLongTimestampMicrosConverter,
        UUIDConverter, DecimalConverter
  ).asJava
        )
  )

  def convert(payload: Array[Byte], schema: Schema): GenericRecord = {
    specificClass match {
      case Some(kl) => converter.convertToSpecificRecord(payload, kl, schema)
      case None => converter.convertToGenericDataRecord(payload, schema)
    }
  }

}

object JsonPayloadToAvroConverter {

  object LogicalTypeIntDateConverter extends IntDateConverter(DateTimeFormatter.ISO_DATE) {
    override def parseDateTime(dateTimeString: String): AnyRef = LocalDate.from(DateTimeFormatter.ISO_DATE.parse(dateTimeString))
  }

  object LogicalTypeIntTimeMillisConverter extends IntTimeMillisConverter(DateTimeFormatter.ISO_TIME) {
    override def parseDateTime(dateTimeString: String): AnyRef = LocalTime.from(DateTimeFormatter.ISO_TIME.parse(dateTimeString))
  }

  object LogicalTypeLongTimeMicrosConverter extends LongTimeMicrosConverter(DateTimeFormatter.ISO_TIME) {
    override def parseDateTime(dateTimeString: String): AnyRef = LocalTime.from(DateTimeFormatter.ISO_TIME.parse(dateTimeString))
  }

  object LogicalTypeLongTimestampMillisConverter extends LongTimestampMillisConverter(DateTimeFormatter.ISO_DATE_TIME) {
    override def parseDateTime(dateTimeString: String): AnyRef = Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(dateTimeString))
  }

  object LogicalTypeLongTimestampMicrosConverter extends LongTimestampMicrosConverter(DateTimeFormatter.ISO_DATE_TIME) {
    override def parseDateTime(dateTimeString: String): AnyRef = Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(dateTimeString));
  }

  object UUIDConverter extends BaseAvroTypeConverter {

    private val conversion = new UUIDConversion

    override def canManage(schema: Schema, path: util.Deque[String]): Boolean =
      schema.getType == Schema.Type.STRING && schema.getLogicalType == LogicalTypes.uuid()

    override def convertPF(schema: Schema, path: util.Deque[String], silently: Boolean): PartialFunction[AnyRef, AnyRef] = {
      case str: String => tryConvert(path, silently)(conversion.fromCharSequence(str, schema, schema.getLogicalType))
    }

    override def expectedFormat: String = "UUID"

  }

  object DecimalConverter extends BytesDecimalConverter {
    override def convert(field: Schema.Field, schema: Schema, value: Any, path: util.Deque[String], silently: Boolean): AnyRef = {
      val scale = schema.getObjectProp("scale").asInstanceOf[Int]
      try {
        val bigDecimalInput = new BigDecimal(value.toString)
        bigDecimalInput.multiply(BigDecimal.TEN.pow(scale - bigDecimalInput.scale))
      } catch {
        case _: NumberFormatException =>
          if (silently) {
            new Incompatible("string number, decimal");
          } else {
            throw new AvroTypeException("Field " + print(path) + " is expected to be a valid number. current value is " + value + ".");
          }
      }
    }
  }

  trait BaseAvroTypeConverter extends AvroTypeConverter {

    def expectedFormat: String

    override final def convert(field: Schema.Field, schema: Schema, jsonValue: AnyRef, path: util.Deque[String], silently: Boolean): AnyRef = {
      convertPF(schema, path, silently).lift(jsonValue).getOrElse(handleUnexpectedFormat(path, silently, None))
    }

    protected def convertPF(schema: Schema, path: util.Deque[String], silently: Boolean): PartialFunction[AnyRef, AnyRef]

    protected def tryConvert(path: util.Deque[String], silently: Boolean)(doConvert: => AnyRef): AnyRef = {
      Try(doConvert).fold(ex => handleUnexpectedFormat(path, silently, Some(ex)), identity)
    }

    protected implicit class DecoderResultExt[A <: AnyRef](decoderResult: Decoder.Result[A]) {
      def toValue(path: util.Deque[String], silently: Boolean): AnyRef = {
        decoderResult.fold(ex => handleUnexpectedFormat(path, silently, Some(ex)), identity)
      }
    }

    protected def handleUnexpectedFormat(path: util.Deque[String], silently: Boolean, cause: Option[Throwable]): AnyRef =
      if (silently)
        new AvroTypeConverter.Incompatible(expectedFormat)
      else
        throw new AvroRuntimeException(s"Field: ${PathsPrinter.print(path)} is expected to has $expectedFormat format", cause.orNull)

  }

}