libraryDependencies ++= {
  val bouncyCastleV = "1.70"

  Seq(
    "org.bouncycastle" % "bcprov-jdk15on" % bouncyCastleV,
    "org.bouncycastle" % "bcpkix-jdk15on" % bouncyCastleV,
    "net.i2p.crypto" % "eddsa" % "0.3.0",
    "com.softwaremill.sttp.client" %% "core" % "2.3.0",
    "com.softwaremill.sttp.client" %% "slf4j-backend" % "2.3.0",
    "com.softwaremill.sttp.client" %% "async-http-client-backend-future" % "2.3.0",
    "org.slf4j" % "slf4j-api" % "1.7.16" % "provided",
    "com.azure" % "azure-storage-blob" % "12.15.0",
    "com.azure" % "azure-identity" % "1.5.0"
  )
}
