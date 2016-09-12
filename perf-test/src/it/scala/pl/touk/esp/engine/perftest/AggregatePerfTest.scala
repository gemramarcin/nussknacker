package pl.touk.esp.engine.perftest

import com.typesafe.scalalogging.LazyLogging
import org.scalatest._
import org.scalatest.concurrent.Eventually
import pl.touk.esp.engine.api.MetaData
import pl.touk.esp.engine.build.{EspProcessBuilder, GraphBuilder}
import pl.touk.esp.engine.graph.{EspProcess, param}
import pl.touk.esp.engine.kafka.{KafkaClient, KafkaConfig}
import pl.touk.esp.engine.perftest.AggregatePerfTest._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class AggregatePerfTest extends FlatSpec with BasePerfTest with Matchers  with Eventually with LazyLogging {

  import net.ceedubs.ficus.Ficus._
  import net.ceedubs.ficus.readers.ArbitraryTypeReader._
  import pl.touk.esp.engine.kafka.KafkaUtils._

  override protected def baseTestName = "agg"

  override protected def configCreatorClassName = "AggProcessConfigCreator"

  "simple process" should "has low memory footprint" in {
    val inTopic = processId + ".in"
    val outTopic = processId + ".out"
    val kafkaClient = prepareKafka(inTopic, outTopic)
    try {
      val outputStream = kafkaClient.createConsumer().consume(outTopic)

      val windowWidth = 1 second
      val slidesInWindow = 5
      val messagesInSlide = 10
      val threshold = slidesInWindow * messagesInSlide
      val parallelism = 8 // powinien być <= liczby slotów w innym przypadku się nie zadeployuje

      val process = prepareSimpleProcess(
        id = processId,
        inTopic = inTopic,
        outTopic = outTopic,
        slideWidth = windowWidth,
        slidesInWindow = slidesInWindow,
        threshold = threshold,
        parallelism = parallelism)

      withDeployedProcess(process) {
        val keys = 100
        val slides = 1000
        val inputCount = keys * slides * messagesInSlide
        val triggeredRatio = 0.01

        val groupSize = 100 * 1000
        val groupsCount = Math.ceil(inputCount.toDouble / groupSize).toInt

        val outputCount = (keys * triggeredRatio).toInt * (slides - (slidesInWindow - 1))

        val messagesStream =
          for {
            slide <- (0 until slides).view
            messageInSlide <- 0 until messagesInSlide
            keyIdx <- 1 to keys
          } yield {
            val timestamp = slide * windowWidth.toMillis + messageInSlide
            val value = if (keyIdx.toDouble / keys <= triggeredRatio) "1" else "0"
            val key = s"key$keyIdx"
            (key, s"$key|$value|$timestamp")
          }

        val (lastMessage, metrics, durationInMillis) = collectMetricsIn {
          for {
            t <- messagesStream.grouped(groupSize).zipWithIndex
            (group, idx) = t
          } {
            Future.sequence(group.map {
              case (key, content) =>
                kafkaClient.sendMessage(inTopic, key, content)
            }.force).map { _ ->
              logger.info(s"Group ${idx + 1} / $groupsCount sent")
            }
          }
          kafkaClient.producer.flush()

          outputStream
            .takeNthNonBlocking(outputCount)
            .futureValue(10 minutes)
        }

        logger.info(metrics.show)
        val usedMemoryInMB = metrics.memoryHistogram.percentile(95.0) / 1000 / 1000
        val msgsPerSecond = inputCount.toDouble / (durationInMillis.toDouble / 1000)
        logger.info(f"Throughput: $msgsPerSecond%.2f msgs/sec.")

        usedMemoryInMB should be < 500L
        msgsPerSecond should be > 10000.0
      }

    } finally {
      clearKafka(kafkaClient, inTopic, outTopic)
    }
  }

  private def prepareKafka(inTopic: String, outTopic: String): KafkaClient = {
    val kafkaConfig = config.as[KafkaConfig](s"$profile.kafka")
    val kafkaClient = new KafkaClient(kafkaConfig.kafkaAddress, kafkaConfig.zkAddress)
    kafkaClient.createTopic(inTopic, partitions = 8)
    kafkaClient.createTopic(outTopic, partitions = 1)
    kafkaClient
  }

  private def clearKafka(kafkaClient: KafkaClient, inTopic: String, outTopic: String) = {
    kafkaClient.deleteTopic(inTopic)
    kafkaClient.deleteTopic(outTopic)
    kafkaClient.shutdown()
  }

}

object AggregatePerfTest {
  import pl.touk.esp.engine.spel.Implicits._

  def prepareSimpleProcess(id: String,
                           inTopic: String,
                           outTopic: String,
                           slideWidth: FiniteDuration,
                           slidesInWindow: Int,
                           threshold: Int,
                           parallelism: Int) =
    EspProcessBuilder
      .id(id)
      .parallelism(parallelism)
      .exceptionHandler()
      .source("source", "kafka-keyvalue", "topic" -> inTopic)
      .aggregate(
        id = "aggregate", aggregatedVar = "input", keyExpression = "#input.key",
        duration = slideWidth * slidesInWindow, step = slideWidth, foldingFunRef = Some("sum"), triggerExpression = Some(s"#input == $threshold")
      )
      .sink("sink", "#input", "kafka-int", "topic" -> outTopic)

}