package com.advancedtelematic.tuf.reposerver.util

import java.time.Instant

import java.time.temporal.ChronoUnit
import akka.http.scaladsl.model.{StatusCodes, Uri}
import cats.syntax.option._
import cats.syntax.show._
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.DelegatedPathPattern._
import com.advancedtelematic.libtuf.data.ClientDataType.{DelegatedPathPattern, DelegatedRoleName, Delegation, Delegations, SnapshotRole, TargetsRole}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, JsonSignedPayload, RepoId, SignedPayload, TufKey, TufKeyPair}
import com.advancedtelematic.libtuf.data.ValidatedString._
import com.advancedtelematic.libtuf_server.repo.server.RepoRoleRefresh
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.AddDelegationFromRemoteRequest
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import org.scalactic.source.Position

import scala.concurrent.Future
import com.advancedtelematic.tuf.reposerver.data.RepoCodecs._
import com.advancedtelematic.tuf.reposerver.http.{TufRepoSignedRoleProvider, TufRepoTargetItemsProvider}

trait RepoResourceDelegationsSpecUtil extends RepoResourceSpecUtil {
  lazy val keyPair = Ed25519KeyType.crypto.generateKeyPair()

  val delegatedRoleName = "my-delegation.test.ok_".unsafeApply[DelegatedRoleName]

  val delegation = {
    val delegationPath = "mypath/*".unsafeApply[DelegatedPathPattern]
    Delegation(delegatedRoleName, List(keyPair.pubkey.id), List(delegationPath))
  }

  implicit val roleRefresh = new RepoRoleRefresh(fakeKeyserverClient, new TufRepoSignedRoleProvider(), new TufRepoTargetItemsProvider())

  val delegations = Delegations(Map(keyPair.pubkey.id -> keyPair.pubkey), List(delegation))

  def uploadOfflineSignedTargetsRole(_delegations: Delegations = delegations)
                                            (implicit repoId: RepoId, pos: Position): Unit = {
    val signedTargets = buildSignedTargetsRoleWithDelegations(_delegations)(repoId, pos)
    Put(apiUri(s"repo/${repoId.show}/targets"), signedTargets).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  def buildDelegations(pubKey: TufKey, roleName: String = "mydelegation", pattern: String="mypath/*"): Delegations = {
    val delegation = {
      val delegationPath = pattern.unsafeApply[DelegatedPathPattern]
      val delegatedRoleName = roleName.unsafeApply[DelegatedRoleName]
      Delegation(delegatedRoleName, List(pubKey.id), List(delegationPath))
    }
    Delegations(Map(pubKey.id -> pubKey), List(delegation))
  }

  def buildSignedTargetsRoleWithDelegations(_delegations: Delegations = delegations)
                                                   (implicit repoId: RepoId, pos: Position): Future[JsonSignedPayload] = {
    val oldTargets = buildSignedTargetsRole(repoId, Map.empty)
    val newTargets = oldTargets.signed.copy(delegations = _delegations.some)

    fakeKeyserverClient.sign(repoId, newTargets).map(_.asJsonSignedPayload)
  }

  def buildSignedDelegatedTargets(delegatedKeyPair: TufKeyPair = keyPair, version: Int = 2)
                                         (implicit repoId: RepoId, pos: Position): SignedPayload[TargetsRole] = {
    val delegationTargets = TargetsRole(Instant.now().plus(30, ChronoUnit.DAYS), targets = Map.empty, version = version)
    val signature = TufCrypto.signPayload(delegatedKeyPair.privkey, delegationTargets.asJson).toClient(delegatedKeyPair.pubkey.id)
    SignedPayload(List(signature), delegationTargets, delegationTargets.asJson)
  }

  def pushSignedDelegatedMetadata(signedPayload: SignedPayload[TargetsRole], roleName: DelegatedRoleName=delegatedRoleName)
                                         (implicit repoId: RepoId): RouteTestResult = {
    Put(apiUri(s"repo/${repoId.show}/delegations/${roleName.value}.json"), signedPayload) ~> routes
  }

  def pushSignedDelegatedMetadataOk(signedPayload: SignedPayload[TargetsRole])
                                           (implicit repoId: RepoId): Unit =
    pushSignedDelegatedMetadata(signedPayload) ~> check {
      status shouldBe StatusCodes.NoContent
    }

  def pushSignedTargetsMetadata(signedPayload: JsonSignedPayload)
                                       (implicit repoId: RepoId): RouteTestResult = {
    Put(apiUri(s"repo/${repoId.show}/targets"), signedPayload).withValidTargetsCheckSum ~> routes
  }

  def addNewTrustedDelegations(delegations: Delegation*)
                                      (implicit repoId: RepoId): RouteTestResult = {
    Put(apiUri(s"repo/${repoId.show}/trusted-delegations"), delegations.asJson) ~> routes
  }

  def addNewRemoteDelegation(delegatedRoleName: DelegatedRoleName, req: AddDelegationFromRemoteRequest)
                                    (implicit  repoId: RepoId, pos: Position): RouteTestResult = {
    Put(apiUri(s"repo/${repoId.show}/trusted-delegations/${delegatedRoleName.value}/remote"), req.asJson) ~> routes
  }
}
