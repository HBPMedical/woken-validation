/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.validation

import java.io.File

import com.typesafe.config.Config
import com.bugsnag.Bugsnag
import kamon.Kamon
import kamon.prometheus.PrometheusReporter
import kamon.sigar.SigarProvisioner
import kamon.system.SystemMetrics
import kamon.zipkin.ZipkinReporter
import org.hyperic.sigar.{ Sigar, SigarLoader }
import org.slf4j.LoggerFactory

import scala.util.Try

object KamonSupport {
  private val logger = LoggerFactory.getLogger("WokenValidation")

  def startKamonReporters(config: Config): Unit = {

    val bugsnag     = new Bugsnag("c023faf8a616d9f2847f539b6cf241a9")
    val kamonConfig = config.getConfig("kamon")

    if (kamonConfig.getBoolean("enabled") || kamonConfig.getBoolean("prometheus.enabled") || kamonConfig
          .getBoolean("zipkin.enabled")) {

      logger.info("Kamon configuration:")
      logger.info(config.getConfig("kamon").toString)
      logger.info(s"Start monitoring...")

      Kamon.reconfigure(config)

      val hostSystemMetrics = kamonConfig.getBoolean("system-metrics.host.enabled")
      if (hostSystemMetrics) {
        logger.info(s"Start Sigar metrics...")
        val _ = Try {
          val sigarLoader = new SigarLoader(classOf[Sigar])
          sigarLoader.load()
          logger.info("Sigar loaded.")
        }

        Try(
          SigarProvisioner.provision(
            new File(System.getProperty("user.home") + File.separator + ".native")
          )
        ).recover { case e: Exception => logger.warn("Cannot provision Sigar", e) }

        if (SigarProvisioner.isNativeLoaded)
          logger.info("Sigar metrics are available")
        else
          logger.warn("Sigar metrics are not available")
      }

      if (hostSystemMetrics || kamonConfig.getBoolean("system-metrics.jvm.enabled")) {
        logger.info(s"Start collection of system metrics...")
        SystemMetrics.startCollecting()
      }

      if (kamonConfig.getBoolean("prometheus.enabled")) {
        val _ = Kamon.addReporter(new PrometheusReporter)
        logger.info("Prometheus reporter started.")
      }

      if (kamonConfig.getBoolean("zipkin.enabled")) {
        val _ = Kamon.addReporter(new ZipkinReporter)
        logger.info("Zipkin Reporter started.")
      }
    }
  }

  def stopKamonReporters(): Unit = {
    SystemMetrics.stopCollecting()
    Kamon.stopAllReporters()
  }

}
