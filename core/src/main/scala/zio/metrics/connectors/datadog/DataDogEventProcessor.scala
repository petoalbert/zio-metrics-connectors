package zio.metrics.connectors.datadog

import scala.collection.{immutable, mutable}

import zio._
import zio.internal.RingBuffer
import zio.metrics._
import zio.metrics.connectors.statsd.StatsdClient

object DataDogEventProcessor {

  def make(
    client: StatsdClient,
    queue: RingBuffer[(MetricKey[MetricKeyType.Histogram], Double)],
  ): ZIO[DatadogConfig, Nothing, Unit] =
    ZIO.service[DatadogConfig].flatMap { config =>
      ZIO
        .attempt {
          while (!queue.isEmpty()) {
            val items  = queue.pollUpTo(config.maxBatchedMetrics)
            val values = groupMap(items)(_._1)(_._2)
            values.foreach { case (key, value) =>
              val encoded = DatadogEncoder.encodeHistogramValues(key, NonEmptyChunk.fromChunk(value).get)
              client.send(encoded)
            }
          }
        }
        .ignoreLogged
        .schedule(Schedule.fixed(config.metricProcessingInterval))
        .forkDaemon
        .unit
    }

  // Backwards compatibility for 2.12
  private def groupMap[A, K, B](as: Chunk[A])(key: A => K)(f: A => B): Map[K, Chunk[B]] = {
    val m = mutable.Map.empty[K, mutable.Builder[B, Chunk[B]]]
    for (elem <- as) {
      val k       = key(elem)
      val builder = m.getOrElseUpdate(k, Chunk.newBuilder)
      builder += f(elem)
    }
    var result = immutable.Map.empty[K, Chunk[B]]
    m.foreach { case (k, v) =>
      result = result + ((k, v.result()))
    }
    result
  }
}
