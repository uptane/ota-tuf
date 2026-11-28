libraryDependencies ++=
  Seq("software.amazon.awssdk" % "s3" % "2.59.10", "software.amazon.awssdk" % "sts" % "2.59.10")

Compile / mainClass := Some("com.advancedtelematic.tuf.reposerver.Boot")

fork := true
