libraryDependencies ++=
  Seq("software.amazon.awssdk" % "s3" % "2.31.78", "software.amazon.awssdk" % "sts" % "2.31.78")

Compile / mainClass := Some("com.advancedtelematic.tuf.reposerver.Boot")

fork := true
