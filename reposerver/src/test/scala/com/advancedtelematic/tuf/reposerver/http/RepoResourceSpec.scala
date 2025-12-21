package com.advancedtelematic.tuf.reposerver.http

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.scalatest.EitherValues._
import org.apache.pekko.http.scaladsl.model.Multipart.FormData.BodyPart
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers._
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import cats.data.NonEmptyList
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.show._
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.DataType.{HashMethod, Namespace}
import com.advancedtelematic.libats.data.{ErrorRepresentation, PaginationResult}
import com.advancedtelematic.libats.data.RefinedUtils.RefineTry
import com.advancedtelematic.libats.http.Errors.RawError
import com.advancedtelematic.tuf.reposerver.http.ErrorCodes
import com.advancedtelematic.libtuf.crypt.CanonicalJson._
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{
  ClientHashes,
  ClientTargetItem,
  DelegatedRoleName,
  Delegation,
  RoleTypeOps,
  RootRole,
  SnapshotRole,
  TargetCustom,
  TargetsRole,
  TimestampRole
}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, RepoId, RoleType, TufKey, _}
import com.advancedtelematic.libtuf.data.ValidatedString.*
import com.advancedtelematic.libtuf_server.crypto.Sha256Digest
import com.advancedtelematic.libtuf_server.data.Requests._
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import com.advancedtelematic.libtuf_server.repo.client.ReposerverClient.EditTargetItem
import com.advancedtelematic.libtuf_server.repo.server.DataType.SignedRole
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.{StorageMethod, TargetItem}
import com.advancedtelematic.tuf.reposerver.db.SignedRoleDbTestUtil._
import com.advancedtelematic.tuf.reposerver.db.{SignedRoleRepositorySupport, TargetItemRepositorySupport}
import com.advancedtelematic.tuf.reposerver.target_store.TargetStoreEngine.{
  TargetBytes,
  TargetRetrieveResult
}
import com.advancedtelematic.tuf.reposerver.util.NamespaceSpecOps._
import com.advancedtelematic.tuf.reposerver.util._
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineV
import io.circe.syntax._
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.prop.Whenever
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{Assertion, BeforeAndAfterAll, Inspectors}

import scala.concurrent.Future
import org.scalatest.OptionValues._

import java.net.URI
import com.advancedtelematic.libtuf_server.data.Marshalling.*
import PaginationResult.*

class RepoResourceSpec
    extends TufReposerverSpec
    with RepoResourceSpecUtil
    with ResourceSpec
    with BeforeAndAfterAll
    with Inspectors
    with Whenever
    with PatienceConfiguration
    with SignedRoleRepositorySupport
    with TargetItemRepositorySupport {

  override val ec: scala.concurrent.ExecutionContextExecutor = this.executor

  val repoId = RepoId.generate()

  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig().copy(timeout = Span(5, Seconds))

  override def beforeAll(): Unit = {
    super.beforeAll()
    fakeKeyserverClient.createRoot(repoId).futureValue
  }

  def signaturesShouldBeValid(repoId: RepoId,
                              roleType: RoleType,
                              signedPayload: JsonSignedPayload): Assertion = {
    val signature = signedPayload.signatures.head
    val signed = signedPayload.signed

    val isValid =
      TufCrypto.isValid(signature, fakeKeyserverClient.publicKey(repoId, roleType), signed)
    isValid shouldBe true
  }

  def createRepo()(implicit ns: NamespaceTag): Unit =
    Post(apiUri(s"user_repo")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

  test("POST returns latest signed json") {
    Post(apiUri(s"repo/${repoId.show}/targets/myfile"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val signedPayload = responseAs[JsonSignedPayload]
      signaturesShouldBeValid(repoId, RoleType.TARGETS, signedPayload)

      val signed = signedPayload.signed
      val targetsRole = signed.as[TargetsRole].valueOr(throw _)
      targetsRole.targets("myfile".refineTry[ValidTargetFilename].get).length shouldBe 2
    }
  }

  test("POST returns json with previous elements") {
    Post(apiUri(s"repo/${repoId.show}/targets/myfile01"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Post(apiUri(s"repo/${repoId.show}/targets/myfile02"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val signed = responseAs[JsonSignedPayload].signed

      val targetsRole = signed.as[TargetsRole].valueOr(throw _)
      targetsRole.targets("myfile01".refineTry[ValidTargetFilename].get).length shouldBe 2
      targetsRole.targets("myfile02".refineTry[ValidTargetFilename].get).length shouldBe 2
    }
  }

  test("POST returns json with valid hashes") {
    Post(apiUri(s"repo/${repoId.show}/targets/myfile"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val signed = responseAs[JsonSignedPayload].signed

      val targetsRole = signed.as[TargetsRole].valueOr(throw _)
      targetsRole
        .targets("myfile".refineTry[ValidTargetFilename].get)
        .hashes(HashMethod.SHA256) shouldBe testFile.checksum.hash
    }
  }

  test("POSTing a file adds uri to custom field") {
    val urlTestFile = testFile.copy(
      uri = Uri("https://ats.com/urlTestFile"),
      name = TargetName("myfilewithuri").some,
      version = TargetVersion("0.1.0").some,
      targetFormat = None
    )

    Post(apiUri(s"repo/${repoId.show}/targets/myfilewithuri"), urlTestFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val signed = responseAs[JsonSignedPayload].signed

      val targetsRole = signed.as[TargetsRole].valueOr(throw _)
      val item = targetsRole.targets("myfilewithuri".refineTry[ValidTargetFilename].get)

      item.customParsed[TargetCustom].flatMap(_.uri).map(_.toString) should contain(
        urlTestFile.uri.toString()
      )
      item.customParsed[TargetCustom].flatMap(_.targetFormat).get shouldBe TargetFormat.BINARY
    }
  }

  test("fails if there is no root.json available") {
    val unexistingRepoId = RepoId.generate()

    Post(apiUri(s"repo/${unexistingRepoId.show}/targets/otherfile"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.FailedDependency
    }
  }

  test("GET for each role type returns the signed json with valid signatures") {
    Post(apiUri(s"repo/${repoId.show}/targets/myfile"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    forAll(RoleType.TUF_ALL.reverse) { roleType =>
      Get(apiUri(s"repo/${repoId.show}/$roleType.json")) ~> routes ~> check {
        status shouldBe StatusCodes.OK

        val signedPayload = responseAs[JsonSignedPayload]
        signaturesShouldBeValid(repoId, roleType, signedPayload)
      }
    }
  }

  test("GET on timestamp.json returns a valid Timestamp role") {
    val newRepoId = addTargetToRepo()

    Get(apiUri(s"repo/${newRepoId.show}/timestamp.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      signaturesShouldBeValid(
        newRepoId,
        RoleType.TIMESTAMP,
        responseAs[SignedPayload[TimestampRole]].asJsonSignedPayload
      )
    }
  }

  test("GET on snapshot.json returns a valid Snapshot role") {
    val newRepoId = addTargetToRepo()

    Get(apiUri(s"repo/${newRepoId.show}/snapshot.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      signaturesShouldBeValid(
        newRepoId,
        RoleType.SNAPSHOT,
        responseAs[SignedPayload[SnapshotRole]].asJsonSignedPayload
      )
    }
  }

  test("GET on targets.json returns a valid Targets role") {
    val newRepoId = addTargetToRepo()

    Get(apiUri(s"repo/${newRepoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      header("x-ats-role-checksum") shouldBe defined
      signaturesShouldBeValid(
        newRepoId,
        RoleType.TARGETS,
        responseAs[SignedPayload[TargetsRole]].asJsonSignedPayload
      )
    }
  }

  keyTypeTest("GET on root.json returns a valid Root role") { keyType =>
    val newRepoId = addTargetToRepo(keyType = keyType)

    Get(apiUri(s"repo/${newRepoId.show}/root.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      signaturesShouldBeValid(
        newRepoId,
        RoleType.ROOT,
        responseAs[SignedPayload[RootRole]].asJsonSignedPayload
      )
    }
  }

  test("GET on root.json fails if not available on keyserver") {
    val newRepoId = RepoId.generate()

    Get(apiUri(s"repo/${newRepoId.show}/root.json")) ~> routes ~> check {
      status shouldBe StatusCodes.FailedDependency
    }
  }

  test("GET on 1.root.json fails if not available on keyserver") {
    val newRepoId = RepoId.generate()

    Get(apiUri(s"repo/${newRepoId.show}/1.root.json")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  test("GET on root.json gets json from keyserver") {
    val newRepoId = RepoId.generate()

    fakeKeyserverClient.createRoot(newRepoId).futureValue

    Get(apiUri(s"repo/${newRepoId.show}/root.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      signaturesShouldBeValid(
        newRepoId,
        RoleType.ROOT,
        responseAs[SignedPayload[RootRole]].asJsonSignedPayload
      )
      header("x-ats-tuf-repo-id").get.value() shouldBe newRepoId.uuid.toString
    }
  }

  test("POST a new target updates snapshot.json") {
    val snapshotRole =
      Get(apiUri(s"repo/${repoId.show}/snapshot.json")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[SnapshotRole]]
      }

    Future(Thread.sleep(1100)).futureValue

    Post(apiUri(s"repo/${repoId.show}/targets/changesnapshot"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val newTimestampRole =
      Get(apiUri(s"repo/${repoId.show}/snapshot.json"), testFile) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[SnapshotRole]]
      }

    snapshotRole.signed.expires.isBefore(newTimestampRole.signed.expires) shouldBe true

    snapshotRole.signatures.head shouldNot be(newTimestampRole.signatures.head)
  }

  test("POST a new target updates timestamp.json") {
    val timestampRole =
      Get(apiUri(s"repo/${repoId.show}/timestamp.json")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TimestampRole]]
      }

    Future(Thread.sleep(1100)).futureValue

    Post(apiUri(s"repo/${repoId.show}/targets/changets"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val newTimestampRole =
      Get(apiUri(s"repo/${repoId.show}/timestamp.json"), testFile) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TimestampRole]]
      }

    timestampRole.signed.expires.isBefore(newTimestampRole.signed.expires) shouldBe true

    timestampRole.signatures.head shouldNot be(newTimestampRole.signatures.head)
  }

  test("timestamp.json is refreshed if expired") {
    val role = Get(apiUri(s"repo/${repoId.show}/timestamp.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TimestampRole]]
    }

    val expiredInstant = Instant.now.minus(1, ChronoUnit.DAYS)
    val expiredJsonPayload = JsonSignedPayload(
      role.signatures,
      role.asJsonSignedPayload.signed.deepMerge(Json.obj("expires" -> expiredInstant.asJson))
    )

    val newRole = SignedRole
      .withChecksum[TimestampRole](expiredJsonPayload, role.signed.version, expiredInstant)
      .futureValue
    signedRoleRepository.update(repoId, newRole).futureValue

    Get(apiUri(s"repo/${repoId.show}/timestamp.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val updatedRole = responseAs[SignedPayload[TimestampRole]].signed
      updatedRole.version shouldBe role.signed.version + 1
      updatedRole.expires.isAfter(Instant.now) shouldBe true
    }
  }

  test("snapshot.json is refreshed if expired") {
    val role = Get(apiUri(s"repo/${repoId.show}/snapshot.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[SnapshotRole]]
    }

    val expiredInstant = Instant.now.minus(1, ChronoUnit.DAYS)
    val expiredJsonPayload = JsonSignedPayload(
      role.signatures,
      role.asJsonSignedPayload.signed.deepMerge(Json.obj("expires" -> expiredInstant.asJson))
    )

    val newRole = SignedRole
      .withChecksum[SnapshotRole](expiredJsonPayload, role.signed.version, expiredInstant)
      .futureValue
    signedRoleRepository.update(repoId, newRole).futureValue

    Get(apiUri(s"repo/${repoId.show}/snapshot.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val updatedRole = responseAs[SignedPayload[SnapshotRole]].signed

      updatedRole.version shouldBe role.signed.version + 1
      updatedRole.expires.isAfter(Instant.now) shouldBe true
    }
  }

  test("targets.json is refreshed if expired") {
    val role = Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]]
    }

    val expiredInstant = Instant.now.minus(1, ChronoUnit.DAYS)
    val expiredJsonPayload = JsonSignedPayload(
      role.signatures,
      role.asJsonSignedPayload.signed.deepMerge(Json.obj("expires" -> expiredInstant.asJson))
    )

    val newRole = SignedRole
      .withChecksum[TargetsRole](expiredJsonPayload, role.signed.version, expiredInstant)
      .futureValue
    signedRoleRepository.update(repoId, newRole).futureValue

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val updatedRole = responseAs[SignedPayload[TargetsRole]].signed

      updatedRole.version shouldBe role.signed.version + 1
      updatedRole.expires.isAfter(Instant.now) shouldBe true
    }
  }

  test("GET on a role returns valid json before targets are added") {
    val repoId = RepoId.generate()

    Post(apiUri(s"repo/${repoId.show}"))
      .withHeaders(RawHeader("x-ats-namespace", repoId.show)) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed.targets should be(empty)
    }

    forAll(RoleType.TUF_ALL) { roleType =>
      Get(apiUri(s"repo/${repoId.show}/$roleType.json")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
    }
  }

  keyTypeTest("SnapshotRole includes signed jsons lengths") { keyType =>
    val newRepoId = addTargetToRepo(keyType = keyType)

    val targetsRole =
      Get(apiUri(s"repo/${newRepoId.show}/targets.json")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }

    val rootRole =
      Get(apiUri(s"repo/${newRepoId.show}/root.json")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[RootRole]]
      }

    Get(apiUri(s"repo/${newRepoId.show}/snapshot.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val signed = responseAs[SignedPayload[SnapshotRole]].signed

      val targetLength = targetsRole.asJson.canonical.length
      signed.meta(RoleType.TARGETS.metaPath).length shouldBe targetLength

      val rootLength = rootRole.asJson.canonical.length
      signed.meta(RoleType.ROOT.metaPath).length shouldBe rootLength
    }
  }

  test("GET snapshots.json returns json with valid hashes") {
    val newRepoId = addTargetToRepo()

    Post(apiUri(s"repo/${newRepoId.show}/targets/myfile"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"repo/${newRepoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val targetsRole = responseAs[SignedPayload[TargetsRole]]

      val targetsCheckSum = Sha256Digest.digest(targetsRole.asJson.canonical.getBytes)

      Get(apiUri(s"repo/${newRepoId.show}/snapshot.json")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val snapshotRole = responseAs[SignedPayload[SnapshotRole]].signed

        val hash = snapshotRole.meta(RoleType.TARGETS.metaPath).hashes(targetsCheckSum.method)

        hash shouldBe targetsCheckSum.hash
      }
    }
  }

  test("fails for non existent targets") {
    val newRepoId = addTargetToRepo()

    Delete(apiUri(s"repo/${newRepoId.show}/targets/doesnotexist")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  test("delete removes target item from targets.json") {
    val newRepoId = addTargetToRepo()

    Get(apiUri(s"repo/${newRepoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val targetsRole = responseAs[SignedPayload[TargetsRole]]

      targetsRole.signed.targets shouldNot be(empty)
    }

    Delete(apiUri(s"repo/${newRepoId.show}/targets/myfile01")) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${newRepoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val targetsRole = responseAs[SignedPayload[TargetsRole]]

      targetsRole.signed.targets should be(empty)
    }
  }

  test("delete removes target from target store when target is managed") {
    val repoId = addTargetToRepo()

    Put(
      apiUri(s"repo/${repoId.show}/targets/some/target?name=bananas&version=0.0.1"),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val targetFilename = refineV[ValidTargetFilename]("some/target").toOption.get

    localStorage.retrieve(repoId, targetFilename).futureValue shouldBe a[TargetBytes]

    Delete(apiUri(s"repo/${repoId.show}/targets/some/target")) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets/some/target")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }

    localStorage
      .retrieve(repoId, targetFilename)
      .failed
      .futureValue shouldBe Errors.TargetNotFoundError
  }

  test("Bumps version number when adding a new target") {
    val newRepoId = addTargetToRepo()

    Post(apiUri(s"repo/${newRepoId.show}/targets/myfile"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"repo/${newRepoId.show}/snapshot.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val targetsRole = responseAs[SignedPayload[SnapshotRole]]

      targetsRole.signed.version shouldBe 2
    }

    Get(apiUri(s"repo/${newRepoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val targetsRole = responseAs[SignedPayload[TargetsRole]]

      targetsRole.signed.version shouldBe 2
    }

    Get(apiUri(s"repo/${newRepoId.show}/timestamp.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val role = responseAs[SignedPayload[TimestampRole]]
      role.signed.version shouldBe 2
    }
  }

  keyTypeTest("delegates to keyServer to create root") { keyType =>
    val newRepoId = RepoId.generate()

    withRandomNamepace { implicit ns =>
      Post(
        apiUri(s"repo/${newRepoId.show}"),
        CreateRepositoryRequest(keyType)
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        fakeKeyserverClient.fetchRootRole(newRepoId).futureValue.signed shouldBe a[RootRole]
      }
    }
  }

  keyTypeTest("POST on user_create creates a repository for a namespace") { keyType =>
    withRandomNamepace { implicit ns =>
      Post(apiUri(s"user_repo"), CreateRepositoryRequest(keyType)).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val newRepoId = responseAs[RepoId]
        fakeKeyserverClient.fetchRootRole(newRepoId).futureValue.signed shouldBe a[RootRole]
      }
    }
  }

  keyTypeTest("POST on user_create creates a repository for a namespace with given KeyType") {
    keyType =>
      val otherKeyType = if (keyType == Ed25519KeyType) RsaKeyType else Ed25519KeyType

      withRandomNamepace { implicit ns =>
        Post(
          apiUri("user_repo"),
          CreateRepositoryRequest(otherKeyType)
        ).namespaced ~> routes ~> check {
          status shouldBe StatusCodes.OK
          val newRepoId = responseAs[RepoId]
          val signed = fakeKeyserverClient.fetchRootRole(newRepoId).futureValue.signed
          signed shouldBe a[RootRole]

          signed
            .roles(RoleType.ROOT)
            .keyids
            .foreach(keyId => assert(signed.keys(keyId).keytype == otherKeyType))
        }
      }
  }

  keyTypeTest("creating a target on user_creates adds target to user repo") { keyType =>
    withRandomNamepace { implicit ns =>
      val newRepoId =
        Post(apiUri(s"user_repo"), CreateRepositoryRequest(keyType)).namespaced ~> routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[RepoId]
        }

      Post(apiUri("user_repo/targets/myfile"), testFile).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK

        val signedPayload = responseAs[JsonSignedPayload]
        signaturesShouldBeValid(newRepoId, RoleType.TARGETS, signedPayload)
      }
    }
  }

  keyTypeTest("getting role after adding a target on user repo returns user role") { keyType =>
    withRandomNamepace { implicit ns =>
      val newRepoId =
        Post(apiUri("user_repo"), CreateRepositoryRequest(keyType)).namespaced ~> routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[RepoId]
        }

      Post(apiUri("user_repo/targets/myfile"), testFile).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK

        val signedPayload = responseAs[JsonSignedPayload]
        signaturesShouldBeValid(newRepoId, RoleType.TARGETS, signedPayload)
      }

      Get(apiUri("user_repo/root.json"), testFile).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK

        val signedPayload = responseAs[SignedPayload[RootRole]]
        signaturesShouldBeValid(newRepoId, RoleType.ROOT, signedPayload.asJsonSignedPayload)
      }
    }
  }

  keyTypeTest("fails if repo for user already exists") { keyType =>
    withRandomNamepace { implicit ns =>
      Post(apiUri("user_repo"), CreateRepositoryRequest(keyType)).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Post(apiUri("user_repo")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }
  }

  test("creating repo fails for invalid key type parameter") {
    withRandomNamepace { implicit ns =>
      Post(apiUri("user_repo"))
        .withEntity(ContentTypes.`application/json`, """ { "keyType":"caesar" } """)
        .namespaced ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  val testEntity = HttpEntity(ByteString("""
                                           |Like all the men of the Library, in my younger days I traveled;
                                           |I have journeyed in quest of a book, perhaps the catalog of catalogs.
                                           |""".stripMargin))

  val fileBodyPart = BodyPart("file", testEntity, Map("filename" -> "babel.txt"))

  val form = Multipart.FormData(fileBodyPart)

  test("uploading a target changes targets json") {
    val repoId = addTargetToRepo()

    Put(
      apiUri(s"repo/${repoId.show}/targets/some/target/funky/thing?name=name&version=version"),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]]
    }

    Head(apiUri(s"repo/${repoId.show}/targets/some/target/funky/thing")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseEntity shouldBe HttpEntity.Empty
    }

    Get(apiUri(s"repo/${repoId.show}/targets/some/target/funky/thing")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseEntity.dataBytes.runReduce(_ ++ _).futureValue shouldBe testEntity.getData()
    }
  }

  test("uploading a target using raw body changes targets json") {
    val repoId = addTargetToRepo()

    Put(
      apiUri(s"repo/${repoId.show}/targets/some/target/raw/thing?name=name&version=version"),
      testEntity
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets/some/target/raw/thing")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseEntity.dataBytes.runReduce(_ ++ _).futureValue shouldBe testEntity.getData()
    }
  }

  test("PUT /targets/{filename} uploads binary file and adds target to metadata") {
    val repoId = addTargetToRepo()
    
    // Create a 1KB binary file
    val fileSize = 1024
    val fileContent = ByteString(Array.fill(fileSize)(0x42.toByte))
    val fileHash = Sha256Digest.digest(fileContent.toArray)
    val filename = "test-binary-file.bin"
    
    // Upload the file
    Put(
      apiUri(s"repo/${repoId.show}/targets/$filename?name=test-target&version=1.0.0"),
      HttpEntity(ContentTypes.`application/octet-stream`, fileContent)
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    
    // Verify the file is stored in the storage backend
    Get(apiUri(s"repo/${repoId.show}/targets/$filename")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val retrievedContent = responseEntity.dataBytes.runReduce(_ ++ _).futureValue
      retrievedContent shouldBe fileContent
      retrievedContent.length shouldBe fileSize
    }
    
    // Verify the target is added to metadata with correct hash and size
    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val signed = responseAs[SignedPayload[TargetsRole]]
      val targetsRole = signed.signed
      val targetFilename = filename.refineTry[ValidTargetFilename].get
      
      targetsRole.targets.keys should contain(targetFilename)
      val targetItem = targetsRole.targets(targetFilename)
      targetItem.length shouldBe fileSize
      targetItem.hashes(HashMethod.SHA256) shouldBe fileHash.hash
    }
  }

  keyTypeTest("uploading a target from a uri changes targets json") { keyType =>
    val repoId = addTargetToRepo(keyType = keyType)

    Put(
      apiUri(
        s"repo/${repoId.show}/targets/some/target/funky/thing?name=name&version=version&fileUri=${fakeHttpClient.fileUri}"
      )
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"repo/${repoId.show}/targets/some/target/funky/thing")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseEntity.dataBytes.runReduce(_ ++ _).futureValue shouldBe fakeHttpClient.fileBody
        .getData()
    }
  }

  test("GET returns 404 if target does not exist") {
    val repoId = addTargetToRepo()

    Get(apiUri(s"repo/${repoId.show}/targets/some/thing")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  test("HEAD returns 404 if target does not exist") {
    val repoId = addTargetToRepo()

    Head(apiUri(s"repo/${repoId.show}/targets/some/thing")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
      responseEntity shouldBe HttpEntity.Empty
    }
  }

  test("accept name/version, hardwareIds, targetFormat") {
    val repoId = addTargetToRepo()
    val targetFilename: TargetFilename = Refined.unsafeApply("target/with/desc")

    Put(
      apiUri(
        s"repo/${repoId.show}/targets/${targetFilename.value}?name=somename&version=someversion&hardwareIds=1,2,3&targetFormat=binary"
      ),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val custom = responseAs[SignedPayload[TargetsRole]].signed
        .targets(targetFilename)
        .customParsed[TargetCustom]

      custom.map(_.name) should contain(TargetName("somename"))
      custom.map(_.version) should contain(TargetVersion("someversion"))
      custom.map(_.hardwareIds.map(_.value)) should contain(Seq("1", "2", "3"))
      custom.flatMap(_.targetFormat) should contain(TargetFormat.BINARY)
    }
  }

  test("accepts custom json to nest into target custom") {
    val repoId = addTargetToRepo()
    val targetFilename: TargetFilename = Refined.unsafeApply("target/with/desc")

    Put(
      apiUri(
        s"repo/${repoId.show}/targets/${targetFilename.value}?name=somename&version=someversion"
      ),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val payload =
      io.circe.jawn.parse("""{"param0":0,"param1":{"nested1":1,"nested2":"mystring"}}""").value

    Patch(
      apiUri(s"repo/${repoId.show}/proprietary-custom/${targetFilename.value}"),
      payload
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val custom = responseAs[SignedPayload[TargetsRole]].signed
        .targets(targetFilename)
        .customParsed[TargetCustom]
        .value
      custom.name shouldBe TargetName("somename")
      custom.version shouldBe TargetVersion("someversion")

      custom.proprietary shouldBe payload
    }
  }

  test("accepts empty object in custom proprietary json") {
    val repoId = addTargetToRepo()
    val targetFilename: TargetFilename = Refined.unsafeApply("target/with/desc")

    Put(
      apiUri(
        s"""repo/${repoId.show}/targets/${targetFilename.value}?name=somename&version=someversion"""
      ),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Patch(
      apiUri(s"""repo/${repoId.show}/proprietary-custom/${targetFilename.value}"""),
      Map("some" -> "value").asJson
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Patch(
      apiUri(s"""repo/${repoId.show}/proprietary-custom/${targetFilename.value}"""),
      Json.obj()
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val custom = responseAs[SignedPayload[TargetsRole]].signed
        .targets(targetFilename)
        .customParsed[TargetCustom]
        .value
      custom.proprietary shouldBe Json.obj()
    }
  }

  test("PATCH target proprietary json attributes overwrite existing json parameters on top level") {
    val repoId = addTargetToRepo()
    val targetFilename: TargetFilename = Refined.unsafeApply("target/with/desc")

    val payload = Map("myattr" -> Map("myattr1" -> 0)).asJson

    Put(
      apiUri(
        s"""repo/${repoId.show}/targets/${targetFilename.value}?name=somename&version=someversion"""
      ),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Patch(
      apiUri(s"""repo/${repoId.show}/proprietary-custom/${targetFilename.value}"""),
      payload
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    val payload2 = Map("myattr" -> Map("myattr1" -> 2)).asJson

    Patch(
      apiUri(s"""repo/${repoId.show}/proprietary-custom/${targetFilename.value}"""),
      payload2
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val custom = responseAs[SignedPayload[TargetsRole]].signed
        .targets(targetFilename)
        .customParsed[TargetCustom]
        .value

      custom.proprietary shouldBe payload2
    }
  }

  test("PATCH proprietary json top level parameter overwrites entire object, does not deep merge") {
    val repoId = addTargetToRepo()
    val targetFilename: TargetFilename = Refined.unsafeApply("target/with/desc")

    val payload = Map("myattr" -> Map("myattr1" -> 0)).asJson

    Put(
      apiUri(
        s"""repo/${repoId.show}/targets/${targetFilename.value}?name=somename&version=someversion"""
      ),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Patch(
      apiUri(s"""repo/${repoId.show}/proprietary-custom/${targetFilename.value}"""),
      payload
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    val payload2 = Map("myattr" -> Map("myattr2" -> 2)).asJson

    Patch(
      apiUri(s"""repo/${repoId.show}/proprietary-custom/${targetFilename.value}"""),
      payload2
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val custom = responseAs[SignedPayload[TargetsRole]].signed
        .targets(targetFilename)
        .customParsed[TargetCustom]
        .value

      custom.proprietary shouldBe payload2
    }
  }

  test("PATCH proprietary json can add attributes on top level") {
    val repoId = addTargetToRepo()
    val targetFilename: TargetFilename = Refined.unsafeApply("target/with/desc")

    val payload = Map("myattr" -> Map("myattr1" -> 0)).asJson

    Put(
      apiUri(
        s"""repo/${repoId.show}/targets/${targetFilename.value}?name=somename&version=someversion"""
      ),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Patch(
      apiUri(s"""repo/${repoId.show}/proprietary-custom/${targetFilename.value}"""),
      payload
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    val payload2 = Map("myattr2" -> Map("myattr2" -> 2)).asJson

    Patch(
      apiUri(s"""repo/${repoId.show}/proprietary-custom/${targetFilename.value}"""),
      payload2
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val custom = responseAs[SignedPayload[TargetsRole]].signed
        .targets(targetFilename)
        .customParsed[TargetCustom]
        .value

      custom.proprietary shouldBe Map(
        "myattr2" -> Map("myattr2" -> 2),
        "myattr" -> Map("myattr1" -> 0)
      ).asJson
    }
  }

  test("PATCH proprietary json attributes cannot override non proprietary values") {
    val repoId = addTargetToRepo()
    val targetFilename: TargetFilename = Refined.unsafeApply("target/with/desc")

    Put(
      apiUri(
        s"""repo/${repoId.show}/targets/${targetFilename.value}?name=somename&version=someversion"""
      ),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val payload =
      Map("name" -> "othername", "version" -> "other-version", "myparam" -> "one").asJson

    Patch(
      apiUri(s"""repo/${repoId.show}/proprietary-custom/${targetFilename.value}"""),
      payload
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val custom = responseAs[SignedPayload[TargetsRole]].signed
        .targets(targetFilename)
        .customParsed[TargetCustom]
        .value

      custom.name shouldBe TargetName("somename")
      custom.version shouldBe TargetVersion("someversion")

      custom.proprietary shouldBe Map("myparam" -> "one").asJson
    }
  }

  test("Missing targetFormat gets set to BINARY") {
    val repoId = addTargetToRepo()
    val targetFilename: TargetFilename = Refined.unsafeApply("target/with/desc")

    Put(
      apiUri(
        s"repo/${repoId.show}/targets/${targetFilename.value}?name=somename&version=someversion&hardwareIds=1,2,3"
      ),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val custom = responseAs[SignedPayload[TargetsRole]].signed
        .targets(targetFilename)
        .customParsed[TargetCustom]

      custom.flatMap(_.targetFormat) should contain(TargetFormat.BINARY)
    }
  }

  test("on updates, updatedAt in target custom is updated, createdAt is unchanged") {
    val repoId = addTargetToRepo()
    val targetFilename: TargetFilename = Refined.unsafeApply("target/to/update")

    Put(
      apiUri(
        s"repo/${repoId.show}/targets/${targetFilename.value}?name=somename&version=someversion"
      ),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val now = Instant.now

    Thread.sleep(1000)

    Put(
      apiUri(
        s"repo/${repoId.show}/targets/${targetFilename.value}?name=somename&version=someversion"
      ),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val custom = responseAs[SignedPayload[TargetsRole]].signed
        .targets(targetFilename)
        .customParsed[TargetCustom]

      custom.map(_.createdAt).get.isBefore(now) shouldBe true
      custom.map(_.updatedAt).get.isAfter(now) shouldBe true
    }
  }

  test("create a repo returns 409 if repo for namespace already exists") {
    val repoId = RepoId.generate()

    Post(apiUri(s"repo/${repoId.show}"))
      .withHeaders(RawHeader("x-ats-namespace", repoId.show)) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    fakeKeyserverClient.fetchRootRole(repoId).futureValue shouldBe a[SignedPayload[_]]

    val otherRepoId = RepoId.generate()

    Post(apiUri(s"repo/${otherRepoId.show}"))
      .withHeaders(RawHeader("x-ats-namespace", repoId.show)) ~> routes ~> check {
      status shouldBe StatusCodes.Conflict
    }

    fakeKeyserverClient
      .fetchRootRole(otherRepoId)
      .failed
      .futureValue
      .asInstanceOf[RawError]
      .code
      .code shouldBe "root_role_not_found"
  }

  keyTypeTest("accepts an offline signed targets.json") { keyType =>
    implicit val repoId = addTargetToRepo(keyType = keyType)

    Put(
      apiUri(s"repo/${repoId.show}/targets/old/target?name=bananas&version=0.0.1"),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val targetFilename = refineV[ValidTargetFilename]("old/target").toOption.get
    localStorage.retrieve(repoId, targetFilename).futureValue shouldBe a[TargetRetrieveResult]

    val signedPayload = buildSignedTargetsRole(repoId, offlineTargets, version = 3)

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
      header("x-ats-role-checksum").map(_.value) should contain(
        makeRoleChecksumHeader(repoId).value
      )
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val targets = responseAs[SignedPayload[TargetsRole]].signed.targets
      targets.keys should contain(offlineTargetFilename)
    }

    // check that previous target has been deleted
    localStorage
      .retrieve(repoId, targetFilename)
      .failed
      .futureValue shouldBe Errors.TargetNotFoundError
  }

  test("reject putting offline signed targets.json without checksum if it exists already") {
    val repoId = addTargetToRepo()
    val signedPayload = buildSignedTargetsRole(repoId, offlineTargets)

    Put(apiUri(s"repo/${repoId.show}/targets"), signedPayload) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionRequired
    }
  }

  test("getting offline target item fails if no custom url was provided when signing target") {
    implicit val repoId = addTargetToRepo()
    val targetCustomJson = TargetCustom(
      TargetName("name"),
      TargetVersion("version"),
      Seq.empty,
      TargetFormat.BINARY.some
    ).asJson
    val hashes: ClientHashes = Map(
      HashMethod.SHA256 -> Refined
        .unsafeApply("8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4")
    )
    val offlineTargets =
      Map(offlineTargetFilename -> ClientTargetItem(hashes, 0, targetCustomJson.some))

    val signedPayload = buildSignedTargetsRole(repoId, offlineTargets)

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets/${offlineTargetFilename.value}")) ~> routes ~> check {
      status shouldBe StatusCodes.ExpectationFailed
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.NoUriForUnmanagedTarget
    }
  }

  test("getting offline target item redirects to custom url") {
    implicit val repoId = addTargetToRepo()

    val signedPayload = buildSignedTargetsRole(repoId, offlineTargets)

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets/${offlineTargetFilename.value}")) ~> routes ~> check {
      status shouldBe StatusCodes.Found
      header[Location].map(_.value()) should contain("https://ats.com")
    }
  }

  test("re-generates snapshot role after storing offline target") {
    implicit val repoId = addTargetToRepo()

    val signedPayload = buildSignedTargetsRole(repoId, offlineTargets)

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/snapshot.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[SnapshotRole]].signed.version shouldBe 2
    }
  }

  test("re-generates timestamp role after storing offline target") {
    implicit val repoId = addTargetToRepo()

    val signedPayload = buildSignedTargetsRole(repoId, offlineTargets)

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/timestamp.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TimestampRole]].signed.version shouldBe 2
    }
  }

  test("PUT offline target fails when target does not include custom meta") {
    implicit val repoId = addTargetToRepo()

    val targets = Map(offlineTargetFilename -> ClientTargetItem(Map.empty, 0, None))
    val signedPayload = buildSignedTargetsRole(repoId, targets)

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].firstErrorCause.get should include(
        "target item error some/file/name: new offline signed target items must contain custom metadata"
      )
    }
  }

  test("rejects requests with invalid/missing checksums") {
    val clientTargetItem = offlineTargets(offlineTargetFilename).copy(hashes = Map.empty)
    val targets = Map(offlineTargetFilename -> clientTargetItem)
    val signedPayload = buildSignedTargetsRole(repoId, targets)

    Put(apiUri(s"repo/${repoId.show}/targets"), signedPayload)
      .withValidTargetsCheckSum(repoId) ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].firstErrorCause.get should include("Invalid/Missing Checksum")
    }
  }

  test("accepts requests with no uri in target custom") {
    implicit val repoId = addTargetToRepo()

    val targetCustomJson = TargetCustom(
      TargetName("name"),
      TargetVersion("version"),
      Seq.empty,
      TargetFormat.BINARY.some
    ).asJson

    val hashes: ClientHashes = Map(
      HashMethod.SHA256 -> Refined
        .unsafeApply("8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4")
    )
    val targets = Map(offlineTargetFilename -> ClientTargetItem(hashes, 0, targetCustomJson.some))
    val signedPayload = buildSignedTargetsRole(repoId, targets)

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  test("accepts offline target uploaded by cli") {
    implicit val repoId = addTargetToRepo()

    val targetCustomJson = TargetCustom(
      TargetName("cli-uploaded"),
      TargetVersion("0.0.1"),
      Seq.empty,
      TargetFormat.BINARY.some,
      uri = None,
      cliUploaded = true.some
    ).asJson

    val hashes: ClientHashes = Map(
      HashMethod.SHA256 -> Refined
        .unsafeApply("8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4")
    )
    val targetFilename: TargetFilename = Refined.unsafeApply("cli-uploaded-0.0.1")

    val targets = Map(targetFilename -> ClientTargetItem(hashes, 0, targetCustomJson.some))
    val signedPayload = buildSignedTargetsRole(repoId, targets)

    // Fake "upload" by cli tool
    localStorage.store(repoId, targetFilename, Source.single(ByteString("cli file"))).futureValue

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets/cli-uploaded-0.0.1")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[ByteString].utf8String shouldBe "cli file"
    }
  }

  keyTypeTest("rejects offline targets.json with bad signatures") { keyType =>
    implicit val repoId = addTargetToRepo(keyType = keyType)

    val targetsRole = TargetsRole(Instant.now().plus(1, ChronoUnit.DAYS), Map.empty, 2)

    val invalidSignedPayload =
      fakeKeyserverClient.sign(repoId, TimestampRole(Map.empty, Instant.now, 0)).futureValue

    val signedPayload = JsonSignedPayload(invalidSignedPayload.signatures, targetsRole.asJson)

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].firstErrorCause.get should include(
        "role validation not found in authoritative role"
      )
    }
  }

  keyTypeTest("rejects offline targets.json with less signatures than the required threshold") {
    keyType =>
      implicit val repoId = addTargetToRepo(keyType = keyType)

      val targetsRole = TargetsRole(Instant.now().plus(1, ChronoUnit.DAYS), Map.empty, 2)

      val signedPayload = JsonSignedPayload(Seq.empty, targetsRole.asJson)

      Put(
        apiUri(s"repo/${repoId.show}/targets"),
        signedPayload
      ).withValidTargetsCheckSum ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[ErrorRepresentation].firstErrorCause.get should include(
          "Valid signature count must be >= threshold"
        )
      }
  }

  test("rejects offline targets.json if public keys are not available") {
    implicit val repoId = addTargetToRepo()

    val targetFilename: TargetFilename = Refined.unsafeApply("some/file/name")
    val targets = Map(targetFilename -> ClientTargetItem(Map.empty, 0, None))

    val targetsRole = TargetsRole(Instant.now().plus(1, ChronoUnit.DAYS), targets, 2)

    val Ed25519TufKeyPair(pub, sec) = TufCrypto.generateKeyPair(Ed25519KeyType, 256)
    val signature = TufCrypto.signPayload(sec, targetsRole.asJson).toClient(pub.id)
    val signedPayload = JsonSignedPayload(List(signature), targetsRole.asJson)

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].firstErrorCause.get should include(
        s"key ${pub.id} required for role validation not found in authoritative role"
      )
    }
  }

  test("return offline targets.json even if expired") {
    implicit val repoId = addTargetToRepo()

    val expiredTargetsRole = TargetsRole(Instant.now().minus(1, ChronoUnit.DAYS), offlineTargets, 2)
    val signedPayload = fakeKeyserverClient.sign(repoId, expiredTargetsRole).futureValue

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    // delete the key to simulate offline targets.json
    fakeKeyserverClient.deletePrivateKey(repoId, signedPayload.signatures.head.keyid).futureValue

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  test("fake keyserver client saves instants with same precision as json codecs") {
    val newRepoId = RepoId.generate()
    fakeKeyserverClient.createRoot(newRepoId).futureValue
    val raw = fakeKeyserverClient.fetchRootRole(newRepoId).futureValue.signed
    val rawJson = fakeKeyserverClient
      .fetchRootRole(newRepoId)
      .futureValue
      .signed
      .asJson
      .as[RootRole]
      .valueOr(throw _)

    raw.expires shouldBe rawJson.expires
  }

  keyTypeTest("delegates getting specific version of root.json to keyserver") { keyType =>
    val newRepoId = RepoId.generate()

    withRandomNamepace { implicit ns =>
      Post(
        apiUri(s"repo/${newRepoId.show}"),
        CreateRepositoryRequest(keyType)
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
    }

    val newRoot = Get(apiUri(s"repo/${newRepoId.show}/root.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[RootRole]]
    }

    Get(apiUri(s"repo/${newRepoId.show}/1.root.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[RootRole]].signed shouldBe newRoot.signed
    }
  }

  test("returns same json as pushed to the server when Decoder adds fields") {
    import io.circe.{parser => circe_parser}
    import cats.syntax.either._
    import com.advancedtelematic.libtuf.crypt.CanonicalJson._

    val str = """
                | {
                |  "_type": "Targets",
                |  "expires": "2219-12-13T15:37:21Z",
                |  "targets": {
                |    "myfile01": {
                |      "hashes": {
                |        "sha256": "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4"
                |      },
                |      "length": 2,
                |      "custom": null
                |    }
                |  },
                |  "version": 2
                |}
              """.stripMargin

    val oldJson = circe_parser.parse(str).flatMap(_.as[TargetsRole]).valueOr(throw _)

    implicit val repoId = addTargetToRepo()

    val signedPayload = fakeKeyserverClient.sign(repoId, oldJson).futureValue

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val newJson = responseAs[Json]
      newJson.canonical shouldBe signedPayload.asJson.canonical
    }
  }

  test("Uploading a TargetItem doesn't overwrite custom metadata added offline") {
    implicit val repoId = addTargetToRepo()

    val targets = createOfflineTargets(proprietary = Json.obj("proprietary" -> Json.True))
    val targetsRole = TargetsRole(Instant.now().plus(1, ChronoUnit.DAYS), targets, 2)
    val jsonSignedPayload = fakeKeyserverClient.sign(repoId, targetsRole).futureValue
    val signedPayload = SignedPayload(jsonSignedPayload.signatures, targetsRole, targetsRole.asJson)

    Put(
      apiUri(s"repo/${repoId.show}/targets"),
      signedPayload
    ).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Post(apiUri(s"repo/${repoId.show}/targets/myfile"), testFile) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val newJson = responseAs[JsonSignedPayload].signed
      newJson.hcursor
        .downField("targets")
        .downField("some/file/name")
        .downField("custom")
        .downField("proprietary") shouldBe Symbol("succeeded")
    }
  }

  test("PUT to uploads errors when using local storage") {
    val repoId = addTargetToRepo()

    Put(apiUri(s"repo/${repoId.show}/uploads/mytarget"))
      .withHeaders(`Content-Length`(1024)) ~> routes ~> check {
      status shouldBe StatusCodes.InternalServerError
      responseAs[
        ErrorRepresentation
      ].description shouldBe "out of band storage of target is not supported for local storage"
    }
  }

  test("PUT to uploads is rejected when file is too big") {
    val repoId = addTargetToRepo()

    Put(apiUri(s"repo/${repoId.show}/uploads/mytarget"))
      .withHeaders(`Content-Length`(3 * Math.pow(10, 9).toLong + 1)) ~> routes ~> check {
      status shouldBe StatusCodes.PayloadTooLarge
      responseAs[
        ErrorRepresentation
      ].code shouldBe com.advancedtelematic.libtuf.data.ErrorCodes.Reposerver.PayloadTooLarge
      responseAs[ErrorRepresentation].description should include("File being uploaded is too large")
    }
  }

  test("cannot upload a target that still exists in targets.json") {
    val repoId = addTargetToRepo()

    Put(
      apiUri(s"repo/${repoId.show}/targets/some/target/thing?name=name&version=version"),
      form
    ) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]]
    }

    Put(apiUri(s"repo/${repoId.show}/uploads/some/target/thing"))
      .withHeaders(`Content-Length`(1024)) ~> routes ~> check {
      status shouldBe StatusCodes.Conflict
      responseAs[ErrorRepresentation].description should include("Entity already exists")
    }
  }

  test("targets.json expire date is set according to expired-not-before") {
    withRandomNamepace { implicit ns =>
      createRepo()

      val notBefore = Instant.now().plus(30 * 6, ChronoUnit.DAYS)

      Put(
        apiUri(s"user_repo/targets/expire/not-before"),
        ExpireNotBeforeRequest(notBefore)
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.NoContent
      }

      Get(apiUri(s"user_repo/targets.json")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetsRole = responseAs[SignedPayload[TargetsRole]].signed
        targetsRole.expires.isAfter(notBefore.minusSeconds(1)) shouldBe true
      }
    }
  }

  test(
    "targets.json is refreshed on GET if current expire date is earlier than set expires-not-before"
  ) {
    withRandomNamepace { implicit ns =>
      createRepo()

      val notBefore = Instant.now().plus(30 * 6, ChronoUnit.DAYS)

      val initialVersion = Get(apiUri(s"user_repo/targets.json")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]].signed.version
      }

      Put(
        apiUri(s"user_repo/targets/expire/not-before"),
        ExpireNotBeforeRequest(notBefore)
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.NoContent
      }

      Get(apiUri(s"user_repo/targets.json")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetsRole = responseAs[SignedPayload[TargetsRole]].signed
        targetsRole.version shouldBe initialVersion + 1
        targetsRole.expires.isAfter(notBefore.minusSeconds(1)) shouldBe true
      }
    }
  }

  test("expire-not-before field is used to get upstream root.json") {
    withRandomNamepace { implicit ns =>
      createRepo()

      val notBefore = "2222-01-01T00:00:00Z"
      val notBeforeIs = Instant.parse(notBefore)

      Put(
        apiUri(s"user_repo/targets/expire/not-before"),
        ExpireNotBeforeRequest(notBeforeIs)
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.NoContent
      }

      Get(apiUri(s"user_repo/root.json")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetsRole = responseAs[SignedPayload[RootRole]].signed
        targetsRole.expires shouldBe notBeforeIs
      }
    }
  }

  test("targets.json expire date is set according to expires-not-before when adding a target") {
    withRandomNamepace { implicit ns =>
      createRepo()

      val notBefore = Instant.now().plus(30 * 6, ChronoUnit.DAYS)

      Get(apiUri(s"user_repo/targets.json")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Put(
        apiUri(s"user_repo/targets/expire/not-before"),
        ExpireNotBeforeRequest(notBefore)
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.NoContent
      }

      Put(
        apiUri(s"user_repo/targets/some/target/thing?name=name&version=version"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Get(apiUri(s"user_repo/targets.json")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetsRole = responseAs[SignedPayload[TargetsRole]].signed
        targetsRole.expires.isAfter(notBefore.minusSeconds(1)) shouldBe true
      }
    }
  }

  test("can fetch single targets_item") {
    withRandomNamepace { implicit ns =>
      createRepo()
      // Create package
      Put(
        apiUri(s"user_repo/targets/cheerios-0.0.5?name=cheerios&version=0.0.5"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }

      // fetch it
      Get(apiUri(s"user_repo/target_items/cheerios-0.0.5")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustom = responseAs[ClientTargetItem].custom.asJson.as[TargetCustom].value
        targetCustom.name.value shouldBe "cheerios"
        targetCustom.version.value shouldBe "0.0.5"
      }
    }
  }

  test("can fetch all target_items when pattern parameter is excluded") {
    withRandomNamepace { implicit ns =>
      createRepo()
      // create packages
      Put(
        apiUri(s"user_repo/targets/cheerios-0.0.5?name=cheerios&version=0.0.5"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }
      Put(
        apiUri(s"user_repo/targets/cheerios-0.0.6?name=cheerios&version=0.0.6"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }
      Put(
        apiUri(s"user_repo/targets/riceKrispies-0.0.1?name=riceKrispies&version=0.0.1"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }
      // fetch them
      Get(apiUri(s"user_repo/target_items")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustoms = responseAs[PaginationResult[ClientTargetItem]].map { clientTargetItem =>
          clientTargetItem.custom.asJson.as[TargetCustom] match {
            case Right(custom) => custom
            case Left(err) => println(s"Failed to parse json. Error: ${err.toString}"); throw err
          }
        }
        val nameVersionTuple =
          targetCustoms.values.map(custom => (custom.name.value, custom.version.value))
        nameVersionTuple should contain("cheerios", "0.0.5")
        nameVersionTuple should contain("cheerios", "0.0.6")
        nameVersionTuple should contain("riceKrispies", "0.0.1")
      }
    }
  }

  test("Use pagination query params when fetching target_items") {
    withRandomNamepace { implicit ns =>
      createRepo()
      // create packages
      (1 to 100 by 1).foreach { idx =>
        Put(
          apiUri(
            s"user_repo/targets/riceKrispies-0.0." + idx + "?name=riceKrispies&version=0.0." + idx
          ),
          form
        ).namespaced ~> routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[SignedPayload[TargetsRole]]
        }
      }
      Get(apiUri(s"user_repo/target_items")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val paged = responseAs[PaginationResult[ClientTargetItem]]
        paged.total shouldBe 100
        paged.offset shouldBe 0.toOffset // default
        paged.limit shouldBe 50.toLimit // default
        paged.values.length shouldEqual paged.limit.toLong
        val targetCustoms = paged.map { clientTargetItem =>
          clientTargetItem.custom.asJson.as[TargetCustom] match {
            case Right(custom) => custom
            case Left(err) => println(s"Failed to parse json. Error: ${err.toString}"); throw err
          }
        }
        val nameVersionTuple =
          targetCustoms.values.map(custom => (custom.name.value, custom.version.value))
        (1 to 50 by 1).foreach { idx =>
          nameVersionTuple should contain("riceKrispies", "0.0." + idx)
        }
        (51 to 100 by 1).foreach { idx =>
          nameVersionTuple should not contain ("riceKrispies", "0.0." + idx)
        }
      }
      Get(apiUri(s"user_repo/target_items?offset=1")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val paged = responseAs[PaginationResult[ClientTargetItem]]
        println(paged)
        paged.total shouldBe 100
        paged.offset shouldBe 1.toOffset
        paged.limit shouldBe 50.toLimit // default
        paged.values.length shouldEqual paged.limit.toLong
        val targetCustoms = paged.map { clientTargetItem =>
          clientTargetItem.custom.asJson.as[TargetCustom] match {
            case Right(custom) => custom
            case Left(err) => println(s"Failed to parse json. Error: ${err.toString}"); throw err
          }
        }
        val nameVersionTuple =
          targetCustoms.values.map(custom => (custom.name.value, custom.version.value))
        nameVersionTuple should not contain ("riceKrispies", "0.0.1")
        (2 to 51 by 1).foreach { idx =>
          nameVersionTuple should contain("riceKrispies", "0.0." + idx)
        }
        (52 to 100 by 1).foreach { idx =>
          nameVersionTuple should not contain ("riceKrispies", "0.0." + idx)
        }
      }
      Get(apiUri(s"user_repo/target_items?limit=2")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val paged = responseAs[PaginationResult[ClientTargetItem]]
        paged.total shouldBe 100
        paged.offset shouldBe 0.toOffset
        paged.limit shouldBe 2.toLimit
        paged.values.length shouldEqual paged.limit.toLong
        val targetCustoms = paged.map { clientTargetItem =>
          clientTargetItem.custom.asJson.as[TargetCustom] match {
            case Right(custom) => custom
            case Left(err) => println(s"Failed to parse json. Error: ${err.toString}"); throw err
          }
        }
        val nameVersionTuple =
          targetCustoms.values.map(custom => (custom.name.value, custom.version.value))
        nameVersionTuple should contain("riceKrispies", "0.0.1")
        nameVersionTuple should contain("riceKrispies", "0.0.2")
        (3 to 100 by 1).foreach { idx =>
          nameVersionTuple should not contain ("riceKrispies", "0.0." + idx)
        }
      }
      Get(apiUri(s"user_repo/target_items?offset=30&limit=30")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val paged = responseAs[PaginationResult[ClientTargetItem]]
        paged.total shouldBe 100
        paged.offset shouldBe 30.toOffset
        paged.limit shouldBe 30.toLimit
        paged.values.length shouldEqual paged.limit.toLong
        val targetCustoms = paged.map { clientTargetItem =>
          clientTargetItem.custom.asJson.as[TargetCustom] match {
            case Right(custom) => custom
            case Left(err) => println(s"Failed to parse json. Error: ${err.toString}"); throw err
          }
        }
        val nameVersionTuple =
          targetCustoms.values.map(custom => (custom.name.value, custom.version.value))
        (1 to 30 by 1).foreach { idx =>
          nameVersionTuple should not contain ("riceKrispies", "0.0." + idx)
        }
        (31 to 60 by 1).foreach { idx =>
          nameVersionTuple should contain("riceKrispies", "0.0." + idx)
        }
      }
      Get(apiUri(s"user_repo/target_items?offset=30&limit=60")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val paged = responseAs[PaginationResult[ClientTargetItem]]
        paged.total shouldBe 100
        paged.offset shouldBe 30.toOffset
        paged.limit shouldBe 60.toLimit
        paged.values.length shouldEqual paged.limit.toLong
        val targetCustoms = paged.map { clientTargetItem =>
          clientTargetItem.custom.asJson.as[TargetCustom] match {
            case Right(custom) => custom
            case Left(err) => println(s"Failed to parse json. Error: ${err.toString}"); throw err
          }
        }
        val nameVersionTuple =
          targetCustoms.values.map(custom => (custom.name.value, custom.version.value))
        (1 to 30 by 1).foreach { idx =>
          nameVersionTuple should not contain ("riceKrispies", "0.0." + idx)
        }
        (31 to 90 by 1).foreach { idx =>
          nameVersionTuple should contain("riceKrispies", "0.0." + idx)
        }
      }
      Get(apiUri(s"user_repo/target_items?offset=30&limit=90")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val paged = responseAs[PaginationResult[ClientTargetItem]]
        paged.total shouldBe 100
        paged.offset shouldBe 30.toOffset
        paged.limit shouldBe 90.toLimit
        paged.values.length shouldEqual 70 // 100 (total) - 30 (offset)
        val targetCustoms = paged.map { clientTargetItem =>
          clientTargetItem.custom.asJson.as[TargetCustom] match {
            case Right(custom) => custom
            case Left(err) => println(s"Failed to parse json. Error: ${err.toString}"); throw err
          }
        }
        val nameVersionTuple =
          targetCustoms.values.map(custom => (custom.name.value, custom.version.value))
        (1 to 30 by 1).foreach { idx =>
          nameVersionTuple should not contain ("riceKrispies", "0.0." + idx)
        }
        (31 to 100 by 1).foreach { idx =>
          nameVersionTuple should contain("riceKrispies", "0.0." + idx)
        }
      }
    }
  }

  test("can search target_items with pattern and get expected output") {
    withRandomNamepace { implicit ns =>
      createRepo()
      // create packages
      Put(
        apiUri(s"user_repo/targets/cheerios-0.0.5?name=cheerios&version=0.0.5"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }
      Put(
        apiUri(s"user_repo/targets/cheerios-0.0.6?name=cheerios&version=0.0.6"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }
      Put(
        apiUri(s"user_repo/targets/riceKrispies-0.0.1?name=riceKrispies&version=0.0.1"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }
      // fetch them
      Get(apiUri(s"user_repo/target_items?nameContains=cheerios")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustoms = responseAs[PaginationResult[ClientTargetItem]].map { clientTargetItem =>
          clientTargetItem.custom.asJson.as[TargetCustom] match {
            case Right(custom) => custom
            case Left(err) => println(s"Failed to parse json. Error: ${err.toString}"); throw err
          }
        }
        val nameVersionTuple =
          targetCustoms.values.map(custom => (custom.name.value, custom.version.value))
        nameVersionTuple should contain("cheerios", "0.0.5")
        nameVersionTuple should contain("cheerios", "0.0.6")
        nameVersionTuple should not contain ("riceKrispies", "0.0.1")
      }
    }
  }

  test("can edit single targets_item uri") {
    withRandomNamepace { implicit ns =>
      createRepo()
      // Create package
      Put(
        apiUri("user_repo/targets/cheerios-0.0.5?name=cheerios&version=0.0.5"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }
      // fetch it
      Get(apiUri("user_repo/target_items/cheerios-0.0.5")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustom = responseAs[ClientTargetItem].custom.asJson.as[TargetCustom].value
        targetCustom.name.value shouldBe "cheerios"
        targetCustom.version.value shouldBe "0.0.5"
        targetCustom.uri shouldBe empty
        targetCustom.hardwareIds shouldBe empty
        targetCustom.proprietary.asObject.map(_.isEmpty shouldBe true)
      }
      // edit it
      val testUri = URI.create("https://toradex.com")
      val editBody = EditTargetItem(uri = Some(testUri))
      Patch(apiUri("user_repo/targets/cheerios-0.0.5"), editBody).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustom = responseAs[ClientTargetItem].custom.asJson.as[TargetCustom].value
        targetCustom.name.value shouldBe "cheerios"
        targetCustom.version.value shouldBe "0.0.5"
        targetCustom.uri shouldBe Some(testUri)
        targetCustom.hardwareIds shouldBe empty
        targetCustom.proprietary.asObject.map(_.isEmpty shouldBe true)
      }
      // fetch it
      Get(apiUri("user_repo/target_items/cheerios-0.0.5")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustom = responseAs[ClientTargetItem].custom.asJson.as[TargetCustom].value
        targetCustom.name.value shouldBe "cheerios"
        targetCustom.version.value shouldBe "0.0.5"
        targetCustom.uri shouldBe Some(testUri)
        targetCustom.hardwareIds shouldBe empty
        targetCustom.proprietary.asObject.map(_.isEmpty shouldBe true)
      }
    }
  }

  test("can edit single targets_item hardwareIds") {
    withRandomNamepace { implicit ns =>
      createRepo()
      // Create package
      Put(
        apiUri("user_repo/targets/cheerios-0.0.5?name=cheerios&version=0.0.5"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }
      // fetch it
      Get(apiUri("user_repo/target_items/cheerios-0.0.5")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustom = responseAs[ClientTargetItem].custom.asJson.as[TargetCustom].value
        targetCustom.name.value shouldBe "cheerios"
        targetCustom.version.value shouldBe "0.0.5"
        targetCustom.uri shouldBe empty
        targetCustom.hardwareIds shouldBe empty
        targetCustom.proprietary.asObject.map(_.isEmpty shouldBe true)
      }
      val editBody =
        EditTargetItem(hardwareIds = Seq[HardwareIdentifier](Refined.unsafeApply("foo")))
      Patch(apiUri("user_repo/targets/cheerios-0.0.5"), editBody).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustom = responseAs[ClientTargetItem].custom.asJson.as[TargetCustom].value
        targetCustom.name.value shouldBe "cheerios"
        targetCustom.version.value shouldBe "0.0.5"
        targetCustom.uri shouldBe empty
        targetCustom.hardwareIds should contain(Refined.unsafeApply("foo"))
        targetCustom.proprietary.asObject.map(_.isEmpty shouldBe true)
      }
      // fetch it
      Get(apiUri("user_repo/target_items/cheerios-0.0.5")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustom = responseAs[ClientTargetItem].custom.asJson.as[TargetCustom].value
        targetCustom.name.value shouldBe "cheerios"
        targetCustom.version.value shouldBe "0.0.5"
        targetCustom.uri shouldBe empty
        targetCustom.hardwareIds should contain(Refined.unsafeApply("foo"))
        targetCustom.proprietary.asObject.map(_.isEmpty shouldBe true)
      }
    }
  }

  test("can edit single targets_item proprietary custom json") {
    withRandomNamepace { implicit ns =>
      createRepo()
      // Create package
      Put(
        apiUri("user_repo/targets/cheerios-0.0.5?name=cheerios&version=0.0.5"),
        form
      ).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SignedPayload[TargetsRole]]
      }
      // fetch it
      Get(apiUri("user_repo/target_items/cheerios-0.0.5")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustom = responseAs[ClientTargetItem].custom.asJson.as[TargetCustom].value
        targetCustom.name.value shouldBe "cheerios"
        targetCustom.version.value shouldBe "0.0.5"
        targetCustom.uri shouldBe empty
        targetCustom.hardwareIds shouldBe empty
        targetCustom.proprietary.asObject.map(_.isEmpty shouldBe true)
      }
      // edit it
      val editBody =
        EditTargetItem(proprietaryCustom = Some(Map[String, String]("foo" -> "bar").asJson))
      Patch(apiUri("user_repo/targets/cheerios-0.0.5"), editBody).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustom = responseAs[ClientTargetItem].custom.asJson.as[TargetCustom].value
        targetCustom.name.value shouldBe "cheerios"
        targetCustom.version.value shouldBe "0.0.5"
        targetCustom.uri shouldBe empty
        targetCustom.hardwareIds shouldBe empty
        println(s"BEN SAYS, proprietary json is: ${targetCustom.proprietary.noSpaces}")
        targetCustom.proprietary.asObject.map(_.isEmpty shouldBe false)
      }
      // fetch it
      Get(apiUri("user_repo/target_items/cheerios-0.0.5")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val targetCustom = responseAs[ClientTargetItem].custom.asJson.as[TargetCustom].value
        targetCustom.name.value shouldBe "cheerios"
        targetCustom.version.value shouldBe "0.0.5"
        targetCustom.uri shouldBe empty
        targetCustom.hardwareIds shouldBe empty
        targetCustom.proprietary.asObject.map(_.isEmpty shouldBe false)
      }
    }
  }

  test("rotating root generates root and targets") {
    val repoId = addTargetToRepo()

    val oldTargets = Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed
    }

    val oldRoot = Get(apiUri(s"repo/${repoId.show}/root.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[RootRole]].signed
    }

    Put(apiUri(s"repo/${repoId.show}/root/rotate")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val updatedRole = responseAs[SignedPayload[TargetsRole]].signed

      updatedRole.version shouldBe oldTargets.version + 1
    }

    Get(apiUri(s"repo/${repoId.show}/root.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val updatedRole = responseAs[SignedPayload[RootRole]].signed

      updatedRole.version shouldBe oldRoot.version + 1
    }
  }

  test("rotate returns 412 when key is offline") {
    val repoId = RepoId.generate()
    fakeKeyserverClient.createRoot(repoId).futureValue

    val root = fakeKeyserverClient.fetchRootRole(repoId).futureValue

    fakeKeyserverClient
      .deletePrivateKey(repoId, root.signed.roles(RoleType.ROOT).keyids.head)
      .futureValue

    Put(apiUri(s"repo/${repoId.show}/root/rotate")) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
    }
  }

  test("returns canonicalized root.json") {
    val responseBody = Get(apiUri(s"repo/${repoId.show}/root.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[ByteString].utf8String
    }
    val parsedJson = parse(responseBody).getOrElse(fail(s"Failed to parse response: $responseBody"))
    responseBody shouldBe parsedJson.canonical
  }

  test("returns canonicalized 1.root.json") {
    val responseBody = Get(apiUri(s"repo/${repoId.show}/1.root.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[ByteString].utf8String
    }
    val parsedJson = parse(responseBody).getOrElse(fail(s"Failed to parse response: $responseBody"))
    responseBody shouldBe parsedJson.canonical
  }

  test("returns canonicalized targets.json") {
    addTargetToRepo()
    val responseBody = Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[ByteString].utf8String
    }
    val parsedJson = parse(responseBody).getOrElse(fail(s"Failed to parse response: $responseBody"))
    responseBody shouldBe parsedJson.canonical
  }

  test("returns canonicalized snapshot.json") {
    addTargetToRepo()
    val responseBody = Get(apiUri(s"repo/${repoId.show}/snapshot.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[ByteString].utf8String
    }
    val parsedJson = parse(responseBody).getOrElse(fail(s"Failed to parse response: $responseBody"))
    responseBody shouldBe parsedJson.canonical
  }

  test("returns canonicalized timestamp.json") {
    addTargetToRepo()
    val responseBody = Get(apiUri(s"repo/${repoId.show}/timestamp.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[ByteString].utf8String
    }
    val parsedJson = parse(responseBody).getOrElse(fail(s"Failed to parse response: $responseBody"))
    responseBody shouldBe parsedJson.canonical
  }

  test("PUT /trusted-delegations produces same metadata online and offline") {
    val repoId1 = addTargetToRepo()
    val repoId2 = addTargetToRepo()
    
    // Set up both repos identically: add trusted delegation keys
    val newKeys = Ed25519KeyType.crypto.generateKeyPair()
    Put(apiUri(s"repo/${repoId1.show}/trusted-delegations/keys"), List(newKeys.pubkey).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    Put(apiUri(s"repo/${repoId2.show}/trusted-delegations/keys"), List(newKeys.pubkey).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    
    // Take keys offline for repoId1
    val root1 = fakeKeyserverClient.fetchRootRole(repoId1).futureValue
    fakeKeyserverClient.deletePrivateKey(repoId1, root1.signed.roles(RoleType.TARGETS).keyids.head).futureValue
    
    // Create the new delegation
    val delegation = Delegation(
      name = "test-delegation".unsafeApply[DelegatedRoleName],
      keyids = List(newKeys.pubkey.id),
      threshold = 1,
      terminating = false,
      paths = List.empty
    )
    
    // Perform operation on repoId1 (offline keys) - get metadata from error response
    val offlineMetadata = Put(apiUri(s"repo/${repoId1.show}/trusted-delegations"), List(delegation).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
      extractTargetsRoleFromError(responseAs[ErrorRepresentation])
    }
    
    // Perform operation on repoId2 (online keys) - get metadata from success response
    Put(apiUri(s"repo/${repoId2.show}/trusted-delegations"), List(delegation).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    val onlineMetadata = Get(apiUri(s"repo/${repoId2.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed
    }
    
    // Compare signed portions
    assertOfflineMetadataMatches(offlineMetadata, onlineMetadata)
  }

  test("DELETE /trusted-delegations/{name} produces same metadata online and offline") {
    val repoId1 = addTargetToRepo()
    val repoId2 = addTargetToRepo()
    
    // Set up both repos identically: add keys and delegations
    val newKeys = Ed25519KeyType.crypto.generateKeyPair()
    Put(apiUri(s"repo/${repoId1.show}/trusted-delegations/keys"), List(newKeys.pubkey).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    Put(apiUri(s"repo/${repoId2.show}/trusted-delegations/keys"), List(newKeys.pubkey).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    val delegation1 = Delegation(
      name = "delegation-1".unsafeApply[DelegatedRoleName],
      keyids = List(newKeys.pubkey.id),
      threshold = 1,
      terminating = false,
      paths = List.empty
    )
    val delegation2 = Delegation(
      name = "delegation-2".unsafeApply[DelegatedRoleName],
      keyids = List(newKeys.pubkey.id),
      threshold = 1,
      terminating = false,
      paths = List.empty
    )

    // Add both delegations to both repos
    Put(apiUri(s"repo/${repoId1.show}/trusted-delegations"), List(delegation1, delegation2).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    Put(apiUri(s"repo/${repoId2.show}/trusted-delegations"), List(delegation1, delegation2).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    
    // Take keys offline for repoId1
    val root1 = fakeKeyserverClient.fetchRootRole(repoId1).futureValue
    fakeKeyserverClient.deletePrivateKey(repoId1, root1.signed.roles(RoleType.TARGETS).keyids.head).futureValue
    
    // Perform operation on repoId1 (offline keys) - get metadata from error response
    val offlineMetadata = Delete(apiUri(s"repo/${repoId1.show}/trusted-delegations/${delegation1.name.value}")) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
      extractTargetsRoleFromError(responseAs[ErrorRepresentation])
    }
    
    // Perform operation on repoId2 (online keys) - get metadata from success response
    Delete(apiUri(s"repo/${repoId2.show}/trusted-delegations/${delegation1.name.value}")) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    val onlineMetadata = Get(apiUri(s"repo/${repoId2.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed
    }
    
    // Compare signed portions
    assertOfflineMetadataMatches(offlineMetadata, onlineMetadata)
  }

  test("PUT /trusted-delegations/keys produces same metadata online and offline") {
    val repoId1 = addTargetToRepo()
    val repoId2 = addTargetToRepo()
    
    // Set up both repos identically: add initial keys and delegation
    val initialKeys = Ed25519KeyType.crypto.generateKeyPair()
    Put(apiUri(s"repo/${repoId1.show}/trusted-delegations/keys"), List(initialKeys.pubkey).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    Put(apiUri(s"repo/${repoId2.show}/trusted-delegations/keys"), List(initialKeys.pubkey).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    val delegation = Delegation(
      name = "test-delegation".unsafeApply[DelegatedRoleName],
      keyids = List(initialKeys.pubkey.id),
      threshold = 1,
      terminating = false,
      paths = List.empty
    )

    Put(apiUri(s"repo/${repoId1.show}/trusted-delegations"), List(delegation).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    Put(apiUri(s"repo/${repoId2.show}/trusted-delegations"), List(delegation).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    
    // Take keys offline for repoId1
    val root1 = fakeKeyserverClient.fetchRootRole(repoId1).futureValue
    fakeKeyserverClient.deletePrivateKey(repoId1, root1.signed.roles(RoleType.TARGETS).keyids.head).futureValue
    
    // Create new keys
    val newKeys = Ed25519KeyType.crypto.generateKeyPair()
    
    // Perform operation on repoId1 (offline keys) - get metadata from error response
    val offlineMetadata = Put(apiUri(s"repo/${repoId1.show}/trusted-delegations/keys"), List(newKeys.pubkey).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
      extractTargetsRoleFromError(responseAs[ErrorRepresentation])
    }
    
    // Perform operation on repoId2 (online keys) - get metadata from success response
    Put(apiUri(s"repo/${repoId2.show}/trusted-delegations/keys"), List(newKeys.pubkey).asJson) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    val onlineMetadata = Get(apiUri(s"repo/${repoId2.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed
    }
    
    // Compare signed portions
    assertOfflineMetadataMatches(offlineMetadata, onlineMetadata)
  }

  test("PUT /targets/{filename} produces same metadata online and offline") {
    val repoId1 = RepoId.generate()
    val repoId2 = RepoId.generate()
    fakeKeyserverClient.createRoot(repoId1).futureValue
    fakeKeyserverClient.createRoot(repoId2).futureValue

    // Set up both repos identically: add a target first to ensure the targets role exists
    val initialFileContent = ByteString(Array.fill(512)(0x41.toByte))
    Put(
      apiUri(s"repo/${repoId1.show}/targets/initialfile?name=initial&version=1.0.0"),
      HttpEntity(ContentTypes.`application/octet-stream`, initialFileContent)
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    Put(
      apiUri(s"repo/${repoId2.show}/targets/initialfile?name=initial&version=1.0.0"),
      HttpEntity(ContentTypes.`application/octet-stream`, initialFileContent)
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    // Take keys offline for repoId1
    val root1 = fakeKeyserverClient.fetchRootRole(repoId1).futureValue
    fakeKeyserverClient.deletePrivateKey(repoId1, root1.signed.roles(RoleType.TARGETS).keyids.head).futureValue

    // Create a 1KB binary file
    val fileSize = 1024
    val fileContent = ByteString(Array.fill(fileSize)(0x42.toByte))
    val filename = "test-binary-file.bin"
    val targetFilename = filename.refineTry[ValidTargetFilename].get

    // Perform operation on repoId1 (offline keys) - get metadata from error response
    val offlineMetadata = Put(
      apiUri(s"repo/${repoId1.show}/targets/$filename?name=test-target&version=1.0.0"),
      HttpEntity(ContentTypes.`application/octet-stream`, fileContent)
    ) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
      extractTargetsRoleFromError(responseAs[ErrorRepresentation])
    }

    // Perform operation on repoId2 (online keys) - get metadata from success response
    Put(
      apiUri(s"repo/${repoId2.show}/targets/$filename?name=test-target&version=1.0.0"),
      HttpEntity(ContentTypes.`application/octet-stream`, fileContent)
    ) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    val onlineMetadata = Get(apiUri(s"repo/${repoId2.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed
    }

    // Compare signed portions
    assertOfflineMetadataMatches(offlineMetadata, onlineMetadata)

    // Verify the file WAS stored in both repos (binary should be persisted even if keys are offline)
    val storedFile1 = localStorage.retrieve(repoId1, targetFilename).futureValue
    storedFile1 match {
      case TargetBytes(bytes, size) =>
        val retrievedContent = bytes.runReduce(_ ++ _).futureValue
        retrievedContent shouldBe fileContent
        retrievedContent.length shouldBe fileSize
        size shouldBe fileSize
      case _ => fail("Expected TargetBytes but got TargetRedirect")
    }

    val storedFile2 = localStorage.retrieve(repoId2, targetFilename).futureValue
    storedFile2 match {
      case TargetBytes(bytes, size) =>
        val retrievedContent = bytes.runReduce(_ ++ _).futureValue
        retrievedContent shouldBe fileContent
        retrievedContent.length shouldBe fileSize
        size shouldBe fileSize
      case _ => fail("Expected TargetBytes but got TargetRedirect")
    }

    // Verify the target was NOT persisted to the TargetItems table for repoId1 (no side effects in metadata)
    targetItemRepo.exists(repoId1, targetFilename).futureValue shouldBe false
    // But it WAS persisted for repoId2 (online keys)
    targetItemRepo.exists(repoId2, targetFilename).futureValue shouldBe true
  }

  test("POST /targets produces same metadata online and offline") {
    val repoId1 = addTargetToRepo()
    val repoId2 = addTargetToRepo()
    
    // Take keys offline for repoId1
    val root1 = fakeKeyserverClient.fetchRootRole(repoId1).futureValue
    fakeKeyserverClient.deletePrivateKey(repoId1, root1.signed.roles(RoleType.TARGETS).keyids.head).futureValue
    
    val newFilename = "new-target-file".refineTry[ValidTargetFilename].get
    val newChecksum = Sha256Digest.digest("new content".getBytes)
    val testUri = Uri("https://ats.com/newfile")
    val targetName = TargetName("newtarget")
    val targetVersion = TargetVersion("1.0.0")
    val requestItem = testFile.copy(
      uri = testUri,
      name = targetName.some,
      version = targetVersion.some,
      checksum = newChecksum,
      length = "new content".getBytes.length
    )
    
    // Perform operation on repoId1 (offline keys) - get metadata from error response
    val offlineMetadata = Post(apiUri(s"repo/${repoId1.show}/targets/${newFilename.value}"), requestItem) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
      extractTargetsRoleFromError(responseAs[ErrorRepresentation])
    }
    
    // Perform operation on repoId2 (online keys) - get metadata from success response
    val onlineMetadata = Post(apiUri(s"repo/${repoId2.show}/targets/${newFilename.value}"), requestItem) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[JsonSignedPayload].signed.as[TargetsRole].valueOr(throw _)
    }
    
    // Compare signed portions
    assertOfflineMetadataMatches(offlineMetadata, onlineMetadata)
  }

  test("DELETE /targets produces same metadata online and offline") {
    val repoId1 = addTargetToRepo()
    val repoId2 = addTargetToRepo()
    
    // Take keys offline for repoId1
    val root1 = fakeKeyserverClient.fetchRootRole(repoId1).futureValue
    fakeKeyserverClient.deletePrivateKey(repoId1, root1.signed.roles(RoleType.TARGETS).keyids.head).futureValue
    
    val filenameToDelete = "myfile01".refineTry[ValidTargetFilename].get
    
    // Perform operation on repoId1 (offline keys) - get metadata from error response
    val offlineMetadata = Delete(apiUri(s"repo/${repoId1.show}/targets/${filenameToDelete.value}")) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
      extractTargetsRoleFromError(responseAs[ErrorRepresentation])
    }
    
    // Perform operation on repoId2 (online keys) - get metadata from success response
    Delete(apiUri(s"repo/${repoId2.show}/targets/${filenameToDelete.value}")) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    val onlineMetadataAfter = Get(apiUri(s"repo/${repoId2.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed
    }
    
    // Compare signed portions
    assertOfflineMetadataMatches(offlineMetadata, onlineMetadataAfter)
    
    // Verify the target was NOT deleted from the TargetItems table for repoId1 (no side effects)
    val targetItem1 = targetItemRepo.findByFilename(repoId1, filenameToDelete).futureValue
    targetItem1.filename shouldBe filenameToDelete
    
    // But it WAS deleted for repoId2 (online keys)
    targetItemRepo.exists(repoId2, filenameToDelete).futureValue shouldBe false
  }

  test("PATCH /targets produces same metadata online and offline") {
    val repoId1 = addTargetToRepo()
    val repoId2 = addTargetToRepo()
    
    // Take keys offline for repoId1
    val root1 = fakeKeyserverClient.fetchRootRole(repoId1).futureValue
    fakeKeyserverClient.deletePrivateKey(repoId1, root1.signed.roles(RoleType.TARGETS).keyids.head).futureValue
    
    val filenameToEdit = "myfile01".refineTry[ValidTargetFilename].get
    val testUri = URI.create("https://toradex.com")
    val editRequest = EditTargetItem(
      uri = Some(testUri),
      hardwareIds = Seq[HardwareIdentifier](Refined.unsafeApply("hw1")),
      proprietaryCustom = Some(Json.obj("test" -> "value".asJson))
    )
    
    // Perform operation on repoId1 (offline keys) - get metadata from error response
    val offlineMetadata = Patch(apiUri(s"repo/${repoId1.show}/targets/${filenameToEdit.value}"), editRequest) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
      extractTargetsRoleFromError(responseAs[ErrorRepresentation])
    }
    
    // Perform operation on repoId2 (online keys) - get metadata from success response
    Patch(apiUri(s"repo/${repoId2.show}/targets/${filenameToEdit.value}"), editRequest) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
    val onlineMetadata = Get(apiUri(s"repo/${repoId2.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed
    }
    
    // Compare signed portions
    assertOfflineMetadataMatches(offlineMetadata, onlineMetadata)
  }

  test("PATCH /proprietary-custom produces same metadata online and offline") {
    val repoId1 = addTargetToRepo()
    val repoId2 = addTargetToRepo()
    
    // Take keys offline for repoId1
    val root1 = fakeKeyserverClient.fetchRootRole(repoId1).futureValue
    fakeKeyserverClient.deletePrivateKey(repoId1, root1.signed.roles(RoleType.TARGETS).keyids.head).futureValue
    
    val filenameToUpdate = "myfile01".refineTry[ValidTargetFilename].get
    val proprietaryUpdate = Json.obj("newkey" -> "newvalue".asJson, "nested" -> Json.obj("inner" -> 42.asJson))
    
    // Perform operation on repoId1 (offline keys) - get metadata from error response
    val offlineMetadata = Patch(apiUri(s"repo/${repoId1.show}/proprietary-custom/${filenameToUpdate.value}"), proprietaryUpdate) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
      extractTargetsRoleFromError(responseAs[ErrorRepresentation])
    }
    
    // Perform operation on repoId2 (online keys) - get metadata from success response
    Patch(apiUri(s"repo/${repoId2.show}/proprietary-custom/${filenameToUpdate.value}"), proprietaryUpdate) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
    val onlineMetadata = Get(apiUri(s"repo/${repoId2.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed
    }
    
    // Compare signed portions
    assertOfflineMetadataMatches(offlineMetadata, onlineMetadata)
  }

  implicit class ErrorRepresentationOps(value: ErrorRepresentation) {

    def firstErrorCause: Option[String] =
      value.cause.flatMap(_.as[NonEmptyList[String]].toOption).map(_.head)

  }

}
