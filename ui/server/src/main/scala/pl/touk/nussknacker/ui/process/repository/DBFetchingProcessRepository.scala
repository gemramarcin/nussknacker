package pl.touk.nussknacker.ui.process.repository

import cats.Monad
import cats.data.OptionT
import cats.instances.future._
import com.typesafe.scalalogging.LazyLogging
import db.util.DBIOActionInstances.{DB, _}
import pl.touk.nussknacker.engine.api.process.{ProcessId, ProcessName, VersionId}
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.restmodel.process.ProcessingType
import pl.touk.nussknacker.restmodel.processdetails._
import pl.touk.nussknacker.ui.db.entity._
import pl.touk.nussknacker.ui.db.{DateUtils, DbConfig}
import pl.touk.nussknacker.ui.process.marshall.ProcessConverter
import pl.touk.nussknacker.ui.process.repository.ProcessDBQueryRepository.ProcessNotFoundError
import pl.touk.nussknacker.ui.security.api.LoggedUser

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds

object DBFetchingProcessRepository {
  def create(dbConfig: DbConfig)(implicit ec: ExecutionContext) =
    new DBFetchingProcessRepository[Future](dbConfig) with BasicRepository
}

abstract class DBFetchingProcessRepository[F[_]: Monad](val dbConfig: DbConfig) extends FetchingProcessRepository[F] with LazyLogging {

  import api._

  override def fetchProcesses[PS: ProcessShapeFetchStrategy](isSubprocess: Option[Boolean], isArchived: Option[Boolean],
                                                             isDeployed: Option[Boolean], categories: Option[Seq[String]], processingTypes: Option[Seq[String]])
                                                            (implicit loggedUser: LoggedUser, ec: ExecutionContext): F[List[BaseProcessDetails[PS]]] = {

    val expr: List[Option[ProcessEntityFactory#ProcessEntity => Rep[Boolean]]] = List(
      isSubprocess.map(arg => process => process.isSubprocess === arg),
      isArchived.map(arg => process => process.isArchived === arg),
      categories.map(arg => process => process.processCategory.inSet(arg)),
      processingTypes.map(arg => process => process.processingType.inSet(arg))
    )

    run(fetchProcessDetailsByQueryAction({ process =>
      expr.flatten.foldLeft(true: Rep[Boolean])((x, y) => x && y(process))
    }, isDeployed))
  }

  override def fetchProcessesDetails[PS: ProcessShapeFetchStrategy]()(implicit loggedUser: LoggedUser, ec: ExecutionContext): F[List[BaseProcessDetails[PS]]] = {
    run(fetchProcessDetailsByQueryActionUnarchived(p => !p.isSubprocess))
  }

  override def fetchDeployedProcessesDetails[PS: ProcessShapeFetchStrategy]()(implicit loggedUser: LoggedUser, ec: ExecutionContext): F[List[BaseProcessDetails[PS]]] =
    run(fetchProcessDetailsByQueryActionUnarchived(p => !p.isSubprocess, Option(true)))

  override def fetchProcessesDetails[PS: ProcessShapeFetchStrategy](processNames: List[ProcessName])
                                                                   (implicit loggedUser: LoggedUser, ec: ExecutionContext): F[List[BaseProcessDetails[PS]]] = {
    val processNamesSet = processNames.map(_.value).toSet
    run(fetchProcessDetailsByQueryActionUnarchived(p => !p.isSubprocess && p.name.inSet(processNamesSet)))
  }

  override def fetchSubProcessesDetails[PS: ProcessShapeFetchStrategy]()(implicit loggedUser: LoggedUser, ec: ExecutionContext): F[List[BaseProcessDetails[PS]]] = {
    run(fetchProcessDetailsByQueryActionUnarchived(p => p.isSubprocess))
  }

  override def fetchAllProcessesDetails[PS: ProcessShapeFetchStrategy]()(implicit loggedUser: LoggedUser, ec: ExecutionContext): F[List[BaseProcessDetails[PS]]] = {
    run(fetchProcessDetailsByQueryActionUnarchived(_ => true))
  }

  private def fetchProcessDetailsByQueryActionUnarchived[PS: ProcessShapeFetchStrategy](query: ProcessEntityFactory#ProcessEntity => Rep[Boolean], isDeployed: Option[Boolean] = None)
                                                                                       (implicit loggedUser: LoggedUser, ec: ExecutionContext) =
    fetchProcessDetailsByQueryAction(e => query(e) && !e.isArchived, isDeployed)

  private def fetchProcessDetailsByQueryAction[PS: ProcessShapeFetchStrategy](query: ProcessEntityFactory#ProcessEntity => Rep[Boolean])
                                                                             (implicit loggedUser: LoggedUser, ec: ExecutionContext): api.DBIOAction[List[BaseProcessDetails[PS]], api.NoStream, Effect.All with Effect.Read] = {
    fetchProcessDetailsByQueryAction(query, None) //Back compatibility
  }

  private def fetchProcessDetailsByQueryAction[PS: ProcessShapeFetchStrategy](query: ProcessEntityFactory#ProcessEntity => Rep[Boolean],
                                                                              isDeployed: Option[Boolean])(implicit loggedUser: LoggedUser, ec: ExecutionContext): DBIOAction[List[BaseProcessDetails[PS]], NoStream, Effect.All with Effect.Read] = {
    (for {
      lastActionPerProcess <- fetchLastActionPerProcessQuery.result
      lastDeployedActionPerProcess <- fetchLastDeployedActionPerProcessQuery.result
      latestProcesses <- fetchLatestProcessesQuery(query, lastDeployedActionPerProcess, isDeployed).result
    } yield
      latestProcesses.map { case ((_, processVersion), process) => createFullDetails(
        process,
        processVersion,
        lastActionPerProcess.find(_._1 == process.id).map(_._2),
        lastDeployedActionPerProcess.find(_._1 == process.id).map(_._2),
        isLatestVersion = true
      )}).map(_.toList)
  }

  override def fetchLatestProcessDetailsForProcessId[PS: ProcessShapeFetchStrategy](id: ProcessId)(implicit loggedUser: LoggedUser, ec: ExecutionContext): F[Option[BaseProcessDetails[PS]]] = {
    run(fetchLatestProcessDetailsForProcessIdQuery(id))
  }

  override def fetchProcessDetailsForId[PS: ProcessShapeFetchStrategy](processId: ProcessId, versionId: VersionId)
                                                                      (implicit loggedUser: LoggedUser, ec: ExecutionContext): F[Option[BaseProcessDetails[PS]]] = {
    val action = for {
      latestProcessVersion <- OptionT[DB, ProcessVersionEntityData](fetchProcessLatestVersionsQuery(processId)(ProcessShapeFetchStrategy.NotFetch).result.headOption)
      processVersion <- OptionT[DB, ProcessVersionEntityData](fetchProcessLatestVersionsQuery(processId).filter(pv => pv.id === versionId).result.headOption)
      processDetails <- fetchProcessDetailsForVersion(processVersion, isLatestVersion = latestProcessVersion.id == processVersion.id)
    } yield processDetails
    run(action.value)
  }

  override def fetchProcessId(processName: ProcessName)(implicit ec: ExecutionContext): F[Option[ProcessId]] = {
    run(processesTable.filter(_.name === processName.value).map(_.id).result.headOption.map(_.map(id => id)))
  }

  def fetchProcessName(processId: ProcessId)(implicit ec: ExecutionContext): F[Option[ProcessName]] = {
    run(processesTable.filter(_.id === processId).map(_.name).result.headOption.map(_.map(ProcessName(_))))
  }

  override def fetchProcessDetails(processName: ProcessName)(implicit ec: ExecutionContext): F[Option[ProcessEntityData]] = {
    run(processesTable.filter(_.name === processName.value).result.headOption)
  }

  override def fetchProcessActions(processId: ProcessId)(implicit ec: ExecutionContext): F[List[ProcessAction]] =
    run(fetchProcessLatestActionsQuery(processId).result.map(_.toList.map(ProcessDBQueryRepository.toProcessAction)))

  override def fetchProcessingType(processId: ProcessId)(implicit user: LoggedUser, ec: ExecutionContext): F[ProcessingType] = {
    run {
      implicit val fetchStrategy: ProcessShapeFetchStrategy[_] = ProcessShapeFetchStrategy.NotFetch
      fetchLatestProcessDetailsForProcessIdQuery(processId).flatMap {
        case None => DBIO.failed(ProcessNotFoundError(processId.value.toString))
        case Some(process) => DBIO.successful(process.processingType)
      }
    }
  }

  private def fetchLatestProcessDetailsForProcessIdQuery[PS: ProcessShapeFetchStrategy](id: ProcessId)
                                                                                       (implicit loggedUser: LoggedUser, ec: ExecutionContext): DB[Option[BaseProcessDetails[PS]]] = {
    (for {
      latestProcessVersion <- OptionT[DB, ProcessVersionEntityData](fetchProcessLatestVersionsQuery(id).result.headOption)
      processDetails <- fetchProcessDetailsForVersion(latestProcessVersion, isLatestVersion = true)
    } yield processDetails).value
  }

  private def fetchProcessDetailsForVersion[PS: ProcessShapeFetchStrategy](processVersion: ProcessVersionEntityData, isLatestVersion: Boolean)
                                                                          (implicit loggedUser: LoggedUser, ec: ExecutionContext) = {
    val id = processVersion.processId
    for {
      process <- OptionT[DB, ProcessEntityData](processTableFilteredByUser.filter(_.id === id).result.headOption)
      processVersions <- OptionT.liftF[DB, Seq[ProcessVersionEntityData]](fetchProcessLatestVersionsQuery(id)(ProcessShapeFetchStrategy.NotFetch).result)
      actions <- OptionT.liftF[DB, Seq[(ProcessActionEntityData, Option[CommentEntityData])]](fetchProcessLatestActionsQuery(id).result)
      tags <- OptionT.liftF[DB, Seq[TagsEntityData]](tagsTable.filter(_.processId === process.id).result)
    } yield createFullDetails(
      process = process,
      processVersion = processVersion,
      lastActionData = actions.headOption,
      lastDeployedActionData = actions.headOption.find(_._1.isDeployed),
      isLatestVersion = isLatestVersion,
      tags = tags,
      history = processVersions.map(
        v => ProcessDBQueryRepository.toProcessVersion(v, actions.filter(p => p._1.processVersionId == v.id).toList)
      ),
    )
  }

  private def createFullDetails[PS: ProcessShapeFetchStrategy](process: ProcessEntityData,
                                                               processVersion: ProcessVersionEntityData,
                                                               lastActionData: Option[(ProcessActionEntityData, Option[CommentEntityData])],
                                                               lastDeployedActionData: Option[(ProcessActionEntityData, Option[CommentEntityData])],
                                                               isLatestVersion: Boolean,
                                                               tags: Seq[TagsEntityData] = List.empty,
                                                               history: Seq[ProcessVersion] = List.empty)(implicit loggedUser: LoggedUser): BaseProcessDetails[PS] = {
    BaseProcessDetails[PS](
      id = process.name, //TODO: replace by Long / ProcessId
      processId = process.id, //TODO: Remove it weh we will support Long / ProcessId
      name = process.name,
      processVersionId = processVersion.id,
      isLatestVersion = isLatestVersion,
      isArchived = process.isArchived,
      isSubprocess = process.isSubprocess,
      description = process.description,
      processingType = process.processingType,
      processCategory = process.processCategory,
      lastAction = lastActionData.map(ProcessDBQueryRepository.toProcessAction),
      lastDeployedAction = lastDeployedActionData.map(ProcessDBQueryRepository.toProcessAction),
      tags = tags.map(_.name).toList,
      modificationDate = DateUtils.toLocalDateTime(processVersion.createDate),
      modifiedAt = DateUtils.toLocalDateTime(processVersion.createDate),
      modifiedBy = processVersion.user,
      createdAt = DateUtils.toLocalDateTime(process.createdAt),
      createdBy = process.createdBy,
      json = convertToTargetShape(processVersion.json, process),
      history = history.toList,
      modelVersion = processVersion.modelVersion
    )
  }

  private def convertToTargetShape[PS: ProcessShapeFetchStrategy](maybeCanonical: Option[CanonicalProcess], process: ProcessEntityData): PS = {
    (maybeCanonical, implicitly[ProcessShapeFetchStrategy[PS]]) match {
      case (Some(canonical), ProcessShapeFetchStrategy.FetchCanonical) =>
        canonical.asInstanceOf[PS]
      case (Some(canonical), ProcessShapeFetchStrategy.FetchDisplayable) =>
        val displayableProcess = ProcessConverter.toDisplayableOrDie(canonical, process.processingType)
        displayableProcess.asInstanceOf[PS]
      case (_, ProcessShapeFetchStrategy.NotFetch) => ().asInstanceOf[PS]
      case (None, strategy) => throw new IllegalArgumentException(s"Missing scenario json data, it's required to convert for strategy: $strategy.")
    }
  }
}
