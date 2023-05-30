
import com.typesafe.sbt.packager.docker._
import sbt.Keys._
import sbt._
import com.typesafe.sbt.SbtNativePackager.Docker
import DockerPlugin.autoImport._
import com.github.sbt.git.SbtGit.git
import com.typesafe.sbt.SbtNativePackager.autoImport._
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport._

object Packaging {
  def docker(distPackageName: String) = {
    Seq(

      Docker / dockerRepository := Some("advancedtelematic"),

      Docker / packageName := distPackageName,

      dockerUpdateLatest := true,

      dockerAliases ++= Seq(dockerAlias.value.withTag(git.gitHeadCommit.value)),

      Docker / defaultLinuxInstallLocation := s"/opt/${moduleName.value}",

      dockerBaseImage := "eclipse-temurin:17.0.3_7-jre-jammy",

      Docker / daemonUser := "daemon"
    )
  }
}
