import CustomSettings._
import java.nio.file.Files.{copy => fileCopy}
import java.nio.file.Paths.{get => createPath}
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

def itFilter(name: String): Boolean = name endsWith "IntegrationSpec"

def unitFilter(name: String): Boolean = !itFilter(name)

lazy val ItTest = config("it").extend(Test)

lazy val UnitTest = config("ut").extend(Test)

lazy val commonConfigs = Seq(ItTest, UnitTest)

lazy val commonDeps = libraryDependencies ++= {
  val scalaTestV = "3.2.9"
  lazy val libatsV = libatsVersion.value
  lazy val catsV = "2.6.1"

  Seq(
    "org.scala-lang.modules" %% "scala-async" % "0.9.6",
    "io.github.uptane" %% "libats" % libatsV,
    "org.scalatest" %% "scalatest" % scalaTestV % "test",
    "org.typelevel" %% "cats-core" % catsV,
  )
}

lazy val serverDependencies = libraryDependencies ++= {
  lazy val akkaV = "2.6.16"
  lazy val akkaHttpV = "10.1.12"
  lazy val libatsV = libatsVersion.value
  lazy val slickV = "3.2.0"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaV % "test",
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % "test",
    "com.softwaremill.sttp.client" %% "akka-http-backend" % "2.2.9" % "test",

    "io.github.uptane" %% "libats-http" % libatsV,
    "io.github.uptane" %% "libats-http-tracing" % libatsV,
    "io.github.uptane" %% "libats-messaging" % libatsV,
    "io.github.uptane" %% "libats-metrics-akka" % libatsV,
    "io.github.uptane" %% "libats-metrics-prometheus" % libatsV,
    "io.github.uptane" %% "libats-slick" % libatsV,
    "io.github.uptane" %% "libats-logging" % libatsV,
    "com.typesafe.slick" %% "slick" % slickV,
    "com.typesafe.slick" %% "slick-hikaricp" % slickV,
    "org.mariadb.jdbc" % "mariadb-java-client" % "2.7.4"
  )
}

lazy val commonSettings = Seq(
  organization := "io.github.uptane",
  scalaVersion := "2.12.14",
  organizationName := "uptane",
  organizationHomepage := Some(url("https://uptane.github.io/")),
  scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-Xexperimental", "-Ypartial-unification"),
  Compile / console / scalacOptions ~= (_.filterNot(_ == "-Ywarn-unused-import")),
  resolvers += Resolver.sonatypeRepo("releases"),
  libatsVersion := "0.4.0-18-g8d4141f",
  licenses += ("MPL-2.0", url("http://mozilla.org/MPL/2.0/")),
  description := "scala tuf implementation support",
  buildInfoOptions += BuildInfoOption.ToMap,
  buildInfoOptions += BuildInfoOption.BuildTime,
  dependencyCheckAssemblyAnalyzerEnabled := Some(false)) ++
  Seq(inConfig(ItTest)(Defaults.testTasks): _*) ++
  Seq(inConfig(UnitTest)(Defaults.testTasks): _*) ++
  (UnitTest / testOptions := Seq(Tests.Filter(unitFilter))) ++
  (IntegrationTest / testOptions := Seq(Tests.Filter(itFilter))) ++
  Versioning.settings ++
  commonDeps

lazy val sonarSettings = Seq(
  sonarProperties ++= Map(
    "sonar.projectName" -> "OTA Connect TUF",
    "sonar.projectKey" -> "ota-connect-tuf",
    "sonar.sources" -> "src/main/scala",
    "sonar.tests" -> "src/test/scala",
    "sonar.host.url" -> "http://sonar.in.here.com",
    "sonar.links.issue" -> "https://saeljira.it.here.com/projects/OTA/issues",
    "sonar.links.scm" -> "https://main.gitlab.in.here.com/olp/edge/ota/connect/back-end/ota-tuf",
    "sonar.links.ci" -> "https://main.gitlab.in.here.com/olp/edge/ota/connect/back-end/ota-tuf/pipelines",
    "sonar.language" -> "scala",
    "sonar.projectVersion" -> version.value,
    "sonar.modules" -> "libtuf,libtuf-server,keyserver,reposerver,cli",
    "libtuf.sonar.projectName" -> "OTA Connect Libtuf",
    "libtuf-server.sonar.projectName" -> "OTA Connect Libtuf Server",
    "keyserver.sonar.projectName" -> "OTA Connect TUF Keyserver",
    "reposerver.sonar.projectName" -> "OTA Connect TUF Repository Server",
    "cli.sonar.projectName" -> "OTA Connect TUF CLI (garage-sign)",
  )
)

lazy val libtuf = (project in file("libtuf"))
  .enablePlugins(BuildInfoPlugin, Versioning.Plugin)
  .configs(commonConfigs:_*)
  .settings(commonSettings)
  .settings(Publish.settings)

lazy val libtuf_server = (project in file("libtuf-server"))
  .enablePlugins(BuildInfoPlugin, Versioning.Plugin)
  .configs(commonConfigs:_*)
  .settings(commonSettings)
  .settings(serverDependencies)
  .settings(Publish.settings)
  .dependsOn(libtuf)

lazy val keyserver = (project in file("keyserver"))
  .enablePlugins(BuildInfoPlugin, Versioning.Plugin, JavaAppPackaging)
  .configs(commonConfigs:_*)
  .settings(commonSettings)
  .settings(Publish.disable)
  .settings(Packaging.docker("tuf-keyserver"))
  .settings(serverDependencies)
  .dependsOn(libtuf)
  .dependsOn(libtuf_server)

lazy val reposerver = (project in file("reposerver"))
  .enablePlugins(BuildInfoPlugin, Versioning.Plugin, JavaAppPackaging)
  .configs(commonConfigs:_*)
  .settings(commonSettings)
  .settings(serverDependencies)
  .settings(Publish.disable)
  .settings(Packaging.docker("tuf-reposerver"))
  .dependsOn(libtuf)
  .dependsOn(libtuf_server)

lazy val cli = (project in file("cli"))
  .enablePlugins(BuildInfoPlugin, Versioning.Plugin, JavaAppPackaging, S3ReleasePlugin)
  .configs(commonConfigs:_*)
  .settings(commonSettings)
  .settings(Publish.disable)
  .settings(
    topLevelDirectory := Some("garage-sign"),
    executableScriptName := "garage-sign",
    Universal / mappings += (file("cli/LICENSE") -> "docs/LICENSE"),
    s3Bucket := "ota-tuf-cli-releases",
    libraryDependencies += "com.typesafe" % "config" % "1.4.1" % Test,
    reinstallGarageSign := {
      val home = sys.env("HOME")
      val bin = sys.env("PATH")
                  .split(":")
                  .filter { p =>
                    val f = new File(p + "/garage-sign")
                    p.startsWith(home) && f.isFile && f.isOwnerExecutable
                  }
                  .head
      val targetDir = (new File(bin)).getParent

      stage.value
      val stagingDir = (Universal / stagingDirectory).value
      val files = (stagingDir ** "*").get
      files.foreach { file =>
        val p = file.getAbsolutePath
        if (file.isFile && p.length > stagingDir.absolutePath.length) {
          val relPath = p.substring(stagingDir.getAbsolutePath.length + 1)
          fileCopy(file.toPath, createPath(s"$targetDir/$relPath"), REPLACE_EXISTING)
        }
      }
      println(s"Done installing to $targetDir.")
    }
  )
  .dependsOn(libtuf)

lazy val ota_tuf = (project in file("."))
  .settings(scalaVersion := "2.12.14")
  .settings(Publish.disable)
  .settings(Release.settings(libtuf, libtuf_server, keyserver, reposerver))
  .aggregate(libtuf_server, libtuf, keyserver, reposerver, cli)
  .settings(sonarSettings)
  .settings(sonarScan / aggregate := false)

lazy val reinstallGarageSign = taskKey[Unit]("Reinstall garage-sign in a dir in the home directory")

