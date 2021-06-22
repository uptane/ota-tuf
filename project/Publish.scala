import java.net.URI

import sbt.Keys._
import sbt._

object Publish {

  lazy val settings = Seq(
    // Remove all additional repository other than Maven Central from POM
    pomIncludeRepository := { _ => false },
    publishTo := {
      val nexus = "https://s01.oss.sonatype.org/"
      if (version.value.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus + "content/repositories/snapshots")
      else Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    publishMavenStyle := true
  )

  lazy val disable = Seq(
    publishArtifact := false,
    publish := {},
    publishLocal := {}
  )
}
