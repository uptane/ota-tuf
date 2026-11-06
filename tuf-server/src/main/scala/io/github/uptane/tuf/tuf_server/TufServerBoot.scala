package io.github.uptane.tuf.tuf_server

import org.apache.pekko.actor.ActorSystem
import com.advancedtelematic.libats.boot.{VersionInfo, VersionInfoProvider}
import com.advancedtelematic.libats.http.BootAppDefaultConfig
import com.advancedtelematic.tuf.keyserver.KeyserverBoot
import com.advancedtelematic.tuf.keyserver.daemon.KeyserverDaemon
import com.advancedtelematic.tuf.reposerver.ReposerverBoot
import com.typesafe.config.{Config, ConfigFactory}
import io.prometheus.client.CollectorRegistry
import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.security.Security
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object TufServerBoot extends BootAppDefaultConfig with VersionInfo {
  override protected lazy val provider: VersionInfoProvider = AppBuildInfo

  def main(args: Array[String]): Unit = {
    Security.addProvider(new BouncyCastleProvider)

    // each service serves its own metrics on its own port, plus the jvm wide ones
    val keyserverRegistry = new CollectorRegistry()
    val reposerverRegistry = new CollectorRegistry()

    val keyserverDbConfig = globalConfig.getConfig("ats.keyserver.database")
    val keyserverActorSystem = ActorSystem("keyserver-actor-system")
    val keyserverBind =
      new KeyserverBoot(globalConfig, keyserverDbConfig, keyserverRegistry)(keyserverActorSystem).bind()

    val reposerverDbConfig = globalConfig.getConfig("ats.reposerver.database")
    val reposerverActorSystem = ActorSystem("reposerver-actor-system")
    val reposerverBind =
      new ReposerverBoot(globalConfig, reposerverDbConfig, reposerverRegistry)(reposerverActorSystem).bind()

    val bind = Future.sequence(List(keyserverBind, reposerverBind))

    Await.result(bind, Duration.Inf)
  }

}
