libraryDependencies ++=
  Seq(
    "com.amazonaws" % "aws-java-sdk-s3" % "1.12.797",
    "com.amazonaws" % "aws-java-sdk-sts" % "1.12.797"
  )

Compile / mainClass := Some("com.advancedtelematic.tuf.reposerver.Boot")

fork := true
