libraryDependencies ++=
  Seq("software.amazon.awssdk" % "s3" % "2.59.8", "software.amazon.awssdk" % "sts" % "2.59.11")

Compile / mainClass := Some("com.advancedtelematic.tuf.reposerver.Boot")

fork := true
