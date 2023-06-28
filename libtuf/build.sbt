libraryDependencies ++= {
  val bouncyCastleV = "1.75"

  Seq(
    "org.bouncycastle" % "bcprov-jdk18on" % bouncyCastleV,
    "org.bouncycastle" % "bcpkix-jdk18on" % bouncyCastleV,
    "net.i2p" % "i2p" % "2.2.1",
    "com.softwaremill.sttp.client" %% "core" % "2.3.0",
    "com.softwaremill.sttp.client" %% "slf4j-backend" % "2.3.0",
    "com.softwaremill.sttp.client" %% "async-http-client-backend-future" % "2.3.0",
    "org.slf4j" % "slf4j-api" % "1.7.16" % "provided",
    "com.azure" % "azure-storage-blob" % "12.22.3",
    "com.azure" % "azure-identity" % "1.9.1"
  )
}
