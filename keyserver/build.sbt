libraryDependencies ++= {
  Seq(
    "org.flywaydb" % "flyway-core" % "6.5.7"
  )
}

mainClass in Compile := Some("com.advancedtelematic.tuf.keyserver.Boot")

Revolver.settings

fork := true
