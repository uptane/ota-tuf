import com.jsuereth.sbtpgp.SbtPgp.autoImport.usePgpKeyHex

import sbt.Keys._
import sbt._
import xerial.sbt.Sonatype.GitHubHosting
import xerial.sbt.Sonatype.autoImport._

object Publish {
  lazy val settings = Seq(
    // Remove all additional repository other than Maven Central from POM
    usePgpKeyHex("6ED5E5ABE9BF80F173343B98FFA246A21356D296"),
    isSnapshot := version.value.trim.endsWith("SNAPSHOT"),
    pomIncludeRepository := { _ => false },
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    publishTo := sonatypePublishToBundle.value,
    publishMavenStyle := true,
    sonatypeProjectHosting := Some(GitHubHosting("uptane", "ota-tuf", "releases@uptane.github.io"))
  )

  lazy val disable = Seq(
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeProfileName := "io.github.uptane",
    publish / skip := true,
    publishArtifact := false,
    publish := (()),
    publishTo := None,
    publishLocal := (())
  )
}
