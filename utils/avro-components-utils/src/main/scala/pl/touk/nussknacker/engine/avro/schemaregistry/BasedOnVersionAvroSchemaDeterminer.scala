package pl.touk.nussknacker.engine.avro.schemaregistry

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import pl.touk.nussknacker.engine.avro.{AvroSchemaDeterminer, RuntimeSchemaData, SchemaDeterminerError}

class BasedOnVersionAvroSchemaDeterminer(schemaRegistryClient: SchemaRegistryClient,
                                         topic: String,
                                         versionOption: SchemaVersionOption,
                                         isKey: Boolean) extends AvroSchemaDeterminer {

  override def determineSchemaUsedInTyping: Validated[SchemaDeterminerError, RuntimeSchemaData[AvroSchema]] = {
    val version = versionOption match {
      case ExistingSchemaVersion(v) => Some(v)
      case LatestSchemaVersion => None
    }
    schemaRegistryClient
      .getFreshSchema(topic, version, isKey = isKey)
      .leftMap(err => new SchemaDeterminerError(s"Fetching schema error for topic: $topic, version: $versionOption", err))
      .andThen(withMetadata => withMetadata.schema match {
        case s: AvroSchema => Valid(RuntimeSchemaData(s.rawSchema(), Some(withMetadata.id)))
        case s => Invalid(new SchemaDeterminerError(s"Avro schema is required, but got ${s.schemaType()}", new IllegalArgumentException("")))
      })
  }

}

