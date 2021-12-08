package com.advancedtelematic.tuf.reposerver.http

import java.time.Instant
import org.scalatest.OptionValues._

import java.time.temporal.ChronoUnit
import akka.http.scaladsl.model.{StatusCodes, Uri}
import cats.syntax.option._
import cats.syntax.show._
import com.advancedtelematic.libats.data.ErrorRepresentation
import com.advancedtelematic.libtuf.crypt.CanonicalJson._
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.DelegatedPathPattern._
import com.advancedtelematic.libtuf.data.ClientDataType.{DelegatedPathPattern, DelegatedRoleName, Delegation, Delegations, SnapshotRole, TargetsRole}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, JsonSignedPayload, RepoId, SignedPayload, TufKey, TufKeyPair}
import com.advancedtelematic.libtuf.data.ValidatedString._
import com.advancedtelematic.libtuf_server.repo.server.RepoRoleRefresh
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.AddDelegationFromRemoteRequest
import com.advancedtelematic.tuf.reposerver.util.{RepoResourceSpecUtil, ResourceSpec, TufReposerverSpec}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import eu.timepit.refined.api.Refined
import io.circe.syntax._
import org.scalactic.source.Position

import scala.concurrent.Future
import com.advancedtelematic.tuf.reposerver.data.RepoCodecs._
import io.circe.Json

import java.util.UUID

class RepoResourceDelegationsSpec extends TufReposerverSpec
  with ResourceSpec
  with RepoResourceSpecUtil {

  lazy val keyPair = Ed25519KeyType.crypto.generateKeyPair()

  val delegatedRoleName = "mydelegation".unsafeApply[DelegatedRoleName]

  val delegation = {
    val delegationPath = "mypath/*".unsafeApply[DelegatedPathPattern]
    Delegation(delegatedRoleName, List(keyPair.pubkey.id), List(delegationPath))
  }

  implicit val roleRefresh = new RepoRoleRefresh(fakeKeyserverClient, new TufRepoSignedRoleProvider(), new TufRepoTargetItemsProvider())

  val delegations = Delegations(Map(keyPair.pubkey.id -> keyPair.pubkey), List(delegation))

  private def uploadOfflineSignedTargetsRole(_delegations: Delegations = delegations)
                                  (implicit repoId: RepoId, pos: Position): Unit = {
    val signedTargets = buildSignedTargetsRoleWithDelegations(_delegations)(repoId, pos)
    Put(apiUri(s"repo/${repoId.show}/targets"), signedTargets).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  private def buildDelegations(pubKey: TufKey, roleName: String = "mydelegation", pattern: String="mypath/*"): Delegations = {
    val delegation = {
      val delegationPath = pattern.unsafeApply[DelegatedPathPattern]
      val delegatedRoleName = roleName.unsafeApply[DelegatedRoleName]
      Delegation(delegatedRoleName, List(pubKey.id), List(delegationPath))
    }
    Delegations(Map(pubKey.id -> pubKey), List(delegation))
  }

  private def buildSignedTargetsRoleWithDelegations(_delegations: Delegations = delegations)
                                                   (implicit repoId: RepoId, pos: Position): Future[JsonSignedPayload] = {
    val oldTargets = buildSignedTargetsRole(repoId, Map.empty)
    val newTargets = oldTargets.signed.copy(delegations = _delegations.some)

    fakeKeyserverClient.sign(repoId, newTargets).map(_.asJsonSignedPayload)
  }

  private def buildSignedDelegatedTargets(delegatedKeyPair: TufKeyPair = keyPair, version: Int = 2)
                                         (implicit repoId: RepoId, pos: Position): SignedPayload[TargetsRole] = {
    val delegationTargets = TargetsRole(Instant.now().plus(30, ChronoUnit.DAYS), targets = Map.empty, version = version)
    val signature = TufCrypto.signPayload(delegatedKeyPair.privkey, delegationTargets.asJson).toClient(delegatedKeyPair.pubkey.id)
    SignedPayload(List(signature), delegationTargets, delegationTargets.asJson)
  }

  private def pushSignedDelegatedMetadata(signedPayload: SignedPayload[TargetsRole])
                                         (implicit repoId: RepoId): RouteTestResult = {
    Put(apiUri(s"repo/${repoId.show}/delegations/${delegatedRoleName.value}.json"), signedPayload) ~> routes
  }

  private def pushSignedDelegatedMetadataOk(signedPayload: SignedPayload[TargetsRole])
                                           (implicit repoId: RepoId): Unit =
    pushSignedDelegatedMetadata(signedPayload) ~> check {
      status shouldBe StatusCodes.NoContent
    }

  private def pushSignedTargetsMetadata(signedPayload: JsonSignedPayload)
                                        (implicit repoId: RepoId): RouteTestResult = {
    Put(apiUri(s"repo/${repoId.show}/targets"), signedPayload).withValidTargetsCheckSum ~> routes
  }

  private def addNewTrustedDelegations(delegations: Delegation*)
                                      (implicit repoId: RepoId): RouteTestResult = {
    Put(apiUri(s"repo/${repoId.show}/trusted-delegations"), delegations.asJson) ~> routes
  }

  private def addNewTrustedDelegationsOk(delegations: Delegation*)
                                      (implicit repoId: RepoId): Unit = {
    addNewTrustedDelegations(delegations:_*) ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  private def addNewRemoteDelegation(req: AddDelegationFromRemoteRequest)
                                    (implicit  repoId: RepoId, pos: Position): RouteTestResult = {
    Put(apiUri(s"repo/${repoId.show}/remote-delegations"), req.asJson) ~> routes
  }

  private def addNewRemoteDelegationOk(req: AddDelegationFromRemoteRequest)
                                    (implicit  repoId: RepoId, pos: Position): Unit =
    addNewRemoteDelegation(req) ~> check {
      status shouldBe StatusCodes.Created
    }

  private def getDelegationOk(delegationName: DelegatedRoleName)
                             (implicit  repoId: RepoId): SignedPayload[TargetsRole] =
    Get(apiUri(s"repo/${repoId.show}/delegations/${delegationName.value}.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]]
    }

  private def addNewTrustedDelegationKeysOK(tufKeys: TufKey*)
                                         (implicit repoId: RepoId): Unit = {
    Put(apiUri(s"repo/${repoId.show}/trusted-delegations/keys"), tufKeys.asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  private def getTrustedDelegationsOk()(implicit repoId: RepoId, pos: Position): List[Delegation] = {
    Get(apiUri(s"repo/${repoId.show}/trusted-delegations")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[List[Delegation]]
    }
  }

  private def getTrustedDelegationKeys()(implicit repoId: RepoId): RouteTestResult = {
    Get(apiUri(s"repo/${repoId.show}/trusted-delegations/keys")) ~> routes
  }

  test("Rejects trusted delegations without reference keys") {
    implicit val repoId = addTargetToRepo()
    addNewTrustedDelegations(delegation) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.InvalidTrustedDelegations
    }
  }

  keyTypeTest("Accepts trusted delegation keys") { keyType =>
    implicit val repoId = addTargetToRepo()
    val newKeys = keyType.crypto.generateKeyPair()
    addNewTrustedDelegationKeysOK(newKeys.pubkey)
  }

  keyTypeTest("Accepts trusted delegation keys idempotent") { keyType =>
    implicit val repoId = addTargetToRepo()
    val newKeys = keyType.crypto.generateKeyPair()
    addNewTrustedDelegationKeysOK(newKeys.pubkey)

    addNewTrustedDelegationKeysOK(newKeys.pubkey)
  }

  keyTypeTest("Accepts trusted delegations using trusted keys") { keyType =>
    implicit val repoId = addTargetToRepo()
    val newKeys = keyType.crypto.generateKeyPair()
    addNewTrustedDelegationKeysOK(newKeys.pubkey)

    // add the trusted delegation referencing the newly trusted key
    addNewTrustedDelegationsOk(delegation.copy(keyids = List(newKeys.pubkey.id)))
  }

  keyTypeTest("Gets trusted delegations") { keyType =>
    implicit val repoId = addTargetToRepo()
    val newKeys = keyType.crypto.generateKeyPair()
    addNewTrustedDelegationKeysOK(newKeys.pubkey)

    // use the newly trusted keys
    val newTrustedDelegation = delegation.copy(keyids = List(newKeys.pubkey.id))
    addNewTrustedDelegationsOk(newTrustedDelegation)

    getTrustedDelegationsOk() should contain(newTrustedDelegation)
  }

  keyTypeTest("Gets trusted delegation keys") { keyType =>
    implicit val repoId = addTargetToRepo()
    val newKeys = keyType.crypto.generateKeyPair()
    addNewTrustedDelegationKeysOK(newKeys.pubkey)

    getTrustedDelegationKeys() ~> check {
      status shouldBe StatusCodes.OK
      responseAs[List[TufKey]] should contain(newKeys.pubkey)
    }
  }

  keyTypeTest("Replaces trusted delegation keys when offline targets uploaded") { keyType =>
    implicit val repoId = addTargetToRepo()
    val newKeys = keyType.crypto.generateKeyPair()
    val newDelegationsBlock = buildDelegations(newKeys.pubkey)
    uploadOfflineSignedTargetsRole(newDelegationsBlock)
    getTrustedDelegationKeys() ~> check {
      status shouldBe StatusCodes.OK
      // only items in published delegations should be present
      responseAs[List[TufKey]] shouldBe newDelegationsBlock.keys.values.toList
    }
  }

  test("Rejects trusted delegations using unknown keys") {
    implicit val repoId = addTargetToRepo()
    val newKeys = Ed25519KeyType.crypto.generateKeyPair()

    addNewTrustedDelegations(delegation.copy(keyids=List(newKeys.pubkey.id))) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.InvalidTrustedDelegations
    }
  }

  test("Rejects targets.json containing delegations that reference unknown keys") {
    implicit val repoId = addTargetToRepo()
    val newKeys = Ed25519KeyType.crypto.generateKeyPair()
    val signedTargets = buildSignedTargetsRoleWithDelegations(delegations.copy(roles = List(delegation.copy(keyids = List(newKeys.pubkey.id)))))

    val targetsRole = signedTargets.futureValue
    pushSignedTargetsMetadata(targetsRole) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.InvalidOfflineTargets
    }
  }

  test("accepts delegated targets") {
    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole()

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed.delegations should contain(delegations)
    }
  }

  test("accepts delegated role metadata when signed with known keys") {
    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole()

    val signedDelegation = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegation)
  }

  test("accepts overwrite of existing delegated role metadata") {
    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole()

    val signedDelegation = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegation)

    pushSignedDelegatedMetadataOk(signedDelegation)
  }

  test("returns delegated role metadata") {
    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    Get(apiUri(s"repo/${repoId.show}/delegations/${delegatedRoleName.value}.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].asJson shouldBe signedDelegationRole.asJson
    }
  }

  test("rejects delegated metadata when not defined in targets.json") {
    implicit val repoId = addTargetToRepo()

    val signedDelegation = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadata(signedDelegation) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe Errors.DelegationNotDefined.code
    }
  }

  test("rejects delegated metadata when not signed according to threshold") {
    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole(delegations.copy(roles = List(delegation.copy(threshold = 2))))

    val signedDelegation = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadata( signedDelegation) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.PayloadSignatureInvalid
    }
  }

  test("does not allow repeated signatures to check threshold") {
    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole(delegations.copy(roles = List(delegation.copy(threshold = 2))))

    val default = buildSignedDelegatedTargets()
    val signedDelegation = SignedPayload(default.signatures.head +: default.signatures, default.signed, default.json)

    pushSignedDelegatedMetadata(signedDelegation) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.PayloadSignatureInvalid
    }
  }

  test("rejects delegated metadata when not properly signed") {
    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole()

    val otherKey = Ed25519KeyType.crypto.generateKeyPair()
    val delegationTargets = TargetsRole(Instant.now().plus(30, ChronoUnit.DAYS), targets = Map.empty, version = 2)
    val signature = TufCrypto.signPayload(otherKey.privkey, delegationTargets.asJson).toClient(otherKey.pubkey.id)
    val signedDelegation = SignedPayload(List(signature), delegationTargets, delegationTargets.asJson)

    pushSignedDelegatedMetadata(signedDelegation) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.PayloadSignatureInvalid
    }
  }

  test("re-generates snapshot role after storing delegations") {
    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole()

    Get(apiUri(s"repo/${repoId.show}/snapshot.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[SnapshotRole]].signed.version shouldBe 2
    }
  }

  test("SnapshotRole includes signed delegation length") {
    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    val delegationRole =
      Get(apiUri(s"repo/${repoId.show}/delegations/${delegatedRoleName.value}.json")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }

    val delegationLength = delegationRole.asJson.canonical.length

    Get(apiUri(s"repo/${repoId.show}/snapshot.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val signed = responseAs[SignedPayload[SnapshotRole]].signed
      signed.meta(Refined.unsafeApply(s"${delegatedRoleName.value}.json")).length shouldBe delegationLength
    }
  }

  test("automatically renewed snapshot still contains delegation") {
    val signedRoleGeneration = TufRepoSignedRoleGeneration(fakeKeyserverClient)

    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    val delegationRole =
      Get(apiUri(s"repo/${repoId.show}/delegations/${delegatedRoleName.value}.json")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }

    val delegationLength = delegationRole.asJson.canonical.length

    val oldSnapshots = signedRoleGeneration.findRole[SnapshotRole](repoId).futureValue

    signedRoleRepository.persist[SnapshotRole](repoId, oldSnapshots.copy(expiresAt = Instant.now().minusSeconds(60)), forceVersion = true).futureValue

    val renewedSnapshots = signedRoleRepository.find[SnapshotRole](repoId).futureValue
    renewedSnapshots.role.meta(Refined.unsafeApply(s"${delegatedRoleName.value}.json")).length shouldBe delegationLength
  }

  test("Adding a single target keeps delegations") {
    implicit val repoId = addTargetToRepo()

    uploadOfflineSignedTargetsRole()

    Post(apiUri(s"repo/${repoId.show}/targets/myfile"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed.delegations should contain(delegations)
    }
  }

  test("can add delegation using remote url") {
    implicit val repoId = addTargetToRepo()
    val uri = Uri(s"https://test/mydelegation-${UUID.randomUUID()}")

    val signedDelegation = buildSignedDelegatedTargets()

    fakeRemoteDelegationClient.setRemote(uri, signedDelegation.asJson)

    val req = AddDelegationFromRemoteRequest(uri, delegation.name)

    addNewTrustedDelegationKeysOK(keyPair.pubkey)

    addNewTrustedDelegationsOk(delegation)

    addNewRemoteDelegationOk(req)

    val savedDelegation = getDelegationOk(delegation.name)
    savedDelegation.json shouldBe signedDelegation.json
  }

  test("can overwrite delegation using remote url") {
    implicit val repoId = addTargetToRepo()

    val uri1 = Uri(s"https://test/mydelegation-${UUID.randomUUID()}")
    val uri2 = Uri(s"https://test/mydelegation-${UUID.randomUUID()}")

    val signedDelegation1 = buildSignedDelegatedTargets()
    val signedDelegation2 = buildSignedDelegatedTargets(version = 3)

    fakeRemoteDelegationClient.setRemote(uri1, signedDelegation1.asJson)
    fakeRemoteDelegationClient.setRemote(uri2, signedDelegation2.asJson)

    addNewTrustedDelegationKeysOK(keyPair.pubkey)

    addNewTrustedDelegationsOk(delegation)

    val req1 = AddDelegationFromRemoteRequest(uri1, delegation.name)
    addNewRemoteDelegationOk(req1)

    val savedDelegation = getDelegationOk(delegation.name)
    savedDelegation.json shouldBe signedDelegation1.json

    val req2 = AddDelegationFromRemoteRequest(uri2, delegation.name)
    addNewRemoteDelegationOk(req2)
  }

  test("can update delegation using the configured URL") {
    implicit val repoId = addTargetToRepo()

    val uri = Uri(s"https://test/mydelegation-${UUID.randomUUID()}")

    val signedDelegation1 = buildSignedDelegatedTargets()
    val signedDelegation2 = buildSignedDelegatedTargets(version = 3)

    fakeRemoteDelegationClient.setRemote(uri, signedDelegation1.asJson, Map("myheader" -> "myval2"))

    addNewTrustedDelegationKeysOK(keyPair.pubkey)

    addNewTrustedDelegationsOk(delegation)

    val req1 = AddDelegationFromRemoteRequest(uri, delegation.name, Map("myheader" -> "myval2").some)
    addNewRemoteDelegationOk(req1)

    val savedDelegation1 = getDelegationOk(delegation.name)
    savedDelegation1.json shouldBe signedDelegation1.json

    fakeRemoteDelegationClient.setRemote(uri, signedDelegation2.asJson, Map("myheader" -> "myval2"))

    Put(apiUri(s"repo/${repoId.show}/remote-delegations/${delegation.name.value}/refresh")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val savedDelegation2 = getDelegationOk(delegation.name)
    savedDelegation2.json shouldBe signedDelegation2.json
  }

  test("retrieves when the delegation was last fetched (headers)") {
    implicit val repoId = addTargetToRepo()

    val uri = Uri(s"https://test/mydelegation-${UUID.randomUUID()}")

    val signedDelegation1 = buildSignedDelegatedTargets()

    fakeRemoteDelegationClient.setRemote(uri, signedDelegation1.asJson)

    addNewTrustedDelegationKeysOK(keyPair.pubkey)

    addNewTrustedDelegationsOk(delegation)

    val start = Instant.now

    val req1 = AddDelegationFromRemoteRequest(uri, delegation.name)
    addNewRemoteDelegationOk(req1)

    Get(apiUri(s"repo/${repoId.show}/delegations/${delegation.name.value}.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].json shouldBe signedDelegation1.json

      val tsStr = header("x-ats-delegation-last-fetched-at").value.value()
      val ts = Instant.parse(tsStr)

      ts.isAfter(start) shouldBe true
    }
  }

  test("can delete a trusted delegation") {
    implicit val repoId = addTargetToRepo()

    addNewTrustedDelegationKeysOK(keyPair.pubkey)

    addNewTrustedDelegationsOk(delegation)

    Delete(apiUri(s"repo/${repoId.show}/trusted-delegations/${delegation.name.value}")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val delegations = getTrustedDelegationsOk()
    delegations.map(_.name) shouldBe empty
  }

  test("refresh returns error when there is no remote uri for delegation") {
    implicit val repoId = addTargetToRepo()

    val signedDelegation = buildSignedDelegatedTargets()

    addNewTrustedDelegationKeysOK(keyPair.pubkey)

    addNewTrustedDelegationsOk(delegation)

    pushSignedDelegatedMetadataOk(signedDelegation)

    Put(apiUri(s"repo/${repoId.show}/remote-delegations/${delegation.name.value}/refresh")) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.MissingRemoteDelegationUri
    }
  }

  test("handles remote server 404 errors gracefully") {
    implicit val repoId = addTargetToRepo()

    val uri = Uri(s"https://test/mydelegation-${UUID.randomUUID()}")

    addNewTrustedDelegationKeysOK(keyPair.pubkey)

    addNewTrustedDelegationsOk(delegation)

    val req = AddDelegationFromRemoteRequest(uri, delegation.name)

    addNewRemoteDelegation(req) ~> check {
      status shouldBe StatusCodes.BadGateway
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.DelegationRemoteFetchFailed
    }
  }

  test("handles remote server parsing errors gracefully") {
    implicit val repoId = addTargetToRepo()

    val uri = Uri(s"https://test/mydelegation-${UUID.randomUUID()}")

    addNewTrustedDelegationKeysOK(keyPair.pubkey)

    addNewTrustedDelegationsOk(delegation)

    fakeRemoteDelegationClient.setRemote(uri, Json.obj())

    val req = AddDelegationFromRemoteRequest(uri, delegation.name)

    addNewRemoteDelegation(req) ~> check {
      status shouldBe StatusCodes.BadGateway
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.DelegationRemoteParseFailed
    }
  }

  test("uses supplied headers to fetch remote uri") {
    implicit val repoId = addTargetToRepo()

    val uri = Uri(s"https://test/mydelegation-${UUID.randomUUID()}")

    val signedDelegation = buildSignedDelegatedTargets()

    fakeRemoteDelegationClient.setRemote(uri, signedDelegation.asJson, Map("myheader" -> "myvalue"))

    addNewTrustedDelegationKeysOK(keyPair.pubkey)

    addNewTrustedDelegationsOk(delegation)

    val req = AddDelegationFromRemoteRequest(uri, delegation.name, Map("myheader" -> "myvalue").some)
    addNewRemoteDelegationOk(req)

    val savedDelegation = getDelegationOk(delegation.name)

    savedDelegation.json shouldBe signedDelegation.json
  }
}
