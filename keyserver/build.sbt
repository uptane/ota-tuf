libraryDependencies ++= {
  Seq(
    "org.flywaydb" % "flyway-core" % "6.0.8"
  )
}

Compile / mainClass := Some("com.advancedtelematic.tuf.keyserver.Boot")

Revolver.settings

fork := true
