package com.advancedtelematic.tuf.reposerver.http

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers.CsvSeq
import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, ValidHardwareIdentifier}
import com.advancedtelematic.tuf.reposerver.db.RepoNamespaceRepositorySupport
import com.advancedtelematic.tuf.reposerver.http.PaginationParamsOps.PaginationParams
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport.*
import com.advancedtelematic.tuf.reposerver.data.RepoCodecs.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineV
import slick.jdbc.MySQLProfile.api.*

import scala.concurrent.ExecutionContext

import enumeratum.*

sealed abstract class TargetItemsSort(val column: String) extends EnumEntry

object TargetItemsSort extends Enum[TargetItemsSort] {

  val values = findValues

  case object Filename extends TargetItemsSort("filename ASC")
  case object CreatedAt extends TargetItemsSort("created_at DESC")

  implicit val unmarshaller: Unmarshaller[String, TargetItemsSort] = Unmarshaller.strict { str =>
    withNameInsensitive(str)
  }

}

sealed abstract class AggregatedTargetItemsSort(val column: String) extends EnumEntry

object AggregatedTargetItemsSort extends Enum[AggregatedTargetItemsSort] {

  val values = findValues

  case object Name extends AggregatedTargetItemsSort("name ASC")
  case object LastVersionAt extends AggregatedTargetItemsSort("last_version_at DESC")

  implicit val unmarshaller: Unmarshaller[String, AggregatedTargetItemsSort] = Unmarshaller.strict {
    str =>
      withNameInsensitive(str)
  }

}

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
  import AggregatedTargetItemsSort.*

  private implicit val hardwareIdentifierUnmarshaller
    : Unmarshaller[String, Refined[String, ValidHardwareIdentifier]] = Unmarshaller.strict { str =>
    refineV[ValidHardwareIdentifier](str).valueOr(err =>
      throw new IllegalArgumentException(s"invalid hardware id: $err")
    )
  }

  val SortByTargetItemsParam: Directive1[TargetItemsSort] = parameter(
    "sortBy".as[TargetItemsSort].?[TargetItemsSort](TargetItemsSort.CreatedAt)
  )

  val SortByAggregatedTargetItemsParam: Directive1[AggregatedTargetItemsSort] = parameters(
    "sortBy"
      .as[AggregatedTargetItemsSort]
      .?[AggregatedTargetItemsSort](AggregatedTargetItemsSort.LastVersionAt)
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
          (get & PaginationParams & SearchParams & SortByTargetItemsParam) { case (offset, limit, searchParams, sortBy) =>

            val f = for {
              count <- (new PackageSearch()).count(repoId, searchParams)
              values <- (new PackageSearch()).find(repoId, offset, limit, searchParams, sortBy)
            } yield
              PaginationResult(values, count, offset, limit)
              
            complete(f)
          }
        },
        path("grouped-search") {
          (get & PaginationParams & SearchParams & SortByAggregatedTargetItemsParam) { case (offset, limit, searchParams, sortBy) =>
            val f = for {
              count <- (new PackageSearch()).findAggregatedCount(repoId, searchParams)
              values <- (new PackageSearch()).findAggregated(repoId, offset, limit, searchParams, sortBy)
            } yield
              PaginationResult(values, count, offset, limit)

            complete(f)
          }
        }
      )

    }

  // format: on
}
