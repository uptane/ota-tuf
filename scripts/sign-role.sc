#!/usr/bin/env amm

import $ivy.`com.lihaoyi::ammonite-ops:2.4.0`
import $ivy.`io.github.uptane::libtuf:1.0.0`

import ammonite.ops._
// Ammonite script to encrypt private role keys for the key server database,
// needs the environment variables DB_ENCRYPTION_PASSWORD and DB_ENCRYPTION_SALT from key server,
// see https://confluence.in.here.com/pages/viewpage.action?pageId=972552231.

import $ivy.`org.bouncycastle:bcprov-jdk15on:1.66`

import java.security.Security
import java.util.Base64
import javax.crypto.{Cipher, SecretKeyFactory}
import javax.crypto.spec.{PBEKeySpec, PBEParameterSpec}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import scala.io.Source

@main
def main(inputPath: String, inKey: String) = {
  println(crypto.encrypt(Source.fromFile(secretKeyFilename).getLines.mkString))
}
