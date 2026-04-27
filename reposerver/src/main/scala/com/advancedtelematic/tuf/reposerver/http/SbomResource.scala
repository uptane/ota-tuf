package com.advancedtelematic.tuf.reposerver.http

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.{CreateSbomRequest, Sbom}
import com.advancedtelematic.tuf.reposerver.data.RepoCodecs.*
import com.advancedtelematic.tuf.reposerver.db.SbomRepositorySupport
import com.advancedtelematic.tuf.reposerver.http.PaginationParamsOps.PaginationParams
import com.advancedtelematic.tuf.reposerver.http.ReposerverPekkoPaths.TargetFilenamePath
import com.advancedtelematic.libats.codecs.CirceRefined.*
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport.*
import slick.jdbc.MySQLProfile.api.*

import scala.concurrent.ExecutionContext

class SbomResource()(implicit val db: Database, val ec: ExecutionContext)
    extends SbomRepositorySupport {

  import org.apache.pekko.http.scaladsl.server.Directives.*

  val route: Route =
    pathPrefix("sboms") {
      concat(
        (pathEnd & get & PaginationParams) { (offset, limit) =>
          complete(sbomRepo.findAll(offset, limit))
        },
        (get & pathPrefix("raw") & path(TargetFilenamePath)) { filename =>
          val f = sbomRepo.find(filename).map(_.uri.value)
          onSuccess(f) { uri =>
            redirect(uri, StatusCodes.Found)
          }
        },
        path(TargetFilenamePath) { filename =>
          concat(
            get {
              complete(sbomRepo.find(filename))
            },
            put {
              entity(as[CreateSbomRequest]) { req =>
                complete(sbomRepo.persist(Sbom(filename, req.uri)))
              }
            },
            delete {
              complete(sbomRepo.delete(filename).map(_ => StatusCodes.NoContent))
            }
          )
        }
      )
    }

}
