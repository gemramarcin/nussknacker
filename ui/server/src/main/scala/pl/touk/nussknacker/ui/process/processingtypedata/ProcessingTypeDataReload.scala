package pl.touk.nussknacker.ui.process.processingtypedata

import com.typesafe.scalalogging.LazyLogging
import pl.touk.nussknacker.engine.ProcessingTypeData
import pl.touk.nussknacker.restmodel.process.ProcessingType

trait ProcessingTypeDataReload {

  def reloadAll(): Unit

}

trait Initialization {

  def init(): Unit

}

/**
 * This implements *simplistic* reloading of ProcessingTypeData - treat it as experimental/working PoC
 *
 * One of the biggest issues is that it can break current operations - when reloadAll is invoked, e.g. during
 * process deploy via FlinkRestManager it may very well happen that http backed is closed between two Flink invocations.
 * To handle this correctly we probably need sth like:
 *   def withProcessingTypeData(processingType: ProcessingType)(action: ProcessingTypeData=>Future[T]): Future[T]
 * to be able to wait for all operations to complete
 *
 * Another thing that needs careful consideration is handling exception during ProcessingTypeData creation/closing - probably during
 * close we want to catch exception and try to proceed, but during creation it can be a bit tricky...
 */
class BasicProcessingTypeDataReload(loadMethod: () => ProcessingTypeDataProvider[ProcessingTypeData]) extends ProcessingTypeDataReload with LazyLogging {

  @volatile private var current: ProcessingTypeDataProvider[ProcessingTypeData] = loadMethod()

  override def reloadAll(): Unit = synchronized {
    logger.info("Closing old models")
    current.all.values.foreach(_.close())
    logger.info("Reloading scenario type data")
    current = loadMethod()
    logger.info("Scenario type data reloading finished")
  }
}

object BasicProcessingTypeDataReload {

  def wrapWithReloader(loadMethod: () => ProcessingTypeDataProvider[ProcessingTypeData]): (ProcessingTypeDataProvider[ProcessingTypeData], ProcessingTypeDataReload with Initialization) = {
    // must be lazy to avoid problems with dependency injection cycle - see NusskanckerDefaultAppRouter.create
    lazy val reloader = new BasicProcessingTypeDataReload(loadMethod)
    val provider = new ProcessingTypeDataProvider[ProcessingTypeData] {
      override def forType(typ: ProcessingType): Option[ProcessingTypeData] = reloader.current.forType(typ)

      override def all: Map[ProcessingType, ProcessingTypeData] = reloader.current.all
    }
    val lazyInitializedReloader = new ProcessingTypeDataReload with Initialization {
      override def init(): Unit = reloader

      override def reloadAll(): Unit = reloader.reloadAll()
    }
    (provider, lazyInitializedReloader)
  }

}



