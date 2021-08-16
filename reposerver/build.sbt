libraryDependencies ++= {
  Seq(
    "org.flywaydb" % "flyway-core" % "7.13.0",
    "com.amazonaws" % "aws-java-sdk-s3" % "1.11.338"
  )
}

Compile / mainClass := Some("com.advancedtelematic.tuf.reposerver.Boot")

Revolver.settings

fork := true

