package com.advancedtelematic.tuf.keyserver.http

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import com.advancedtelematic.libtuf.crypt.CanonicalJson._
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.RootManipulationOps._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{RepoId, _}
import com.advancedtelematic.tuf.keyserver.daemon.DefaultKeyGenerationOp
import com.advancedtelematic.tuf.keyserver.data.KeyServerDataType.{
  KeyGenId,
  KeyGenRequest,
  KeyGenRequestStatus,
  SignedPayloadDbOps,
  SignedRootRole
}
import com.advancedtelematic.tuf.keyserver.db.{
  KeyGenRequestSupport,
  KeyRepositorySupport,
  SignedRootRoleSupport
}
import com.advancedtelematic.tuf.keyserver.roles.SignedRootRoles
import com.advancedtelematic.tuf.util.{KeyTypeSpecSupport, TufKeyserverSpec}
import io.circe.syntax._
import org.scalatest.Inspectors
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.time.{Seconds, Span}
import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import com.advancedtelematic.libats.test.MysqlDatabaseSpec

class SignedRootRolesSpec
    extends TufKeyserverSpec
    with MysqlDatabaseSpec
    with Inspectors
    with PatienceConfiguration
    with KeyGenRequestSupport
    with KeyTypeSpecSupport
    with SignedRootRoleSupport
    with KeyRepositorySupport {

  implicit val ec: scala.concurrent.ExecutionContextExecutor = ExecutionContext.global

  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig().copy(timeout = Span(10, Seconds))

  val signedRootRoles = new SignedRootRoles()
  val keyGenerationOp = DefaultKeyGenerationOp()

  keyTypeTest("root role payload must be signed with root key") { keyType =>
    val repoId = RepoId.generate()
    val rootKeyGenRequest = KeyGenRequest(
      KeyGenId.generate(),
      repoId,
      KeyGenRequestStatus.REQUESTED,
      RoleType.ROOT,
      keyType.crypto.defaultKeySize,
      keyType
    )

    keyGenRepo.persist(rootKeyGenRequest).futureValue
    keyGenerationOp(rootKeyGenRequest).futureValue
    signedRootRoles.findFreshAndPersist(repoId).futureValue
    val signed = signedRootRoles.findLatest(repoId).futureValue

    val rootKeyId = signed.signed.roles(RoleType.ROOT).keyids.head
    val publicKey = signed.signed.keys(rootKeyId)

    val clientSignature = signed.signatures.head

    val sig = Signature(clientSignature.sig, clientSignature.method)

    TufCrypto.isValid(sig, publicKey, signed.signed.asJson.canonical.getBytes) shouldBe true
  }

  keyTypeTest("persists signed payload when finding") { keyType =>
    val repoId = RepoId.generate()
    val rootKeyGenRequest = KeyGenRequest(
      KeyGenId.generate(),
      repoId,
      KeyGenRequestStatus.REQUESTED,
      RoleType.ROOT,
      keyType.crypto.defaultKeySize,
      keyType
    )

    keyGenRepo.persist(rootKeyGenRequest).futureValue
    keyGenerationOp(rootKeyGenRequest).futureValue

    val signed = signedRootRoles.findFreshAndPersist(repoId).futureValue

    signedRootRoleRepo
      .findLatest(repoId)
      .futureValue
      .content
      .signed
      .asJson shouldBe signed.signed.asJson

    signedRootRoleRepo.findLatest(repoId).futureValue.content.asJson shouldBe signed.asJson
  }

  keyTypeTest("returns renewed root.json when old one expired") { keyType =>
    val expiredSignedRootRoles = new SignedRootRoles(Duration.ofMillis(1))
    val repoId = RepoId.generate()
    val rootKeyGenRequest = KeyGenRequest(
      KeyGenId.generate(),
      repoId,
      KeyGenRequestStatus.REQUESTED,
      RoleType.ROOT,
      keyType.crypto.defaultKeySize,
      keyType
    )

    keyGenRepo.persist(rootKeyGenRequest).futureValue
    keyGenerationOp(rootKeyGenRequest).futureValue
    val oldRole = expiredSignedRootRoles.findFreshAndPersist(repoId).futureValue

    oldRole.signed.expires.isBefore(Instant.now.plus(1, ChronoUnit.HOURS)) shouldBe true

    val signedRootRole = signedRootRoles.findFreshAndPersist(repoId).futureValue
    signedRootRole.signed.expires.isAfter(Instant.now.plus(30, ChronoUnit.DAYS)) shouldBe true

  }

  keyTypeTest("persists renewed root.json when old one expired") { keyType =>
    val expiredSignedRootRoles = new SignedRootRoles(Duration.ofMillis(1))
    val repoId = RepoId.generate()
    val rootKeyGenRequest = KeyGenRequest(
      KeyGenId.generate(),
      repoId,
      KeyGenRequestStatus.REQUESTED,
      RoleType.ROOT,
      keyType.crypto.defaultKeySize,
      keyType
    )

    keyGenRepo.persist(rootKeyGenRequest).futureValue
    keyGenerationOp(rootKeyGenRequest).futureValue
    expiredSignedRootRoles.findFreshAndPersist(repoId).futureValue
    val signedRootRole = signedRootRoles.findFreshAndPersist(repoId).futureValue

    signedRootRole.signed.expires.isAfter(Instant.now.plus(30, ChronoUnit.DAYS)) shouldBe true
    signedRootRoleRepo.findLatest(repoId).futureValue.content.asJson shouldBe signedRootRole.asJson
  }

  keyTypeTest("returns old role when when keys are not online") { keyType =>
    val expiredSignedRootRoles = new SignedRootRoles(Duration.ofMillis(1))
    val repoId = RepoId.generate()
    val rootKeyGenRequest = KeyGenRequest(
      KeyGenId.generate(),
      repoId,
      KeyGenRequestStatus.REQUESTED,
      RoleType.ROOT,
      keyType.crypto.defaultKeySize,
      keyType
    )

    val rootRole = for {
      _ <- keyGenRepo.persist(rootKeyGenRequest)
      _ <- keyGenerationOp(rootKeyGenRequest)
      role <- expiredSignedRootRoles.findFreshAndPersist(repoId)
    } yield role

    val keyIds = rootRole.futureValue.signed.roleKeys(RoleType.ROOT).map(_.id)

    val fetchedRole = for {
      _ <- Future.sequence(keyIds.map(keyRepo.delete))
      role <- signedRootRoles.findFreshAndPersist(repoId)
    } yield role

    fetchedRole.futureValue.signed.asJson shouldBe rootRole.futureValue.signed.asJson
  }

  private def seedRootWithExpiry(repoId: RepoId, expires: Instant): Future[SignedRootRole] =
    for {
      initial <- signedRootRoles.findFreshAndPersist(repoId)
      seeded = initial.updated(signed =
        initial.signed.copy(expires = expires, version = initial.signed.version + 1)
      )
      dbRole = seeded.toDbSignedRole(repoId)
      _ <- signedRootRoleRepo.persist(dbRole)
    } yield dbRole

  private case class FloorCase(name: String,
                               initialExpiryOffset: Duration,
                               floorOffset: Option[Duration],
                               expectRenewal: Boolean)

  // | # | current root expiry | floor (expireNotBefore) | renews? | why                                      |
  // |---|----------------------|--------------------------|---------|-------------------------------------------|
  // | 1 | now + 400d           | none                      | no      | far from expiry, nothing to renew for      |
  // | 2 | now + 30min          | none                      | yes     | within the 1h near-expiry window           |
  // | 3 | now + 400d           | now + 100d                | no      | floor already satisfied by current expiry  |
  // | 4 | now + 10d            | now + 400d                | yes     | floor is later than current expiry         |
  // | 5 | now + 30min          | now - 10d (already past)  | yes     | near-expiry alone triggers; floor ignored  |
  // | 6 | now + 400d           | now + 400d (exact match)  | no      | `isBefore` is strict, boundary doesn't count|
  private val floorCases = Seq(
    FloorCase("far from expiry, no floor", Duration.ofDays(400), None, expectRenewal = false),
    FloorCase("near expiry, no floor", Duration.ofMinutes(30), None, expectRenewal = true),
    FloorCase(
      "far from expiry, floor already satisfied",
      Duration.ofDays(400),
      Some(Duration.ofDays(100)),
      expectRenewal = false
    ),
    FloorCase(
      "floor later than current expiry",
      Duration.ofDays(10),
      Some(Duration.ofDays(400)),
      expectRenewal = true
    ),
    FloorCase(
      "near expiry with a floor already in the past",
      Duration.ofMinutes(30),
      Some(Duration.ofDays(-10)),
      expectRenewal = true
    ),
    FloorCase(
      "floor exactly equal to current expiry (boundary)",
      Duration.ofDays(400),
      Some(Duration.ofDays(400)),
      expectRenewal = false
    )
  )

  test("Expire not before floor cases") {
    forAll(floorCases) { c =>
      val repoId = RepoId.generate()
      val rootKeyGenRequest = KeyGenRequest(
        KeyGenId.generate(),
        repoId,
        KeyGenRequestStatus.REQUESTED,
        RoleType.ROOT,
        Ed25519KeyType.crypto.defaultKeySize,
        Ed25519KeyType
      )

      keyGenRepo.persist(rootKeyGenRequest).futureValue
      keyGenerationOp(rootKeyGenRequest).futureValue

      val now = Instant.now.truncatedTo(ChronoUnit.SECONDS)
      val initialExpiry = now.plus(c.initialExpiryOffset)
      val seeded = seedRootWithExpiry(repoId, initialExpiry).futureValue
      val floor = c.floorOffset.map(now.plus(_))

      val renewed = signedRootRoles.findFreshAndPersist(repoId, floor).futureValue

      if (c.expectRenewal) {
        renewed.signed.version shouldBe seeded.version + 1
        // proves the floor extends expiry by defaultRoleExpire past itself, not up to
        // the floor value alone (case 4 only passes if this extra year is honored)
        val minExpectedExpiry = List(
          Some(now.plus(Duration.ofDays(365))),
          floor.map(_.plus(Duration.ofDays(365)))
        ).flatten.max.minus(Duration.ofMinutes(1))
        renewed.signed.expires.isAfter(minExpectedExpiry) shouldBe true
      } else {
        renewed.signed.version shouldBe seeded.version
        renewed.signed.expires shouldBe initialExpiry
      }
    }
  }

}
