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

case class PackageSearchParameters(origin: Seq[String],
                                   nameContains: Option[String],
                                   name: Option[String],
                                   hardwareIds: Seq[HardwareIdentifier],
                                   sortBy: TargetItemsSort)

class RepoTargetsResource(namespaceValidation: NamespaceValidation)(
  implicit val db: Database,
  val ec: ExecutionContext)
    extends RepoNamespaceRepositorySupport {

  import akka.http.scaladsl.server.Directives.*
  import cats.syntax.either.*
  import TargetItemsSort.*

  private implicit val hardwareIdentifierUnmarshaller
    : Unmarshaller[String, Refined[String, ValidHardwareIdentifier]] = Unmarshaller.strict { str =>
    refineV[ValidHardwareIdentifier](str).valueOr(err =>
      throw new IllegalArgumentException(s"invalid hardware id: $err")
    )
  }

  val SearchParams: Directive1[PackageSearchParameters] = parameters(
    "origin".as(CsvSeq[String]).?,
    "nameContains".as[String].?,
    "name".as[String].?,
    "hardwareIds".as(CsvSeq[HardwareIdentifier]).?,
    "sortBy".as[TargetItemsSort].?
  ).tflatMap { case (origin, nameContains, name, hardwareIds, sortBy) =>
    provide(
      PackageSearchParameters(
        origin.getOrElse(Seq.empty),
        nameContains,
        name,
        hardwareIds.getOrElse(Seq.empty),
        sortBy.getOrElse(TargetItemsSort.CreatedAt)
      )
    )
  }

  // format: off
  val route =
    (pathPrefix("user_repo")  & NamespaceRepoId(namespaceValidation, repoNamespaceRepo.findFor)) { repoId =>
      concat(
        path("search") {
          (get & PaginationParams & SearchParams) { case (offset, limit, searchParams) =>

            val f = for {
              count <- (new PackageSearch()).count(repoId, searchParams)
              values <- (new PackageSearch()).find(repoId, offset, limit, searchParams)
            } yield
              PaginationResult(values, count, offset, limit)
              
            complete(f)
          }
        },

      )

    }

  // format: on
}
