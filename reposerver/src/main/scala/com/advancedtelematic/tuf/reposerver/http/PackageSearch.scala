package com.advancedtelematic.tuf.reposerver.http

import cats.syntax.show.*
import com.advancedtelematic.libats.codecs.CirceRefined
import com.advancedtelematic.libats.data.DataType.Checksum
import com.advancedtelematic.libats.slick.db.{SlickCirceMapper, SlickUriMapper}
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.{AggregatedPackage, Package}
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.Package.ValidTargetOrigin
import slick.jdbc.{GetResult, SetParameter}
import slick.jdbc.MySQLProfile.api.*
import slick.sql.SqlStreamingAction

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

  private implicit val setHardwareString: SetParameter[Seq[HardwareIdentifier]] = SetParameter {
    (value, pp) =>
      import io.circe.syntax.*
      pp.setString(value.asJson.noSpaces)
  }

  private implicit val setSeqString: SetParameter[Seq[String]] = SetParameter { (value, pp) =>
    pp.setString(value.mkString(","))
  }

  private def findQuery[Q](targetsQuery: TargetsQuery[Q],
                           repoId: RepoId,
                           offset: Long,
                           limit: Long,
                           searchParams: PackageSearchParameters,
                           sortBy: TargetItemsSort): SqlStreamingAction[Vector[Q], Q, Effect] = {

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
          #${sortBy.column},
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
    val q = findQuery(CountQuery, repoId, 0, 1, searchParams, TargetItemsSort.CreatedAt)
    db.run(q.map(_.sum))
  }

  def find(repoId: RepoId,
           offset: Long,
           limit: Long,
           searchParams: PackageSearchParameters,
           sortBy: TargetItemsSort): Future[Seq[Package]] = {
    val q = findQuery(ResultQuery, repoId, offset, limit, searchParams, sortBy)
    db.run(q)
  }

  private sealed trait AggregatedTargetsQuery[T] {
    implicit def getResult: GetResult[T]
    def select: String
  }

  private case object AggregatedCountQuery extends AggregatedTargetsQuery[Long] {
    override implicit def getResult: GetResult[Long] = GetResult.GetLong

    override def select: String = "count(*), 0 versions, null last_version_at"
  }

  private case object AggregatedResultQuery extends AggregatedTargetsQuery[AggregatedPackage] {

    override implicit def getResult: GetResult[AggregatedPackage] = GetResult { pr =>
      val versions = Option(pr.rs.getString("versions"))
        .map(_.split(",").map(TargetVersion.apply))
        .toList
        .flatten

      val origins = Option(pr.rs.getString("origins"))
        .map(_.split(",").flatMap(str => refineV[ValidTargetOrigin](str).toOption))
        .toList
        .flatten

      val hardwareIds = Option(
        pr.rs
          .getString("hardwareIds")
      ).map(
        _.split(",")
          .flatMap { hwidJsonStr =>
            io.circe.parser.decode[List[HardwareIdentifier]](hwidJsonStr).toList.flatten.toSet
          }
      ).toList
        .flatten

      val lastCreatedAt = pr.rs.getTimestamp("last_version_at").toInstant

      AggregatedPackage(
        TargetName(Option(pr.rs.getString("name")).getOrElse("")),
        versions,
        hardwareIds,
        origins,
        lastCreatedAt
      )
    }

    override def select: String =
      """
        |name,
        |GROUP_CONCAT(version ORDER BY created_at DESC SEPARATOR ',') versions,
        |GROUP_CONCAT(hardwareids ORDER BY created_at DESC SEPARATOR ',' ) hardwareIds,
        |GROUP_CONCAT(origin ORDER BY origin DESC SEPARATOR ',' ) origins,
        |MAX(created_at) last_version_at""".stripMargin

  }

  def findAggregatedCount(repoId: RepoId, searchParams: PackageSearchParameters)(
    implicit ec: ExecutionContext): Future[Long] = {
    val q = findAggregatedQuery(
      AggregatedCountQuery,
      repoId,
      0,
      1,
      searchParams,
      AggregatedTargetItemsSort.LastVersionAt
    )
    q.map(_.sum)
  }

  def findAggregated(repoId: RepoId,
                     offset: Long,
                     limit: Long,
                     searchParams: PackageSearchParameters,
                     sortBy: AggregatedTargetItemsSort)(
    implicit ec: ExecutionContext): Future[Seq[AggregatedPackage]] =
    findAggregatedQuery(AggregatedResultQuery, repoId, offset, limit, searchParams, sortBy)

  private def findAggregatedQuery[Q](query: AggregatedTargetsQuery[Q],
                                     repoId: RepoId,
                                     offset: Long,
                                     limit: Long,
                                     searchParams: PackageSearchParameters,
                                     sortBy: AggregatedTargetItemsSort): Future[Seq[Q]] = {
    import query.getResult

    val q =
      sql"""
      SELECT #${query.select}
      FROM aggregated_items
      WHERE repo_id = ${repoId.show} AND
        name is not null AND
        version is not null AND
        IF(${searchParams.origin.isEmpty}, true, FIND_IN_SET(origin, ${searchParams.origin}) > 0) AND
        IF(${searchParams.nameContains.isEmpty}, true, LOCATE(${searchParams.nameContains}, name) > 0) AND
        IF(${searchParams.hardwareIds.isEmpty}, true, JSON_CONTAINS(hardwareids, JSON_QUOTE(${searchParams.hardwareIds.headOption
          .map(_.value)})))
      GROUP BY name
      ORDER BY
          #${sortBy.column},
          name ASC, versions ASC, last_version_at DESC, hardwareIds ASC
      LIMIT $limit OFFSET $offset""".as[Q]

    db.run(q)
  }

}
