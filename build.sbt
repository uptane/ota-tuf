def itFilter(name: String): Boolean = name.endsWith("IntegrationSpec")

def unitFilter(name: String): Boolean = !itFilter(name)

lazy val ItTest = config("it").extend(Test)

lazy val UnitTest = config("ut").extend(Test)

lazy val commonConfigs = Seq(ItTest, UnitTest)

val libatsVersion = "2.6.6"

lazy val commonDeps = libraryDependencies ++= {
  val scalaTestV = "3.2.19"
  lazy val catsV = "2.12.0"
  lazy val akkaHttpV = "10.5.2"
  lazy val enumeratumV = "1.7.4"

  Seq(
    "org.scala-lang.modules" %% "scala-async" % "1.0.1",
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided,
    "io.github.uptane" %% "libats" % libatsVersion,
    "org.scalatest" %% "scalatest" % scalaTestV % "test",
    "org.typelevel" %% "cats-core" % catsV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.beachape" %% "enumeratum" % enumeratumV,
    "com.beachape" %% "enumeratum-circe" % enumeratumV,
    "io.github.uptane" %% "libats-http" % libatsVersion
  )
}

lazy val serverDependencies = libraryDependencies ++= {
  lazy val akkaV = "2.8.5"
  lazy val akkaHttpV = "10.5.2"
  lazy val enumeratumV = "1.7.4"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaV % "test",
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % "test",
    "com.softwaremill.sttp.client" %% "akka-http-backend" % "2.3.0" % "test",
    "io.github.uptane" %% "libats-http" % libatsVersion,
    "io.github.uptane" %% "libats-http-tracing" % libatsVersion,
    "io.github.uptane" %% "libats-messaging" % libatsVersion,
    "io.github.uptane" %% "libats-metrics-akka" % libatsVersion,
    "io.github.uptane" %% "libats-metrics-prometheus" % libatsVersion,
    "io.github.uptane" %% "libats-slick" % libatsVersion,
    "io.github.uptane" %% "libats-logging" % libatsVersion,
    "org.mariadb.jdbc" % "mariadb-java-client" % "3.4.1",
    "com.beachape" %% "enumeratum" % enumeratumV,
    "com.beachape" %% "enumeratum-circe" % enumeratumV,
    "io.scalaland" %% "chimney" % "1.4.0"
  )
}

lazy val commonSettings = Seq(
  organization := "io.github.uptane",
  scalaVersion := "2.13.14",
  organizationName := "uptane",
  organizationHomepage := Some(url("https://uptane.github.io/")),
  scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-Xasync", "-Xsource:3"),
  Compile / console / scalacOptions ~= (_.filterNot(_ == "-Ywarn-unused-import")),
  resolvers += "sonatype-snapshots".at(
    "https://s01.oss.sonatype.org/content/repositories/snapshots"
  ),
  resolvers += "sonatype-releases".at("https://s01.oss.sonatype.org/content/repositories/releases"),
  licenses += ("MPL-2.0", url("http://mozilla.org/MPL/2.0/")),
  description := "scala tuf implementation support",
  buildInfoOptions += BuildInfoOption.ToMap,
  buildInfoOptions += BuildInfoOption.BuildTime
) ++
  Seq(inConfig(ItTest)(Defaults.testTasks): _*) ++
  Seq(inConfig(UnitTest)(Defaults.testTasks): _*) ++
  (UnitTest / testOptions := Seq(Tests.Filter(unitFilter))) ++
  (IntegrationTest / testOptions := Seq(Tests.Filter(itFilter))) ++
  Versioning.settings ++
  commonDeps

lazy val libtuf = (project in file("libtuf"))
  .enablePlugins(Versioning.Plugin, BuildInfoPlugin)
  .configs(commonConfigs: _*)
  .settings(commonSettings)
  .settings(Publish.settings)

lazy val libtuf_server = (project in file("libtuf-server"))
  .enablePlugins(Versioning.Plugin, BuildInfoPlugin)
  .configs(commonConfigs: _*)
  .settings(commonSettings)
  .settings(serverDependencies)
  .settings(Publish.settings)
  .dependsOn(libtuf)

lazy val keyserver = (project in file("keyserver"))
  .enablePlugins(BuildInfoPlugin, Versioning.Plugin, JavaAppPackaging)
  .configs(commonConfigs: _*)
  .settings(commonSettings)
  .settings(Packaging.docker("tuf-keyserver"))
  .settings(Publish.disable)
  .settings(serverDependencies)
  .settings(BuildInfoSettings("com.advancedtelematic.tuf.keyserver"))
  .dependsOn(libtuf)
  .dependsOn(libtuf_server)

lazy val reposerver = (project in file("reposerver"))
  .enablePlugins(BuildInfoPlugin, Versioning.Plugin, JavaAppPackaging)
  .configs(commonConfigs: _*)
  .settings(commonSettings)
  .settings(serverDependencies)
  .settings(Packaging.docker("tuf-reposerver"))
  .settings(Publish.disable)
  .settings(BuildInfoSettings("com.advancedtelematic.tuf.reposerver"))
  .dependsOn(libtuf)
  .dependsOn(libtuf_server)

lazy val tuf_server = (project in file("tuf-server"))
  .enablePlugins(BuildInfoPlugin, Versioning.Plugin, JavaAppPackaging)
  .configs(commonConfigs: _*)
  .settings(commonSettings)
  .settings(serverDependencies)
  .settings(Packaging.docker("tuf-server"))
  .settings(BuildInfoSettings("io.github.uptane.tuf.tuf_server"))
  .dependsOn(libtuf)
  .dependsOn(libtuf_server)
  .dependsOn(keyserver)
  .dependsOn(reposerver)

lazy val cli = (project in file("cli"))
  .enablePlugins(BuildInfoPlugin, Versioning.Plugin, JavaAppPackaging)
  .configs(commonConfigs: _*)
  .settings(commonSettings)
  .settings(Publish.disable)
  .settings(BuildInfoSettings("com.advancedtelematic.tuf.cli"))
  .settings(
    topLevelDirectory := Some("uptane-sign"),
    executableScriptName := "uptane-sign",
    Universal / mappings += (file("cli/LICENSE") -> "docs/LICENSE"),
    libraryDependencies += "com.typesafe" % "config" % "1.4.3" % Test
  )
  .dependsOn(libtuf)

lazy val ota_tuf = (project in file("."))
  .settings(Publish.disable)
  .settings(Release.settings(libtuf, libtuf_server, keyserver, reposerver))
  .aggregate(libtuf_server, libtuf, keyserver, reposerver, cli)
