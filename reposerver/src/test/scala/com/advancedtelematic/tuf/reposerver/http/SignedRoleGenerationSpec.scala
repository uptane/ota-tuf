package com.advancedtelematic.tuf.reposerver.http

import java.time.Instant
import org.apache.pekko.http.scaladsl.util.FastFuture
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.Errors.MissingEntity
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{TimestampRole, _}
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, RepoId, RoleType}
import com.advancedtelematic.libtuf_server.repo.server.RepoRoleRefresh
import com.advancedtelematic.tuf.reposerver.db.{
  RepoNamespaceRepositorySupport,
  SignedRoleRepositorySupport
}
import com.advancedtelematic.tuf.reposerver.util.{FakeKeyserverClient, TufReposerverSpec}
import com.advancedtelematic.libats.test.MysqlDatabaseSpec
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}

import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits

// TODO: Should be moved to libtuf_server and tested without TufRepoProviders
class SignedRoleGenerationSpec
    extends TufReposerverSpec
    with MysqlDatabaseSpec
    with SignedRoleRepositorySupport
    with RepoNamespaceRepositorySupport
    with ScalaFutures {
  override implicit val ec: ExecutionContext = Implicits.global

  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig().copy(timeout = Span(5, Seconds))

  val fakeKeyserverClient: FakeKeyserverClient = new FakeKeyserverClient

  val signedRoleGeneration = TufRepoSignedRoleGeneration(fakeKeyserverClient)

  implicit val roleRefresh: com.advancedtelematic.libtuf_server.repo.server.RepoRoleRefresh =
    new RepoRoleRefresh(
      fakeKeyserverClient,
      new TufRepoSignedRoleProvider(),
      new TufRepoTargetItemsProvider()
    )

  def setupRepo(): Future[RepoId] = for {
    repoId <- FastFuture.successful(RepoId.generate())
    _ <- fakeKeyserverClient.createRoot(repoId, Ed25519KeyType)
    _ <- signedRoleGeneration.regenerateAllSignedRoles(repoId)
  } yield repoId

  test("renewal of snapshots renews also timestamps") {
    val repoId = setupRepo().futureValue

    val oldTimestamps = signedRoleGeneration.findRole[TimestampRole](repoId).futureValue
    val oldSnapshots = signedRoleGeneration.findRole[SnapshotRole](repoId).futureValue

    signedRoleRepository
      .persist[SnapshotRole](
        repoId,
        oldSnapshots.copy(expiresAt = Instant.now().minusSeconds(60)),
        forceVersion = true
      )
      .futureValue

    val renewedSnapshots = signedRoleGeneration.findRole[SnapshotRole](repoId).futureValue
    val renewedTimestampsDb = signedRoleRepository.find[TimestampRole](repoId).futureValue
    val (_, renewedSnapshotsHash) =
      renewedTimestampsDb.role.meta(RoleType.SNAPSHOT.metaPath).hashes.head

    renewedSnapshots.checksum shouldNot be(oldSnapshots.checksum)
    renewedSnapshots.version shouldBe oldSnapshots.version + 1

    renewedTimestampsDb.version shouldBe oldTimestamps.version + 1
    renewedSnapshotsHash shouldBe renewedSnapshots.checksum.hash
  }

  test("renewal of targets renews also snapshots and timestamps") {
    val repoId = setupRepo().futureValue

    val oldTargets = signedRoleGeneration.findRole[TargetsRole](repoId).futureValue
    val oldSnapshots = signedRoleGeneration.findRole[SnapshotRole](repoId).futureValue
    val oldTimestamps = signedRoleGeneration.findRole[TimestampRole](repoId).futureValue

    signedRoleRepository
      .persist[TargetsRole](
        repoId,
        oldTargets.copy(expiresAt = Instant.now().minusSeconds(60)),
        forceVersion = true
      )
      .futureValue

    val renewedTargets = signedRoleGeneration.findRole[TargetsRole](repoId).futureValue
    val renewedSnapshots = signedRoleRepository.find[SnapshotRole](repoId).futureValue
    val renewedTimestamps = signedRoleGeneration.findRole[TimestampRole](repoId).futureValue
    val (_, renewedSnapshotsHash) =
      renewedTimestamps.role.meta(RoleType.SNAPSHOT.metaPath).hashes.head

    val renewedSnaphotsParsed = renewedSnapshots.role
    val (_, renewedTargetsHash) = renewedSnaphotsParsed.meta(RoleType.TARGETS.metaPath).hashes.head

    renewedTargetsHash shouldBe renewedTargets.checksum.hash
    renewedSnapshots.version shouldBe oldSnapshots.version + 1

    renewedTimestamps.version shouldBe oldTimestamps.version + 1
    renewedSnapshotsHash shouldBe renewedSnapshots.checksum.hash
  }

  test("finding snapshots triggers a targets.json renewal if needed") {
    val repoId = setupRepo().futureValue

    val oldTargets = signedRoleGeneration.findRole[TargetsRole](repoId).futureValue
    val oldSnapshots = signedRoleGeneration.findRole[SnapshotRole](repoId).futureValue

    signedRoleRepository
      .persist[TargetsRole](
        repoId,
        oldTargets.copy(expiresAt = Instant.now().minus(365, ChronoUnit.DAYS)),
        forceVersion = true
      )
      .futureValue

    val renewedSnapshots = signedRoleGeneration.findRole[SnapshotRole](repoId).futureValue

    renewedSnapshots.version shouldBe oldSnapshots.version + 1
    renewedSnapshots.role.meta(RoleType.TARGETS.metaPath).version shouldBe oldTargets.version + 1

    val renewedTargets = signedRoleGeneration.findRole[TargetsRole](repoId).futureValue
    renewedTargets.version shouldBe oldTargets.version + 1
  }

  test("finding timestamp triggers a targets.json renewal if needed") {
    val repoId = setupRepo().futureValue

    val oldTargets = signedRoleGeneration.findRole[TargetsRole](repoId).futureValue
    val oldSnapshots = signedRoleGeneration.findRole[SnapshotRole](repoId).futureValue
    val oldTimestamps = signedRoleGeneration.findRole[TimestampRole](repoId).futureValue

    signedRoleRepository
      .persist[TargetsRole](
        repoId,
        oldTargets.copy(expiresAt = Instant.now().minus(365, ChronoUnit.DAYS)),
        forceVersion = true
      )
      .futureValue

    val renewedTimestamps = signedRoleGeneration.findRole[TimestampRole](repoId).futureValue

    renewedTimestamps.version shouldBe oldTimestamps.version + 1
    renewedTimestamps.role
      .meta(RoleType.SNAPSHOT.metaPath)
      .version shouldBe oldSnapshots.version + 1

    val renewedTargets = signedRoleGeneration.findRole[TargetsRole](repoId).futureValue
    renewedTargets.version shouldBe oldTargets.version + 1
  }

  test("root.json renewal should honor the expires_not_before floor, same as targets") {
    val repoId = setupRepo().futureValue
    val ns = Namespace("OTA-3699-implen-lockbox")
    repoNamespaceRepo.persist(repoId, ns).futureValue

    val lockboxExpiresAt =
      Instant.now().plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    repoNamespaceRepo.setExpiresNotBefore(repoId, Some(lockboxExpiresAt)).futureValue

    signedRoleGeneration.regenerateAllSignedRoles(repoId).futureValue

    val renewedTargets = signedRoleGeneration.findRole[TargetsRole](repoId).futureValue
    val renewedRoot = signedRoleGeneration.findRole[RootRole](repoId).futureValue

    // targets already honor the lockbox's requested floor (existing, correct behavior)
    renewedTargets.expiresAt shouldBe lockboxExpiresAt

    // root.json should be pushed out to the same floor, but today it is not
    renewedRoot.expiresAt shouldBe lockboxExpiresAt
  }

  test("expires_not_before floor never regresses when a later lockbox requests an earlier one") {
    val repoId = setupRepo().futureValue
    repoNamespaceRepo.persist(repoId, Namespace("OTA-3699-floor-regression")).futureValue

    val aliceFloor = Instant.now().plus(1000, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    repoNamespaceRepo.setExpiresNotBefore(repoId, Some(aliceFloor)).futureValue
    signedRoleGeneration.regenerateAllSignedRoles(repoId).futureValue

    val rootAfterAlice = signedRoleGeneration.findRole[RootRole](repoId).futureValue
    rootAfterAlice.expiresAt shouldBe aliceFloor

    val eveFloor = Instant.now().plus(100, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    repoNamespaceRepo.setExpiresNotBefore(repoId, Some(eveFloor)).futureValue
    signedRoleGeneration.regenerateAllSignedRoles(repoId).futureValue

    val rootAfterEve = signedRoleGeneration.findRole[RootRole](repoId).futureValue

    // Fixed: eve's later, shorter-lived request is rejected as a no-op; alice's
    // still-valid lockbox keeps its coverage.
    rootAfterEve.expiresAt shouldBe aliceFloor
    repoNamespaceRepo.getExpiresNotBefore(repoId).futureValue shouldBe Some(aliceFloor)
  }

  test("expires_not_before floor can still be raised by a later lockbox") {
    val repoId = setupRepo().futureValue
    repoNamespaceRepo.persist(repoId, Namespace("OTA-3699-floor-raise")).futureValue

    val firstFloor = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    repoNamespaceRepo.setExpiresNotBefore(repoId, Some(firstFloor)).futureValue

    val laterFloor = Instant.now().plus(400, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    repoNamespaceRepo.setExpiresNotBefore(repoId, Some(laterFloor)).futureValue

    repoNamespaceRepo.getExpiresNotBefore(repoId).futureValue shouldBe Some(laterFloor)
  }

  test(
    "regenerateAllSignedRoles cannot honor a floor that failed to persist (no repo_namespaces row)"
  ) {
    val repoId = setupRepo().futureValue
    val floor = Instant.now().plus(400, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

    // no repoNamespaceRepo.persist(repoId, ...) call: reproduces the missing-row case
    repoNamespaceRepo
      .setExpiresNotBefore(repoId, Some(floor))
      .failed
      .futureValue shouldBe a[MissingEntity[_]]

    repoNamespaceRepo.getExpiresNotBefore(repoId).futureValue shouldBe None

    signedRoleGeneration.regenerateAllSignedRoles(repoId).futureValue

    val root = signedRoleGeneration.findRole[RootRole](repoId).futureValue

    // OTA-3704: root never reaches the floor, because it was never durably persisted in
    // the first place. This is a separate bug from OTA-3699, still open after that fix.
    root.expiresAt should not be floor
  }

}
