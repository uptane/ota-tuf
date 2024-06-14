package com.advancedtelematic.tuf.reposerver.http

import io.scalaland.chimney.dsl._
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers.CsvSeq
import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, ValidHardwareIdentifier}
import com.advancedtelematic.tuf.reposerver.db.RepoNamespaceRepositorySupport
import com.advancedtelematic.tuf.reposerver.http.PaginationParamsOps.PaginationParams
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport.*
import com.advancedtelematic.tuf.reposerver.data.RepoCodecs.*
import com.advancedtelematic.libtuf.data.ClientCodecs.*
import com.advancedtelematic.libtuf.data.ClientDataType.{
  AggregatedTargetItemsSort,
  ClientAggregatedPackage,
  ClientPackage,
  TargetItemsSort
}
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.Package.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineV
import slick.jdbc.MySQLProfile.api.*

import scala.concurrent.ExecutionContext

case class PackageSearchParameters(origin: Seq[String],
                                   nameContains: Option[String],
                                   name: Option[String],
                                   version: Option[String],
                                   hardwareIds: Seq[HardwareIdentifier])

class RepoTargetsResource(namespaceValidation: NamespaceValidation)(
  implicit val db: Database,
  val ec: ExecutionContext)
    extends RepoNamespaceRepositorySupport {

  import akka.http.scaladsl.server.Directives.*
  import cats.syntax.either.*
  import TargetItemsSort.*
  import com.advancedtelematic.libtuf.data.ClientDataType.SortDirection

  private implicit val hardwareIdentifierUnmarshaller
    : Unmarshaller[String, Refined[String, ValidHardwareIdentifier]] = Unmarshaller.strict { str =>
    refineV[ValidHardwareIdentifier](str).valueOr(err =>
      throw new IllegalArgumentException(s"invalid hardware id: $err")
    )
  }

  val SortByTargetItemsParam: Directive[(TargetItemsSort, ClientDataType.SortDirection)] =
    parameters(
      "sortBy".as[TargetItemsSort].?[TargetItemsSort](TargetItemsSort.CreatedAt),
      "sortDirection".as[SortDirection].?[SortDirection](SortDirection.Desc)
    )

  val SortByAggregatedTargetItemsParam: Directive[(AggregatedTargetItemsSort, SortDirection)] =
    parameters(
      "sortBy"
        .as[AggregatedTargetItemsSort]
        .?[AggregatedTargetItemsSort](AggregatedTargetItemsSort.LastVersionAt),
      "sortDirection".as[SortDirection].?[SortDirection](SortDirection.Desc)
    )

  val SearchParams: Directive1[PackageSearchParameters] = parameters(
    "origin".as(CsvSeq[String]).?,
    "nameContains".as[String].?,
    "name".as[String].?,
    "version".as[String].?,
    "hardwareIds".as(CsvSeq[HardwareIdentifier]).?
  ).tflatMap { case (origin, nameContains, name, version, hardwareIds) =>
    provide(
      PackageSearchParameters(
        origin.getOrElse(Seq.empty),
        nameContains,
        name,
        version,
        hardwareIds.getOrElse(Seq.empty)
      )
    )
  }

  // format: off
  val route =
    (pathPrefix("user_repo")  & NamespaceRepoId(namespaceValidation, repoNamespaceRepo.findFor)) { repoId =>
      concat(
        path("search") {
          (get & PaginationParams & SearchParams & SortByTargetItemsParam) { case (offset, limit, searchParams, sortBy, sortDirection) =>

            val f = for {
              count <- (new PackageSearch()).count(repoId, searchParams)
              values <- (new PackageSearch()).find(repoId, offset, limit, searchParams, sortBy, sortDirection)
            } yield {
              PaginationResult(values.map(_.transformInto[ClientPackage]), count, offset, limit)
            }

            complete(f)
          }
        },
        path("grouped-search") {
          (get & PaginationParams & SearchParams & SortByAggregatedTargetItemsParam) { case (offset, limit, searchParams, sortBy, sortDirection) =>
            val f = for {
              count <- (new PackageSearch()).findAggregatedCount(repoId, searchParams)
              values <- (new PackageSearch()).findAggregated(repoId, offset, limit, searchParams, sortBy, sortDirection)
            } yield
              PaginationResult(values.map(_.transformInto[ClientAggregatedPackage]), count, offset, limit)

            complete(f)
          }
        }
      )

    }

  // format: on
}
