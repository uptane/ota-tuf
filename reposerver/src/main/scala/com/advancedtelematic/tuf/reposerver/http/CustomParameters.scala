package com.advancedtelematic.tuf.reposerver.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers.CsvSeq
import com.advancedtelematic.libtuf.data.ClientDataType.TargetCustom
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.TargetFormat
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, TargetFormat, TargetName, TargetVersion}
import io.circe.*
import akka.http.scaladsl.unmarshalling.*
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libats.data.ErrorCode
import com.advancedtelematic.libats.http.RefinedMarshallingSupport.*
import com.advancedtelematic.libats.http.AnyvalMarshallingSupport.*
import com.advancedtelematic.libats.http.Errors.RawError

import scala.collection.immutable
import com.advancedtelematic.libtuf_server.data.Marshalling.targetFormatFromStringUnmarshaller

object TargetCustomParameterExtractors {
  import akka.http.scaladsl.server.Directives._
  import akka.http.scaladsl.server._
  import com.advancedtelematic.libats.http.RefinedMarshallingSupport._

  val all: Directive1[TargetCustom] =
    parameters(
      Symbol("name").as[TargetName],
      Symbol("version").as[TargetVersion],
      Symbol("hardwareIds").as(CsvSeq[HardwareIdentifier]).?(immutable.Seq.empty[HardwareIdentifier]),
      Symbol("targetFormat").as[TargetFormat].?,
    ).tmap { case (name, version, hardwareIds, targetFormat) =>
      TargetCustom(name, version, hardwareIds, targetFormat.orElse(Some(TargetFormat.BINARY)))
    }
}
object CustomParameterUnmarshallers {
  val nonNegativeLong: Unmarshaller[String, Long] =
    PredefinedFromStringUnmarshallers.longFromStringUnmarshaller
    .flatMap {
    ec => mat => value =>
    if (value < 0) FastFuture.failed (RawError (ErrorCode("Bad Request"), StatusCodes.BadRequest, "Value cannot be negative") )
    else FastFuture.successful (value)
  }
}
object PaginationParams {
  implicit class PaginationResultOps(x: Option[Long]) {
    def orDefaultOffset: Long = x.getOrElse(0L)
    def orDefaultLimit: Long = x.getOrElse(50L)
  }
}
