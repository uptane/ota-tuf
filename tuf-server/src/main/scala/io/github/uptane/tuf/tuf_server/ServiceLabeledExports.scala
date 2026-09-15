package io.github.uptane.tuf.tuf_server

import com.codahale.metrics.MetricRegistry
import io.prometheus.client.Collector
import io.prometheus.client.Collector.MetricFamilySamples
import io.prometheus.client.Collector.MetricFamilySamples.Sample
import io.prometheus.client.dropwizard.DropwizardExports

class ServiceLabeledExports(sources: Seq[(String, MetricRegistry)]) extends Collector {
  private val sharedPrefix = "jvm"

  private val exporters: Seq[(String, DropwizardExports)] =
    sources.map { case (label, registry) => label -> new DropwizardExports(registry) }

  override def collect(): java.util.List[MetricFamilySamples] = {
    val byName = new java.util.LinkedHashMap[String, MetricFamilySamples]()

    exporters.foreach { case (label, exporter) =>
      exporter.collect().forEach { family =>
        if (family.name.startsWith(sharedPrefix)) {
          byName.putIfAbsent(family.name, family)
        } else {
          val target = byName.computeIfAbsent(
            family.name,
            _ => new MetricFamilySamples(family.name, family.`type`, family.help, new java.util.ArrayList[Sample]())
          )
          family.samples.forEach { s =>
            val names = new java.util.ArrayList[String](s.labelNames)
            names.add("tuf_service")
            val values = new java.util.ArrayList[String](s.labelValues)
            values.add(label)
            target.samples.add(new Sample(s.name, names, values, s.value))
          }
        }
      }
    }

    new java.util.ArrayList[MetricFamilySamples](byName.values)
  }
}
