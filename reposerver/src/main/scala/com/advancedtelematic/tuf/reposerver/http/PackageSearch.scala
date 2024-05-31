package com.advancedtelematic.tuf.reposerver.http

import cats.syntax.show.*
import com.advancedtelematic.libats.codecs.CirceRefined
import com.advancedtelematic.libats.data.DataType.Checksum
import com.advancedtelematic.libats.slick.db.{SlickCirceMapper, SlickUriMapper}
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.Package
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.Package.{
  TargetOrigin,
  ValidTargetOrigin
}
import slick.jdbc.{GetResult, SetParameter}
import slick.jdbc.MySQLProfile.api.*
import slick.sql.SqlStreamingAction

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class PackageSearch()(implicit db: Database) {

  import CirceRefined.*
  import cats.syntax.either.*
  import com.advancedtelematic.libats.codecs.CirceAts.*
  import com.advancedtelematic.libtuf.data.TufDataType.*
  import eu.timepit.refined.refineV

  private sealed trait TargetsQuery[T] {
    def getResult: GetResult[T]
    def select: String
  }

  private case object CountQuery extends TargetsQuery[Long] {
    def select = "count(*)"

    def getResult: GetResult[Long] = GetResult.GetLong
  }

  private case object ResultQuery extends TargetsQuery[Package] {
    def select: String = "*"

    def getResult: GetResult[Package] = GetResult[Package] { pr =>
      val checksum =
        SlickCirceMapper.circeMapper[Checksum].getValue(pr.rs, pr.rs.findColumn("checksum"))
      val uri = Option(SlickUriMapper.uriMapper.getValue(pr.rs, pr.rs.findColumn("uri")))

      val hardwareIdentifiers =
        SlickCirceMapper
          .circeMapper[List[HardwareIdentifier]]
          .getValue(pr.rs, pr.rs.findColumn("hardwareIds"))

      val targetOrigin =
        refineV[ValidTargetOrigin](Option(pr.rs.getString("origin")).getOrElse(""))
          .valueOr(msg =>
            throw new IllegalArgumentException(
              s"Could not read db row, not formatted properly: ${msg.toList.mkString(",")}"
            )
          )

      Package(
        TargetName(Option(pr.rs.getString("name")).getOrElse("")),
        TargetVersion(Option(pr.rs.getString("version")).getOrElse("")),
        refineV[ValidTargetFilename](pr.rs.getString("filename")).valueOr(msg =>
          throw new IllegalArgumentException(s"Could not read db row, not formatted properly: $msg")
        ),
        targetOrigin,
        pr.rs.getLong("length"),
        Map(checksum.method -> checksum.hash),
        uri,
        hardwareIdentifiers,
        pr.rs.getTimestamp("created_at").toInstant,
        io.circe.parser.parse(pr.rs.getString("custom")).toOption
      )
    }

  }

  private implicit val setHardwareString = SetParameter[Seq[HardwareIdentifier]] { (value, pp) =>
    import io.circe.syntax.*
    pp.setString(value.asJson.noSpaces)
  }

  private implicit val setSeqString = SetParameter[Seq[String]] { (value, pp) =>
    pp.setString(value.mkString(","))
  }

  private def buildQuery[Q](
    targetsQuery: TargetsQuery[Q],
    repoId: RepoId,
    offset: Long,
    limit: Long,
    searchParams: PackageSearchParameters): SqlStreamingAction[Vector[Q], Q, Effect] = {

    implicit val getResult: GetResult[Q] = targetsQuery.getResult

    val querySqlAction =
      sql"""
      SELECT #${targetsQuery.select}
      FROM aggregated_items
      WHERE repo_id = ${repoId.show} AND
        IF(${searchParams.origin.isEmpty}, true, FIND_IN_SET(origin, ${searchParams.origin}) > 0) AND
        IF(${searchParams.name.isEmpty}, true, name = ${searchParams.name}) AND
        IF(${searchParams.nameContains.isEmpty}, true, LOCATE(${searchParams.nameContains}, name) > 0) AND
        IF(${searchParams.hardwareIds.isEmpty}, true, JSON_CONTAINS(hardwareids, JSON_QUOTE(${searchParams.hardwareIds.headOption
          .map(_.value)})))
      ORDER BY
          #${searchParams.sortBy.column},
          version,
          length
      LIMIT $limit OFFSET $offset""".as[Q]

    // TODO: This needs a newer mariadb version
    // hardwareId filter should be:
    //         IF(${searchParams.hardwareIds.isEmpty}, true, JSON_LENGTH(JSON_ARRAY_INTERSECT(hardwareids, ${searchParams.hardwareIds})) > 0)
    // but JSON_ARRAY_INTERSECT is not supported in our mariadb version

    querySqlAction
  }

  def count(repoId: RepoId, searchParams: PackageSearchParameters)(
    implicit ec: ExecutionContext): Future[Long] = {
    val q = buildQuery(CountQuery, repoId, 0, 1, searchParams)
    db.run(q.map(_.sum))
  }

  def find(repoId: RepoId,
           offset: Long,
           limit: Long,
           searchParams: PackageSearchParameters): Future[Seq[Package]] = {
    val q = buildQuery(ResultQuery, repoId, offset, limit, searchParams)
    db.run(q)
  }

  case class AggregatedPackage(name: TargetName,
                               versions: Seq[TargetVersion],
                               hardwareIds: Seq[HardwareIdentifier],
                               lastCreatedAt: Instant)

  def findAggregated(repoId: RepoId,
                     offset: Long,
                     limit: Long,
                     searchParams: PackageSearchParameters): Future[Seq[AggregatedPackage]] = {

    // first filter values using group by, distinct, limit+offset

    // get all found values grouped by

    // group into a datastructure that makes sense in memory, possibly *NOT* Map[TargetVersion, Seq[Pacakge]]

    // with GROUP_CONCAT we should be able to do this all in one query?

    implicit val getAggregatedPkgs: GetResult[AggregatedPackage] = GetResult { pr =>
      val versions = pr.rs.getString("versions").split(",").map(TargetVersion.apply)

      val hardwareIds = pr.rs.getString("hardwareIds").split(",").toList.flatMap { hwidJsonStr =>
        io.circe.parser.decode[List[HardwareIdentifier]](hwidJsonStr).toList.flatten
      }

      val lastCreatedAt = pr.rs.getTimestamp("last_created_at").toInstant

      AggregatedPackage(
        TargetName(Option(pr.rs.getString("name")).getOrElse("")),
        versions,
        hardwareIds,
        lastCreatedAt
      )
    }

    val q =
      sql"""
      SELECT name,
             GROUP_CONCAT(version ORDER BY version SEPARATOR ',') versions,
             GROUP_CONCAT(hardwareids ORDER BY hardwareids SEPARATOR ',' ) hardwareIds,
             MAX(created_at) last_version,
             GROUP BY name
      FROM aggregated_items
      WHERE repo_id = ${repoId.show} AND
        IF(${searchParams.origin.isEmpty}, true, FIND_IN_SET(origin, ${searchParams.origin}) > 0) AND
        IF(${searchParams.nameContains.isEmpty}, true, LOCATE(${searchParams.nameContains}, name) > 0) AND
        IF(${searchParams.hardwareIds.isEmpty}, true, JSON_CONTAINS(hardwareids, JSON_QUOTE(${searchParams.hardwareIds.headOption
          .map(_.value)})))
      ORDER BY
          #${searchParams.sortBy.column},
          1 ASC, 2 ASC, 3 DESC
      LIMIT $limit OFFSET $offset""".as[AggregatedPackage]

    db.run(q)
  }

}
