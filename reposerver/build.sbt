libraryDependencies ++=
  Seq("software.amazon.awssdk" % "s3" % "2.59.5", "software.amazon.awssdk" % "sts" % "2.59.7")

Compile / mainClass := Some("com.advancedtelematic.tuf.reposerver.Boot")

fork := true
