package com.advancedtelematic.tuf.reposerver.db

import java.time.Instant
import java.time.temporal.ChronoUnit
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.test.MysqlDatabaseSpec
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import com.advancedtelematic.tuf.reposerver.util.TufReposerverSpec

import scala.concurrent.ExecutionContext

class RepoNamespaceRepositorySpec extends TufReposerverSpec with MysqlDatabaseSpec {

  implicit val ec: ExecutionContext = ExecutionContext.global

  def newRepo(): (RepoId, RepoNamespaceRepository) = {
    val repo = new RepoNamespaceRepository()
    val repoId = RepoId.generate()
    repo.persist(repoId, Namespace(repoId.uuid.toString)).futureValue
    (repoId, repo)
  }

  def millisNow(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

  test("keeps the existing value when new value is null, force = false") {
    val (repoId, repo) = newRepo()
    val existing = millisNow()

    repo.setExpiresNotBefore(repoId, Some(existing), force = true).futureValue
    repo.setExpiresNotBefore(repoId, None, force = false).futureValue

    repo.getExpiresNotBefore(repoId).futureValue shouldBe Some(existing)
  }

  test("GREATEST overwrites existing value with null when force = true") {
    val (repoId, repo) = newRepo()
    val existing = millisNow()

    repo.setExpiresNotBefore(repoId, Some(existing), force = true).futureValue
    repo.setExpiresNotBefore(repoId, None, force = true).futureValue

    repo.getExpiresNotBefore(repoId).futureValue shouldBe None
  }

  test("GREATEST keeps existing value when existing is null and new value is null") {
    val (repoId, repo) = newRepo()

    repo.getExpiresNotBefore(repoId).futureValue shouldBe None
    repo.setExpiresNotBefore(repoId, None, force = false).futureValue

    repo.getExpiresNotBefore(repoId).futureValue shouldBe None
  }

  test("GREATEST picks the greater of two non-null values when force = false") {
    val (repoId, repo) = newRepo()
    val earlier = millisNow()
    val later = earlier.plusSeconds(3600)

    repo.setExpiresNotBefore(repoId, Some(earlier), force = true).futureValue
    repo.setExpiresNotBefore(repoId, Some(later), force = false).futureValue
    repo.getExpiresNotBefore(repoId).futureValue shouldBe Some(later)

    repo.setExpiresNotBefore(repoId, Some(earlier), force = false).futureValue
    repo.getExpiresNotBefore(repoId).futureValue shouldBe Some(later)
  }

}
