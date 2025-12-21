package com.advancedtelematic.tuf.reposerver.util

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import com.advancedtelematic.libats.data.DataType.HashMethod
import com.advancedtelematic.libtuf.data.ClientDataType.{
  ClientHashes,
  ClientTargetItem,
  Delegations,
  TargetCustom,
  TargetsRole
}
import com.advancedtelematic.libtuf.data.TufDataType.{
  JsonSignedPayload,
  KeyType,
  RepoId,
  RoleType,
  RsaKeyType,
  SignedPayload,
  TargetFilename,
  TargetFormat,
  TargetName,
  TargetVersion
}
import com.advancedtelematic.libtuf_server.crypto.Sha256Digest
import com.advancedtelematic.libtuf_server.repo.client.ReposerverClient.RequestTargetItem
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.TargetItem
import com.advancedtelematic.tuf.reposerver.db.{SignedRoleRepositorySupport, TargetItemRepositorySupport}
import com.advancedtelematic.tuf.reposerver.http.{RoleChecksumHeader, TufRepoSignedRoleGeneration}
import eu.timepit.refined.api.Refined
import io.circe.Json
import org.scalatest.{Assertion, Suite}
import org.scalatest.concurrent.ScalaFutures
import cats.syntax.either._
import cats.syntax.show._
import cats.syntax.option._
import io.circe.syntax._
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport._
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libats.http.HttpCodecs._
import com.advancedtelematic.libats.data.ErrorRepresentation

import scala.concurrent.Future

trait RepoResourceSpecUtil
    extends ResourceSpec
    with SignedRoleRepositorySupport
    with TargetItemRepositorySupport
    with ScalaFutures
    with ScalatestRouteTest { this: Suite =>

  override val ec: scala.concurrent.ExecutionContextExecutor = this.executor

  def makeRoleChecksumHeader(repoId: RepoId) =
    RoleChecksumHeader(signedRoleRepository.find[TargetsRole](repoId).futureValue.checksum.hash)

  implicit class RequestOps(value: HttpRequest) {

    def withValidTargetsCheckSum(implicit repoId: RepoId): HttpRequest =
      value.withHeaders(makeRoleChecksumHeader(repoId))

  }

  val testFile = {
    val checksum = Sha256Digest.digest("hi".getBytes)
    RequestTargetItem(
      Uri("https://ats.com/testfile"),
      checksum,
      targetFormat = None,
      name = None,
      version = None,
      hardwareIds = Seq.empty,
      length = "hi".getBytes.length
    )
  }

  def addTargetToRepo(repoId: RepoId = RepoId.generate(),
                      keyType: KeyType = KeyType.default): RepoId = {
    fakeKeyserverClient.createRoot(repoId, keyType).futureValue

    Post(apiUri(s"repo/${repoId.show}/targets/myfile01"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      repoId
    }
  }

  def buildSignedTargetsRole(repoId: RepoId,
                             targets: Map[TargetFilename, ClientTargetItem],
                             version: Int = 2): SignedPayload[TargetsRole] = {
    val targetsRole = TargetsRole(Instant.now().plus(1, ChronoUnit.DAYS), targets, version)
    fakeKeyserverClient.sign(repoId, targetsRole).futureValue
  }

  def createOfflineTargets(filename: TargetFilename = offlineTargetFilename,
                           proprietary: Json = Json.obj()) = {
    val targetCustomJson =
      TargetCustom(
        TargetName("name"),
        TargetVersion("version"),
        Seq.empty,
        TargetFormat.BINARY.some
      ).asJson
        .deepMerge(Json.obj("uri" -> Uri("https://ats.com").asJson))
        .deepMerge(proprietary)

    val hashes: ClientHashes = Map(
      HashMethod.SHA256 -> Refined
        .unsafeApply("8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4")
    )

    Map(filename -> ClientTargetItem(hashes, 0, targetCustomJson.some))
  }

  val offlineTargetFilename: TargetFilename = Refined.unsafeApply("some/file/name")

  val offlineTargets = createOfflineTargets(offlineTargetFilename)

  private def normalizeCustomJson(custom: Option[Json]): Option[Json] = {
    custom.map { json =>
      json.asObject.map { obj =>
        Json.fromJsonObject(obj.remove("createdAt").remove("updatedAt"))
      }.getOrElse(json)
    }
  }

  def extractTargetsRoleFromError(errorRepr: ErrorRepresentation): TargetsRole = {
    val metadataBase64 = errorRepr.cause.flatMap(_.as[io.circe.JsonObject].toOption)
      .flatMap(_.apply("unsigned_metadata_base64"))
      .flatMap(_.asString)
      .getOrElse(throw new RuntimeException("Could not extract unsigned_metadata_base64 from error response"))
    val decodedJson = new String(java.util.Base64.getDecoder.decode(metadataBase64), java.nio.charset.StandardCharsets.UTF_8)
    io.circe.parser.parse(decodedJson).flatMap(_.as[TargetsRole]).valueOr(throw _)
  }

  def assertOfflineMetadataMatches(offlineMetadata: TargetsRole, onlineMetadata: TargetsRole): Assertion = {
    // Normalize targets by removing createdAt/updatedAt from custom JSON
    val normalizedOfflineTargets = offlineMetadata.targets.map { case (filename, item) =>
      filename -> item.copy(custom = normalizeCustomJson(item.custom))
    }
    val normalizedOnlineTargets = onlineMetadata.targets.map { case (filename, item) =>
      filename -> item.copy(custom = normalizeCustomJson(item.custom))
    }

    // Compare signed portions
    normalizedOfflineTargets shouldBe normalizedOnlineTargets
    offlineMetadata.delegations shouldBe onlineMetadata.delegations
    offlineMetadata.version shouldBe onlineMetadata.version
  }
}
