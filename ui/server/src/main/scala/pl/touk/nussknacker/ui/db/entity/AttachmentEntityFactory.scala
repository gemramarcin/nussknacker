package pl.touk.nussknacker.ui.db.entity

import pl.touk.nussknacker.engine.api.process.{ProcessId, VersionId}
import pl.touk.nussknacker.ui.db.DateUtils
import slick.ast.ColumnOption.PrimaryKey
import slick.lifted.{TableQuery => LTableQuery}
import slick.sql.SqlProfile.ColumnOption.NotNull

import java.sql.Timestamp
import java.time.LocalDateTime

trait AttachmentEntityFactory extends BaseEntityFactory {

  import profile.api._

  class AttachmentEntity(tag: Tag) extends Table[AttachmentEntityData](tag, "process_attachments") {
    
    def id = column[Long]("id", PrimaryKey, O.AutoInc)

    def processId = column[ProcessId]("process_id", NotNull)

    def processVersionId = column[VersionId]("process_version_id", NotNull)

    def fileName = column[String]("file_name", NotNull)

    def data = column[Array[Byte]]("data", NotNull)

    def createDate = column[Timestamp]("create_date", NotNull)

    def user = column[String]("user", NotNull)

    def * = (id, processId, processVersionId, fileName, data, user, createDate) <> (
      AttachmentEntityData.apply _ tupled, AttachmentEntityData.unapply
    )

  }

  val attachmentsTable: LTableQuery[AttachmentEntityFactory#AttachmentEntity] = LTableQuery(new AttachmentEntity(_)) 
  
}

case class AttachmentEntityData(id: Long, processId: ProcessId, processVersionId: VersionId, fileName: String, data: Array[Byte], user: String, createDate: Timestamp) {
  val createDateTime: LocalDateTime = DateUtils.toLocalDateTime(createDate)
}
