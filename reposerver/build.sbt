libraryDependencies ++=
  Seq(
    "software.amazon.awssdk" % "s3" % "2.31.1",
    "software.amazon.awssdk" % "sts" % "2.31.1"
  )

Compile / mainClass := Some("com.advancedtelematic.tuf.reposerver.Boot")

fork := true
