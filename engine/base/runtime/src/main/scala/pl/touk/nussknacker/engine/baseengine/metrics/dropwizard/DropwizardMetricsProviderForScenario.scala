package pl.touk.nussknacker.engine.baseengine.metrics.dropwizard

import com.typesafe.scalalogging.LazyLogging
import io.dropwizard.metrics5
import io.dropwizard.metrics5.{Metric, MetricName, MetricRegistry, SlidingTimeWindowReservoir}
import pl.touk.nussknacker.engine.util.metrics._
import pl.touk.nussknacker.engine.util.service.EspTimer

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._

class DropwizardMetricsProviderFactory(metricRegistry: MetricRegistry) extends (String => MetricsProviderForScenario with AutoCloseable) {
  override def apply(scenarioId: String): MetricsProviderForScenario with AutoCloseable = new DropwizardMetricsProviderForScenario(scenarioId, metricRegistry)
}

class DropwizardMetricsProviderForScenario(scenarioId: String, metricRegistry: MetricRegistry) extends MetricsProviderForScenario with AutoCloseable with LazyLogging {

  override def espTimer(metricIdentifier: MetricIdentifier, instantTimerWindowInSeconds: Long = 10): EspTimer = {
    val histogramInstance = histogram(metricIdentifier.withNameSuffix(EspTimer.histogramSuffix))
    val meter = register(metricIdentifier.withNameSuffix(EspTimer.instantRateSuffix), new InstantRateMeter with metrics5.Gauge[Double])
    EspTimer(meter, histogramInstance)
  }

  override def counter(metricIdentifier: MetricIdentifier): Counter = {
    val counter = register(metricIdentifier, new metrics5.Counter)
    counter.inc _
  }

  override def histogram(metricIdentifier: MetricIdentifier, instantTimerWindowInSeconds: Long = 10): Histogram = {
    val histogram = register(metricIdentifier, new metrics5.Histogram(new SlidingTimeWindowReservoir(instantTimerWindowInSeconds, TimeUnit.SECONDS)))
    histogram.update _
  }

  //we want to be safe in concurrent conditions...
  def register[T <: Metric](id: MetricIdentifier, metric: T): T = {
    val metricName = MetricRegistry.name(id.name.head, id.name.tail: _*)
      .tagged(id.tags.asJava)
      .tagged("processId", scenarioId)
    try {
      metricRegistry.register(metricName, metric)
    } catch {
      case e: IllegalArgumentException if e.getMessage == "A metric named " + metricName + " already exists" =>
        logger.info(s"""Reusing existing metric for $metricName""")
        metricRegistry.getMetrics.get(metricName).asInstanceOf[T]
    }
  }

  override def registerGauge[T](metricIdentifier: MetricIdentifier, gauge: Gauge[T]): Unit = {
    register[metrics5.Gauge[T]](metricIdentifier, gauge.getValue _)
  }

  override def close(): Unit = {
    metricRegistry.removeMatching((name: MetricName, _: Metric) => name.getTags.get("processId") == scenarioId)
  }
}