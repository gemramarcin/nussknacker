package pl.touk.nussknacker.ui.db.entity

import pl.touk.nussknacker.engine.api.ProcessVersion
import pl.touk.nussknacker.engine.api.deployment.GraphProcess
import pl.touk.nussknacker.engine.api.process.{ProcessId, ProcessName, VersionId}
import slick.jdbc.JdbcProfile
import slick.lifted.{ForeignKeyQuery, TableQuery => LTableQuery}
import slick.sql.SqlProfile.ColumnOption.NotNull

import java.sql.Timestamp

trait ProcessVersionEntityFactory {

  protected val profile: JdbcProfile
  val processesTable: LTableQuery[ProcessEntityFactory#ProcessEntity]

  import profile.api._

  class ProcessVersionEntity(tag: Tag) extends BaseProcessVersionEntity(tag) {

    def json = column[Option[String]]("json", O.Length(100 * 1000))

    def * = (id, processId, json, createDate, user, modelVersion) <> (
      ProcessVersionEntityData.apply _ tupled,
      ProcessVersionEntityData.unapply)

  }

  class ProcessVersionEntityNoJson(tag: Tag) extends BaseProcessVersionEntity(tag) {

    override def * =  (id, processId, createDate, user, modelVersion) <> (
      (ProcessVersionEntityData.apply(_: Long, _: Long, None, _: Timestamp, _: String, _: Option[Int])).tupled,
      (d: ProcessVersionEntityData) => ProcessVersionEntityData.unapply(d).map { t => (t._1, t._2, t._4, t._5, t._6) })

  }

  abstract class BaseProcessVersionEntity(tag: Tag) extends Table[ProcessVersionEntityData](tag, "process_versions") {

    def id: Rep[Long] = column[Long]("id", NotNull)

    def createDate: Rep[Timestamp] = column[Timestamp]("create_date", NotNull)

    def user: Rep[String] = column[String]("user", NotNull)

    def processId: Rep[Long] = column[Long]("process_id", NotNull)

    def modelVersion: Rep[Option[Int]] = column[Option[Int]]("model_version", NotNull)

    def pk = primaryKey("pk_process_version", (processId, id))

    private def process: ForeignKeyQuery[ProcessEntityFactory#ProcessEntity, ProcessEntityData] = foreignKey("process-version-process-fk", processId, processesTable)(
      _.id,
      onUpdate = ForeignKeyAction.Cascade,
      onDelete = ForeignKeyAction.Cascade
    )
  }

  val processVersionsTable: TableQuery[ProcessVersionEntityFactory#ProcessVersionEntity] =
    LTableQuery(new ProcessVersionEntity(_))

  val processVersionsTableNoJson: TableQuery[ProcessVersionEntityFactory#ProcessVersionEntityNoJson] =
    LTableQuery(new ProcessVersionEntityNoJson(_))
}

case class ProcessVersionEntityData(id: Long,
                                    processId: Long,
                                    json: Option[String],
                                    createDate: Timestamp,
                                    user: String,
                                    modelVersion: Option[Int]) {

  def toGraphProcess: GraphProcess = json match {
    case Some(j) => GraphProcess(j)
    case _ => throw new IllegalStateException(s"Scenario version has neither json. $this")
  }

  def toProcessVersion(processName: ProcessName): ProcessVersion = ProcessVersion(
    versionId = VersionId(id),
    processName = processName,
    processId = ProcessId(processId),
    user = user,
    modelVersion = modelVersion
  )
}

