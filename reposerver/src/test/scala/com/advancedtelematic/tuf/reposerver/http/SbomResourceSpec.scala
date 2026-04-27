package com.advancedtelematic.tuf.reposerver.http

import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.data.RefinedUtils.RefineTry
import com.advancedtelematic.tuf.reposerver.data.RepoCodecs.*
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.{CreateSbomRequest, Sbom}
import com.advancedtelematic.tuf.reposerver.util.{ResourceSpec, TufReposerverSpec}
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport.*
import com.advancedtelematic.libats.codecs.CirceRefined.*
import eu.timepit.refined.api.Refined
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.concurrent.PatienceConfiguration
import PaginationResult.*

class SbomResourceSpec
    extends TufReposerverSpec
    with ResourceSpec
    with PatienceConfiguration {

  private def createSbomRequest(uri: String): CreateSbomRequest =
    CreateSbomRequest(Refined.unsafeApply(uri))

  test("PUT creates an SBOM") {
    val req = createSbomRequest("https://example.com/sbom1.spdx")

    Put(apiUri("sboms/myfile.spdx"), req) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val sbom = responseAs[Sbom]
      sbom.filename.value shouldBe "myfile.spdx"
      sbom.uri.value shouldBe "https://example.com/sbom1.spdx"
    }
  }

  test("GET retrieves an SBOM") {
    val req = createSbomRequest("https://example.com/sbom-get.spdx")
    Put(apiUri("sboms/get-test.spdx"), req) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri("sboms/get-test.spdx")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val sbom = responseAs[Sbom]
      sbom.filename.value shouldBe "get-test.spdx"
      sbom.uri.value shouldBe "https://example.com/sbom-get.spdx"
    }
  }

  test("GET raw returns 302 redirect") {
    val req = createSbomRequest("https://example.com/raw-redirect.spdx")
    Put(apiUri("sboms/raw-test.spdx"), req) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri("sboms/raw/raw-test.spdx")) ~> routes ~> check {
      status shouldBe StatusCodes.Found
      header("Location").map(_.value()) shouldBe Some("https://example.com/raw-redirect.spdx")
    }
  }

  test("GET for non-existent SBOM returns 404") {
    Get(apiUri("sboms/nonexistent.spdx")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  test("DELETE removes an SBOM") {
    val req = createSbomRequest("https://example.com/sbom-delete.spdx")
    Put(apiUri("sboms/delete-test.spdx"), req) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Delete(apiUri("sboms/delete-test.spdx")) ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri("sboms/delete-test.spdx")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  test("DELETE for non-existent SBOM returns 404") {
    Delete(apiUri("sboms/nonexistent.spdx")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  test("PUT upserts an existing SBOM") {
    val req1 = createSbomRequest("https://example.com/original.spdx")
    Put(apiUri("sboms/upsert-test.spdx"), req1) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val req2 = createSbomRequest("https://example.com/updated.spdx")
    Put(apiUri("sboms/upsert-test.spdx"), req2) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri("sboms/upsert-test.spdx")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val sbom = responseAs[Sbom]
      sbom.uri.value shouldBe "https://example.com/updated.spdx"
    }
  }

  test("GET list returns paginated results") {
    val prefix = "list-test-" + System.nanoTime() + "-"
    for (i <- 1 to 3) {
      val req = createSbomRequest(s"https://example.com/$prefix$i.spdx")
      Put(apiUri(s"sboms/$prefix$i.spdx"), req) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
    }

    Get(apiUri("sboms")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val result = responseAs[PaginationResult[Sbom]]
      result.total should be >= 3L
      result.offset shouldBe 0L.toOffset
      result.limit shouldBe 50L.toLimit
    }
  }

  test("GET list respects pagination params") {
    val prefix = "page-test-" + System.nanoTime() + "-"
    for (i <- 1 to 3) {
      val req = createSbomRequest(s"https://example.com/$prefix$i.spdx")
      Put(apiUri(s"sboms/$prefix$i.spdx"), req) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
    }

    Get(apiUri("sboms?offset=1&limit=1")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val result = responseAs[PaginationResult[Sbom]]
      result.offset shouldBe 1L.toOffset
      result.limit shouldBe 1L.toLimit
      result.values.length shouldBe 1
    }
  }

  test("GET list returns empty when no matching SBOMs exist with high offset") {
    Get(apiUri("sboms?offset=999999")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val result = responseAs[PaginationResult[Sbom]]
      result.values shouldBe empty
    }
  }

}
