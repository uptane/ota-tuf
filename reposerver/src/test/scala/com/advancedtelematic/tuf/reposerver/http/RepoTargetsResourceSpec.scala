package com.advancedtelematic.tuf.reposerver.http

import com.advancedtelematic.tuf.reposerver.data.RepoCodecs.*
import org.scalatest.LoneElement.*
import cats.implicits.*
import io.circe.syntax.*
import org.scalatest.OptionValues.*
import akka.http.scaladsl.model.{HttpEntity, StatusCodes}
import akka.util.ByteString
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.tuf.reposerver.util.{
  RepoResourceDelegationsSpecUtil,
  ResourceSpec,
  TufReposerverSpec
}
import com.advancedtelematic.tuf.reposerver.util.NamespaceSpecOps.*
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport.*
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.*
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.Package.*
import com.advancedtelematic.libats.data.DataType.HashMethod
import com.advancedtelematic.libtuf.data.ClientDataType.ClientTargetItem
import com.advancedtelematic.libtuf.data.TufDataType.TargetFilename
import com.advancedtelematic.libtuf_server.crypto.Sha256Digest
import eu.timepit.refined.api.Refined
import io.circe.Json

class RepoTargetsResourceSpec
    extends TufReposerverSpec
    with ResourceSpec
    with RepoResourceDelegationsSpecUtil {

  val testEntity = HttpEntity(ByteString("""
       The library will endure; it is the universe. As for us, everything has not been written; we are not turning into phantoms. We walk the corridors, searching the shelves and rearranging them, looking for lines of meaning amid leagues of cacophony and incoherence, reading the history of the past and our future, collecting our thoughts and collecting the thoughts of others, and every so often glimpsing mirrors, in which we may recognize creatures of the information.”
      """.stripMargin))

  testWithRepo("GET <TBD> returns delegation items ") { implicit ns => implicit repoId =>
    addTargetToRepo(repoId)

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    Put(
      apiUri("user_repo/targets/mypkg?name=library&version=0.0.1&hardwareIds=myid001"),
      testEntity
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUriV2(s"user_repo/search")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val values = responseAs[PaginationResult[Package]].values

      values should have size 2

      val targetItem = values.find(_.name.value == "library").value

      targetItem.version.value shouldBe "0.0.1"
      targetItem.hardwareIds.map(_.value) should contain("myid001")

      val delegatedItem = values.find(_.name.value == "mytargetName").value

      delegatedItem.version.value shouldBe "0.0.2"
      delegatedItem.hardwareIds.map(_.value) should contain("delegated-hardware-id-001")

      responseAs[PaginationResult[Package]].total shouldBe 2
    }

  }

  testWithRepo("deleted items are no longer returned") { implicit ns => implicit repoId =>
    addTargetToRepo(repoId)

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    Get(apiUriV2(s"user_repo/search")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values should have size 1
    }

    val signedDelegationRole1 = buildSignedDelegatedTargets(targets = Map.empty)

    pushSignedDelegatedMetadataOk(signedDelegationRole1)

    Get(apiUriV2(s"user_repo/search")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val values = responseAs[PaginationResult[Package]].values

      values should be(empty)
    }

  }

  testWithRepo("delegated items are updated when delegated metadata is updated") {
    implicit ns => implicit repoId =>
      addTargetToRepo(repoId)

      uploadOfflineSignedTargetsRole()

      val signedDelegationRole = buildSignedDelegatedTargets()

      pushSignedDelegatedMetadataOk(signedDelegationRole)

      Get(apiUriV2(s"user_repo/search")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val values = responseAs[PaginationResult[Package]].values
        values should have size 1
      }

      val newTargets: Map[TargetFilename, ClientTargetItem] = Map(
        Refined.unsafeApply("mypath/othertarget") -> ClientTargetItem(
          Map(HashMethod.SHA256 -> Sha256Digest.digest("hi".getBytes).hash),
          2,
          Json
            .obj(
              "name" -> "customname".asJson,
              "version" -> "0.0.3".asJson,
              "hardwareIds" -> List("delegated-hardware-id-001").asJson
            )
            .some
        )
      )

      val signedDelegationRole1 = buildSignedDelegatedTargets(targets = newTargets)

      pushSignedDelegatedMetadataOk(signedDelegationRole1)

      Get(apiUriV2(s"user_repo/search")).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.OK

        val value = responseAs[PaginationResult[Package]].values.loneElement
        value.name.value shouldBe "customname"
      }

  }

  testWithRepo("filters by origin") { implicit ns => implicit repoId =>
    addTargetToRepo(repoId)

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    Put(
      apiUri("user_repo/targets/mypkg?name=library&version=0.0.1&hardwareIds=myid001"),
      testEntity
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUriV2(s"user_repo/search?origin=targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values should have size 1
    }

    Get(
      apiUriV2(s"user_repo/search?origin=${delegatedRoleName.value}")
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values should have size 1
    }

    Get(
      apiUriV2(s"user_repo/search?origin=${delegatedRoleName.value},targets.json")
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values should have size 2
    }

    Get(apiUriV2(s"user_repo/search?origin=doesnotexist")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values shouldBe empty
    }
  }

  testWithRepo("filters by name") { implicit ns => implicit repoId =>
    addTargetToRepo(repoId)

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    Put(
      apiUri("user_repo/targets/mypkg?name=library&version=0.0.1&hardwareIds=myid001"),
      testEntity
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUriV2(s"user_repo/search?name=library")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values should have size 1
    }

    Get(apiUriV2(s"user_repo/search?name=mytargetName")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values should have size 1
    }
    Get(apiUriV2(s"user_repo/search?name=doesnotexist")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values shouldBe empty
    }
  }

  testWithRepo("sorts by filename ASC") { implicit ns => implicit repoId =>
    addTargetToRepo(repoId)

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    Put(
      apiUri("user_repo/targets/zotherpackage?name=library&version=0.0.1&hardwareIds=myid001"),
      testEntity
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUriV2(s"user_repo/search?sortBy=filename")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val result = responseAs[PaginationResult[Package]]

      result.total shouldBe 2
      result.values.length shouldBe 2

      result.values.map(_.filename.value) shouldBe Seq("mypath/mytargetName", "zotherpackage")
    }
  }

  testWithRepo("sorts by created at DESC") { implicit ns => implicit repoId =>
    addTargetToRepo(repoId)

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    Put(
      apiUri("user_repo/targets/zotherpackage?name=library&version=0.0.1&hardwareIds=myid001"),
      testEntity
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUriV2(s"user_repo/search?sortBy=createdAt")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val result = responseAs[PaginationResult[Package]]

      result.total shouldBe 2
      result.values.length shouldBe 2

      result.values.map(_.filename.value) shouldBe Seq("zotherpackage", "mypath/mytargetName")
    }
  }


  testWithRepo("filters by nameContains") { implicit ns => implicit repoId =>
    addTargetToRepo(repoId)

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    Put(
      apiUri("user_repo/targets/mypkg?name=library&version=0.0.1&hardwareIds=myid001"),
      testEntity
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUriV2(s"user_repo/search?nameContains=lib")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values should have size 1
    }

    Get(apiUriV2(s"user_repo/search?nameContains=mytarget")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values should have size 1
    }

    Get(apiUriV2(s"user_repo/search?nameContains=doesnotexist")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values shouldBe empty
    }
  }

  // TODO: Currently only filters by single hardwareid
  testWithRepo("filters by hardwareIds") { implicit ns => implicit repoId =>
    addTargetToRepo(repoId)

    uploadOfflineSignedTargetsRole()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadataOk(signedDelegationRole)

    Put(
      apiUri("user_repo/targets/mypkg?name=library&version=0.0.1&hardwareIds=myid001"),
      testEntity
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUriV2(s"user_repo/search?hardwareIds=myid001")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values should have size 1
    }

    Get(
      apiUriV2(s"user_repo/search?hardwareIds=delegated-hardware-id-001")
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values should have size 1
    }

    Get(apiUriV2(s"user_repo/search?hardwareIds=somethingelse")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val values = responseAs[PaginationResult[Package]].values
      values shouldBe empty
    }
  }
}
