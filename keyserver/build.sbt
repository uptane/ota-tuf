libraryDependencies ++= {
  Seq(
    "org.flywaydb" % "flyway-core" % "7.13.0"
  )
}

Compile / mainClass := Some("com.advancedtelematic.tuf.keyserver.Boot")

Revolver.settings

fork := true
