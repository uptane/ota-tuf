libraryDependencies ++= {
  Seq(
    "org.flywaydb" % "flyway-core" % "6.5.7"
  )
}

Compile / mainClass := Some("com.advancedtelematic.tuf.keyserver.Boot")

Revolver.settings

fork := true
