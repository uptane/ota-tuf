package com.advancedtelematic.libtuf_server.repo.client

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.Uri.Path.Slash
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.*
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.advancedtelematic.libats.codecs.CirceCodecs.*
import com.advancedtelematic.libats.data.DataType.{Checksum, Namespace, ValidChecksum}
import com.advancedtelematic.libats.data.{ErrorCode, PaginationResult}
import com.advancedtelematic.libats.http.Errors.{RawError, RemoteServiceError}
import com.advancedtelematic.libats.http.ServiceHttpClientSupport
import com.advancedtelematic.libats.http.HttpCodecs.*
import com.advancedtelematic.libats.http.tracing.Tracing.ServerRequestTracing
import com.advancedtelematic.libats.http.tracing.TracingHttpClient
import com.advancedtelematic.libtuf.data.ClientCodecs.*
import com.advancedtelematic.libtuf.data.ClientDataType.{
  AggregatedTargetItemsSort,
  ClientAggregatedPackage,
  ClientPackage,
  ClientTargetItem,
  DelegatedRoleName,
  Delegation,
  DelegationClientTargetItem,
  DelegationFriendlyName,
  DelegationInfo,
  RootRole,
  SortDirection,
  TargetHash,
  TargetItemsSort,
  TargetsRole
}
import com.advancedtelematic.libtuf.data.TufCodecs.*
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.TargetFormat
import com.advancedtelematic.libtuf.data.TufDataType.{
  HardwareIdentifier,
  JsonSignedPayload,
  KeyType,
  RepoId,
  SignedPayload,
  TargetFilename,
  TargetName,
  TargetVersion
}
import com.advancedtelematic.libtuf_server.data.Requests.{
  CommentRequest,
  CreateRepositoryRequest,
  ExpireNotBeforeRequest,
  FilenameComment,
  TargetComment
}
import com.advancedtelematic.libtuf_server.repo.client.ReposerverClient.{
  KeysNotReady,
  NotFound,
  RootNotInKeyserver
}
import eu.timepit.refined.api.Refined

//import com.advancedtelematic.tuf.reposerver.data.RepoDataType.Package
import io.circe.generic.semiauto.*
import io.circe.{Codec, Decoder, Encoder, Json}
import com.advancedtelematic.libats.codecs.CirceCodecs.*
import com.advancedtelematic.libtuf.data.ClientCodecs.*
import org.slf4j.LoggerFactory

import java.net.URI
import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}
import com.advancedtelematic.libats.http.HttpCodecs.*

object ReposerverClient {

  object RequestTargetItem {
    implicit val requestTargetItemCode: Codec[RequestTargetItem] = deriveCodec
  }

  case class RequestTargetItem(uri: Uri,
                               checksum: Checksum,
                               targetFormat: Option[TargetFormat],
                               name: Option[TargetName],
                               version: Option[TargetVersion],
                               hardwareIds: Seq[HardwareIdentifier],
                               length: Long)

  object EditTargetItem {
    implicit val encoder: Encoder[EditTargetItem] = deriveEncoder
    implicit val decoder: Decoder[EditTargetItem] = deriveDecoder
  }

  case class EditTargetItem(uri: Option[URI] = None,
                            hardwareIds: Seq[HardwareIdentifier] = Seq.empty[HardwareIdentifier],
                            proprietaryCustom: Option[Json] = None)

  val KeysNotReady = RawError(ErrorCode("keys_not_ready"), StatusCodes.Locked, "keys not ready")

  val RootNotInKeyserver = RawError(
    ErrorCode("root_role_not_in_keyserver"),
    StatusCodes.FailedDependency,
    "the root role was not found in upstream keyserver"
  )

  val NotFound = RawError(
    ErrorCode("repo_resource_not_found"),
    StatusCodes.NotFound,
    "the requested repo resource was not found"
  )

  val RepoConflict =
    RawError(ErrorCode("repo_conflict"), StatusCodes.Conflict, "repo already exists")

  val PrivateKeysNotInKeyserver = RawError(
    ErrorCode("private_keys_not_found"),
    StatusCodes.PreconditionFailed,
    "could not find required private keys. The repository might be using offline signing"
  )

}

trait ReposerverClient {

  protected def ReposerverError(msg: String) =
    RawError(ErrorCode("reposerver_remote_error"), StatusCodes.BadGateway, msg)

  def createRoot(namespace: Namespace, keyType: KeyType): Future[RepoId]

  def fetchRoot(namespace: Namespace,
                version: Option[Int]): Future[(RepoId, SignedPayload[RootRole])]

  def rotateRoot(namespace: Namespace): Future[Unit]

  def repoExists(namespace: Namespace)(implicit ec: ExecutionContext): Future[Boolean] =
    fetchRoot(namespace, None).transform {
      case Success(_) | Failure(KeysNotReady)              => Success(true)
      case Failure(NotFound) | Failure(RootNotInKeyserver) => Success(false)
      case Failure(t)                                      => Failure(t)
    }

  def addTarget(namespace: Namespace,
                fileName: String,
                uri: Uri,
                checksum: Checksum,
                length: Int,
                targetFormat: TargetFormat,
                name: Option[TargetName] = None,
                version: Option[TargetVersion] = None,
                hardwareIds: Seq[HardwareIdentifier] = Seq.empty): Future[Unit]

  def addTargetFromContent(namespace: Namespace,
                           fileName: String,
                           length: Int,
                           targetFormat: TargetFormat,
                           content: Source[ByteString, Any],
                           name: TargetName,
                           version: TargetVersion,
                           hardwareIds: Seq[HardwareIdentifier] = Seq.empty): Future[Unit]

  def targetExists(namespace: Namespace, targetFilename: TargetFilename): Future[Boolean]

  def fetchSnapshotMetadata(namespace: Namespace): Future[JsonSignedPayload]

  def fetchTimestampMetadata(namespace: Namespace): Future[JsonSignedPayload]

  def fetchTargets(namespace: Namespace): Future[SignedPayload[TargetsRole]]

  def searchTargetsV2(namespace: Namespace,
                      offset: Option[Long],
                      limit: Option[Long],
                      origins: Seq[String],
                      nameContains: Option[String],
                      name: Option[String],
                      version: Option[String],
                      hardwareIds: Seq[HardwareIdentifier],
                      hashes: Seq[Refined[String, ValidChecksum]],
                      sortBy: Option[TargetItemsSort],
                      sortDirection: Option[SortDirection]): Future[PaginationResult[ClientPackage]]

  def searchTargetsGroupedV2(
    namespace: Namespace,
    offset: Option[Long],
    limit: Option[Long],
    origins: Seq[String],
    nameContains: Option[String],
    name: Option[String],
    version: Option[String],
    hardwareIds: Seq[HardwareIdentifier],
    hashes: Seq[TargetHash],
    sortBy: Option[AggregatedTargetItemsSort],
    sortDirection: Option[SortDirection]): Future[PaginationResult[ClientAggregatedPackage]]

  def setTargetComments(namespace: Namespace,
                        targetFilename: TargetFilename,
                        comment: String): Future[Unit]

  def setTargetsMetadataExpiration(namespace: Namespace, expiry: Instant): Future[Unit]

  def fetchSingleTargetComments(namespace: Namespace,
                                targetFilename: TargetFilename): Future[FilenameComment]

  def fetchTargetsComments(namespace: Namespace,
                           targetNameContains: Option[String],
                           offset: Option[Long],
                           limit: Option[Long]): Future[PaginationResult[FilenameComment]]

  def fetchTargetsCommentsByFilename(namespace: Namespace,
                                     filenames: Seq[TargetFilename]): Future[Seq[FilenameComment]]

  def deleteTarget(namespace: Namespace, targetFilename: TargetFilename): Future[Unit]

  def editTarget(namespace: Namespace,
                 targetFilename: TargetFilename,
                 uri: Option[URI] = None,
                 hardwareIds: Seq[HardwareIdentifier] = Seq.empty,
                 proprietaryMeta: Option[Json] = None): Future[ClientTargetItem]

  def fetchSingleTargetItem(namespace: Namespace,
                            targetFilename: TargetFilename): Future[ClientTargetItem]

  def fetchTargetItems(namespace: Namespace,
                       nameContains: Option[String] = None,
                       offset: Option[Long] = None,
                       limit: Option[Long] = None): Future[PaginationResult[ClientTargetItem]]

  def fetchDelegationMetadata(namespace: Namespace, roleName: String): Future[JsonSignedPayload]

  def fetchDelegationTargetItems(
    namespace: Namespace,
    nameContains: Option[String] = None): Future[PaginationResult[DelegationClientTargetItem]]

  def fetchSingleDelegationTargetItem(
    namespace: Namespace,
    targetFilename: TargetFilename): Future[Seq[DelegationClientTargetItem]]

  def fetchTrustedDelegations(namespace: Namespace): Future[Delegation]
  def fetchDelegationsInfo(namespace: Namespace): Future[Map[DelegatedRoleName, DelegationInfo]]
  def refreshDelegatedRole(namespace: Namespace, fileName: DelegatedRoleName): Future[Unit]
}

object ReposerverHttpClient extends ServiceHttpClientSupport {

  def apply(reposerverUri: Uri, authHeaders: Option[HttpHeader] = None)(
    implicit ec: ExecutionContext,
    system: ActorSystem,
    mat: Materializer,
    tracing: ServerRequestTracing): ReposerverHttpClient =
    new ReposerverHttpClient(reposerverUri, defaultHttpClient, authHeaders)

}

class ReposerverHttpClient(reposerverUri: Uri,
                           httpClient: HttpRequest => Future[HttpResponse],
                           authHeaders: Option[HttpHeader] = None)(
  implicit ec: ExecutionContext,
  system: ActorSystem,
  tracing: ServerRequestTracing)
    extends TracingHttpClient(httpClient, "reposerver")
    with ReposerverClient {

  import ReposerverClient.*
  import com.advancedtelematic.libats.http.ServiceHttpClient
  import ServiceHttpClient.*
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport.*
  import io.circe.syntax.*

  val log = LoggerFactory.getLogger(this.getClass)

  private def apiUri(path: Path) =
    reposerverUri.withPath(reposerverUri.path / "api" / "v1" ++ Slash(path))

  private def apiV2Uri(path: Path) =
    reposerverUri.withPath(reposerverUri.path / "api" / "v2" ++ Slash(path))

  private def paginationParams(offset: Option[Long], limit: Option[Long]): Map[String, String] =
    Map("offset" -> offset, "limit" -> limit).collect { case (key, Some(value)) =>
      key -> value.toString
    }

  override def createRoot(namespace: Namespace, keyType: KeyType): Future[RepoId] =
    Marshal(CreateRepositoryRequest(keyType)).to[RequestEntity].flatMap { entity =>
      val req = HttpRequest(HttpMethods.POST, uri = apiUri(Path("user_repo")), entity = entity)

      execHttpUnmarshalledWithNamespace[RepoId](namespace, req).handleErrors {
        case error if error.status == StatusCodes.Conflict =>
          Future.failed(RepoConflict)

        case error if error.status == StatusCodes.Locked =>
          Future.failed(KeysNotReady)
      }
    }

  override def fetchRoot(namespace: Namespace,
                         version: Option[Int]): Future[(RepoId, SignedPayload[RootRole])] = {
    val req =
      if (version.nonEmpty)
        HttpRequest(HttpMethods.GET, uri = apiUri(Path(s"user_repo/${version.get}.root.json")))
      else HttpRequest(HttpMethods.GET, uri = apiUri(Path(s"user_repo/root.json")))

    execHttpFullWithNamespace[SignedPayload[RootRole]](namespace, req).flatMap {
      case Left(error) if error.status == StatusCodes.NotFound =>
        FastFuture.failed(NotFound)
      case Left(error) if error.status == StatusCodes.Locked =>
        FastFuture.failed(KeysNotReady)
      case Left(error) if error.status == StatusCodes.FailedDependency =>
        FastFuture.failed(RootNotInKeyserver)
      case Left(error) =>
        FastFuture.failed(error)
      case Right(r) =>
        r.httpResponse.headers.find(_.is("x-ats-tuf-repo-id")) match {
          case Some(repoIdHeader) =>
            FastFuture.successful(RepoId(UUID.fromString(repoIdHeader.value)) -> r.unmarshalled)
          case None =>
            FastFuture.failed(NotFound)
        }
    }
  }

  override def rotateRoot(namespace: Namespace): Future[Unit] = {
    val req = HttpRequest(HttpMethods.PUT, uri = apiUri(Path(s"user_repo/root/rotate")))
    execHttpUnmarshalledWithNamespace[Unit](namespace, req).ok
  }

  private def addTargetErrorHandler[T]: PartialFunction[RemoteServiceError, Future[T]] = {
    case error if error.status == StatusCodes.PreconditionFailed =>
      Future.failed(PrivateKeysNotInKeyserver)
    case error if error.status == StatusCodes.NotFound =>
      Future.failed(NotFound)
    case error if error.status == StatusCodes.Locked =>
      Future.failed(KeysNotReady)
    case error if error.status == StatusCodes.MethodNotAllowed =>
      // if the parameters are wonky (like the package-id is too long), Akka will match this
      // to another request where it fails with bad method. Catch it and respond with BadRequest
      log.warn(
        "Caught http 405 error from reposerver attempting to add target. Converting it to 400"
      )
      Future.failed(
        RawError(ErrorCode("bad_request"), StatusCodes.BadRequest, "invalid parameters")
      )
  }

  override def addTarget(namespace: Namespace,
                         fileName: String,
                         uri: Uri,
                         checksum: Checksum,
                         length: Int,
                         targetFormat: TargetFormat,
                         name: Option[TargetName] = None,
                         version: Option[TargetVersion] = None,
                         hardwareIds: Seq[HardwareIdentifier] = Seq.empty): Future[Unit] = {
    val payload = payloadFrom(uri, checksum, length, name, version, hardwareIds, targetFormat)

    val entity = HttpEntity(ContentTypes.`application/json`, payload.noSpaces)

    val req = HttpRequest(
      HttpMethods.POST,
      uri = apiUri(Path("user_repo") / "targets" / fileName),
      entity = entity
    )

    execHttpUnmarshalledWithNamespace[Unit](namespace, req).handleErrors(addTargetErrorHandler)
  }

  override def targetExists(namespace: Namespace,
                            targetFilename: TargetFilename): Future[Boolean] = {
    val req = HttpRequest(
      HttpMethods.HEAD,
      uri = apiUri(Path("user_repo") / "targets" / targetFilename.value)
    )

    execHttpUnmarshalledWithNamespace[Unit](namespace, req).flatMap {
      case Left(err) if err.status == StatusCodes.NotFound => FastFuture.successful(false)
      case Left(err)                                       => FastFuture.failed(err)
      case Right(_)                                        => FastFuture.successful(true)
    }
  }

  override def fetchSnapshotMetadata(namespace: Namespace): Future[JsonSignedPayload] = {
    val req = HttpRequest(HttpMethods.GET, uri = apiUri(Path(s"user_repo/snapshot.json")))
    execHttpUnmarshalledWithNamespace[JsonSignedPayload](namespace, req).ok
  }

  override def fetchTimestampMetadata(namespace: Namespace): Future[JsonSignedPayload] = {
    val req = HttpRequest(HttpMethods.GET, uri = apiUri(Path(s"user_repo/timestamp.json")))
    execHttpUnmarshalledWithNamespace[JsonSignedPayload](namespace, req).ok
  }

  override def fetchTargets(namespace: Namespace): Future[SignedPayload[TargetsRole]] = {
    val req = HttpRequest(HttpMethods.GET, uri = apiUri(Path("user_repo/targets.json")))

    execHttpUnmarshalledWithNamespace[SignedPayload[TargetsRole]](namespace, req).handleErrors {
      case error if error.status == StatusCodes.NotFound =>
        FastFuture.failed(NotFound)
    }
  }

  def searchTargetsV2(
    namespace: Namespace,
    offset: Option[Long],
    limit: Option[Long],
    origins: Seq[String],
    nameContains: Option[String],
    name: Option[String],
    version: Option[String],
    hardwareIds: Seq[HardwareIdentifier],
    hashes: Seq[TargetHash],
    sortBy: Option[TargetItemsSort],
    sortDirection: Option[SortDirection]): Future[PaginationResult[ClientPackage]] = {
    val req = HttpRequest(
      HttpMethods.GET,
      uri = apiV2Uri(Path(s"user_repo/search"))
        .withQuery(
          Query(
            paginationParams(offset, limit)
              ++ (if (origins.isEmpty) { Map.empty[String, String] }
                  else { Map("origin" -> origins.mkString(",")) })
              ++ nameContains.map(n => Map("nameContains" -> n)).getOrElse(Map.empty)
              ++ name.map(n => Map("name" -> n)).getOrElse(Map.empty)
              ++ version.map(n => Map("version" -> n)).getOrElse(Map.empty)
              ++ (if (hardwareIds.isEmpty) { Map.empty[String, String] }
                  else {
                    Map("hardwareIds" -> hardwareIds.map(_.value).mkString(","))
                  })
              ++ (if (hashes.isEmpty) { Map.empty[String, String] }
                  else {
                    Map("hashes" -> hashes.map(_.value).mkString(","))
                  })
              ++ sortBy.map(s => Map("sortBy" -> s.entryName)).getOrElse(Map.empty)
              ++ sortDirection
                .map(sortD => Map("sortDirection" -> sortD.entryName))
                .getOrElse(Map.empty)
          )
        )
    )
    execHttpUnmarshalledWithNamespace[PaginationResult[ClientPackage]](namespace, req).ok
  }

  def searchTargetsGroupedV2(
    namespace: Namespace,
    offset: Option[Long],
    limit: Option[Long],
    origins: Seq[String],
    nameContains: Option[String],
    name: Option[String],
    version: Option[String],
    hardwareIds: Seq[HardwareIdentifier],
    hashes: Seq[TargetHash],
    sortBy: Option[AggregatedTargetItemsSort],
    sortDirection: Option[SortDirection]): Future[PaginationResult[ClientAggregatedPackage]] = {
    val req = HttpRequest(
      HttpMethods.GET,
      uri = apiV2Uri(Path(s"user_repo/grouped-search"))
        .withQuery(
          Query(
            paginationParams(offset, limit)
              ++ (if (origins.isEmpty) { Map.empty[String, String] }
                  else { Map("origin" -> origins.mkString(",")) })
              ++ nameContains.map(n => Map("nameContains" -> n)).getOrElse(Map.empty)
              ++ name.map(n => Map("name" -> n)).getOrElse(Map.empty)
              ++ version.map(n => Map("version" -> n)).getOrElse(Map.empty)
              ++ (if (hardwareIds.isEmpty) { Map.empty[String, String] }
                  else {
                    Map("hardwareIds" -> hardwareIds.map(_.value).mkString(","))
                  })
              ++ (if (hashes.isEmpty) { Map.empty[String, String] }
                  else {
                    Map("hashes" -> hashes.map(_.value).mkString(","))
                  })
              ++ sortBy.map(s => Map("sortBy" -> s.entryName)).getOrElse(Map.empty)
              ++ sortDirection
                .map(sortD => Map("sortDirection" -> sortD.entryName))
                .getOrElse(Map.empty)
          )
        )
    )
    execHttpUnmarshalledWithNamespace[PaginationResult[ClientAggregatedPackage]](namespace, req).ok

  }

  override def fetchSingleTargetItem(namespace: Namespace,
                                     targetFilename: TargetFilename): Future[ClientTargetItem] = {
    val req = HttpRequest(
      HttpMethods.GET,
      uri = apiUri(Path(s"user_repo/target_items/${targetFilename.value}"))
    )
    execHttpUnmarshalledWithNamespace[ClientTargetItem](namespace, req).ok
  }

  override def fetchTargetItems(
    namespace: Namespace,
    nameContains: Option[String] = None,
    offset: Option[Long] = None,
    limit: Option[Long] = None): Future[PaginationResult[ClientTargetItem]] = {
    val nameContainsMap = nameContains.map(n => Map("nameContains" -> n)).getOrElse(Map.empty)

    val reqUri = apiUri(Path(s"user_repo/target_items"))
      .withQuery(Query(paginationParams(offset, limit) ++ nameContainsMap))
    execHttpUnmarshalledWithNamespace[PaginationResult[ClientTargetItem]](
      namespace,
      HttpRequest(HttpMethods.GET, uri = reqUri)
    ).ok
  }

  override def fetchDelegationMetadata(namespace: Namespace,
                                       roleName: String): Future[JsonSignedPayload] = {
    val req = HttpRequest(HttpMethods.GET, uri = apiUri(Path(s"user_repo/delegations/${roleName}")))
    execHttpUnmarshalledWithNamespace[JsonSignedPayload](namespace, req).ok
  }

  override def fetchSingleDelegationTargetItem(
    namespace: Namespace,
    targetFilename: TargetFilename): Future[Seq[DelegationClientTargetItem]] = {
    val req = HttpRequest(
      HttpMethods.GET,
      uri = apiUri(Path(s"user_repo/delegations_items/${targetFilename.value}"))
    )
    execHttpUnmarshalledWithNamespace[Seq[DelegationClientTargetItem]](namespace, req).ok
  }

  override def fetchDelegationTargetItems(
    namespace: Namespace,
    nameContains: Option[String] = None): Future[PaginationResult[DelegationClientTargetItem]] = {
    val reqUri =
      if (nameContains.isDefined)
        apiUri(Path(s"user_repo/delegations_items"))
          .withQuery(Query("nameContains" -> nameContains.get))
      else
        apiUri(Path(s"user_repo/delegations_items"))
    val req = HttpRequest(HttpMethods.GET, uri = reqUri)
    execHttpUnmarshalledWithNamespace[PaginationResult[DelegationClientTargetItem]](
      namespace,
      req
    ).ok
  }

  override def fetchTrustedDelegations(namespace: Namespace): Future[Delegation] = {
    val req = HttpRequest(HttpMethods.GET, uri = apiUri(Path("user_repo/trusted-delegations")))
    execHttpUnmarshalledWithNamespace[Delegation](namespace, req).ok
  }

  override def fetchDelegationsInfo(
    namespace: Namespace): Future[Map[DelegatedRoleName, DelegationInfo]] = {
    val req = HttpRequest(HttpMethods.GET, uri = apiUri(Path("user_repo/trusted-delegations/info")))
    execHttpUnmarshalledWithNamespace[Map[DelegatedRoleName, DelegationInfo]](namespace, req).ok
  }

  override def setTargetComments(namespace: Namespace,
                                 targetFilename: TargetFilename,
                                 comment: String): Future[Unit] = {
    val commentBody = HttpEntity(
      ContentTypes.`application/json`,
      CommentRequest(TargetComment(comment)).asJson.noSpaces
    )
    val req = HttpRequest(
      HttpMethods.PUT,
      uri = apiUri(Path(s"user_repo/comments/${targetFilename.value}")),
      entity = commentBody
    )
    execHttpUnmarshalledWithNamespace[Unit](namespace, req).ok
  }

  override def setTargetsMetadataExpiration(namespace: Namespace, expiry: Instant): Future[Unit] = {
    val body =
      HttpEntity(ContentTypes.`application/json`, ExpireNotBeforeRequest(expiry).asJson.noSpaces)
    val req = HttpRequest(
      HttpMethods.PUT,
      uri = apiUri(Path(s"user_repo/targets/expire/not-before")),
      entity = body
    )
    execHttpUnmarshalledWithNamespace[Unit](namespace, req).ok
  }

  override def fetchTargetsComments(
    namespace: Namespace,
    targetNameContains: Option[String],
    offset: Option[Long],
    limit: Option[Long]): Future[PaginationResult[FilenameComment]] = {

    val nameContainsMap = targetNameContains.map(c => Map("nameContains" -> c)).getOrElse(Map.empty)

    val commentUri = apiUri(Path("user_repo/comments")).withQuery(
      Query(paginationParams(offset, limit) ++ nameContainsMap)
    )
    val req = HttpRequest(HttpMethods.GET, uri = commentUri)

    execHttpUnmarshalledWithNamespace[PaginationResult[FilenameComment]](namespace, req)
      .handleErrors {
        case error if error.status == StatusCodes.NotFound =>
          FastFuture.failed(NotFound)
      }
  }

  override def fetchTargetsCommentsByFilename(
    namespace: Namespace,
    filenames: Seq[TargetFilename]): Future[Seq[FilenameComment]] = {
    val filenameBody = HttpEntity(ContentTypes.`application/json`, filenames.asJson.noSpaces)
    val req = HttpRequest(
      HttpMethods.POST,
      uri = apiUri(Path("user_repo/list-target-comments")),
      entity = filenameBody
    )

    execHttpUnmarshalledWithNamespace[Seq[FilenameComment]](namespace, req).handleErrors {
      case error if error.status == StatusCodes.NotFound =>
        FastFuture.failed(NotFound)
    }
  }

  override def fetchSingleTargetComments(
    namespace: Namespace,
    targetFilename: TargetFilename): Future[FilenameComment] = {
    val req = HttpRequest(
      HttpMethods.GET,
      uri = apiUri(Path(s"user_repo/comments/${targetFilename.value}"))
    )
    execHttpUnmarshalledWithNamespace[FilenameComment](namespace, req).ok
  }

  def deleteTarget(namespace: Namespace, targetFilename: TargetFilename): Future[Unit] = {
    val req =
      HttpRequest(HttpMethods.DELETE, uri = apiUri(Path(s"user_repo/targets/${targetFilename}")))
    execHttpUnmarshalledWithNamespace[Unit](namespace, req).handleErrors {
      case error if error.status == StatusCodes.NotFound =>
        FastFuture.failed(NotFound)
    }
  }

  // Does the reposerver support this?
  override def editTarget(namespace: Namespace,
                          targetFilename: TargetFilename,
                          uri: Option[URI] = None,
                          hardwareIds: Seq[HardwareIdentifier] = Seq.empty,
                          proprietaryMeta: Option[Json] = None): Future[ClientTargetItem] = {
    val editTargetItem = EditTargetItem(uri, hardwareIds, proprietaryMeta)
    val req = HttpRequest(
      HttpMethods.PATCH,
      uri = apiUri(Path(s"user_repo/targets/${targetFilename}"))
    ).withEntity(ContentTypes.`application/json`, editTargetItem.asJson.noSpaces)
    execHttpUnmarshalledWithNamespace[ClientTargetItem](namespace, req).handleErrors {
      case error if error.status == StatusCodes.NotFound =>
        FastFuture.failed(NotFound)
    }
  }

  override protected def execHttpFullWithNamespace[T](namespace: Namespace, request: HttpRequest)(
    implicit ct: ClassTag[T],
    ev: FromEntityUnmarshaller[T]): Future[ServiceHttpFullResponseEither[T]] = {
    val authReq = authHeaders match {
      case Some(a) => request.addHeader(a)
      case None    => request
    }

    super.execHttpFullWithNamespace(namespace, authReq)
  }

  private def payloadFrom(uri: Uri,
                          checksum: Checksum,
                          length: Int,
                          name: Option[TargetName],
                          version: Option[TargetVersion],
                          hardwareIds: Seq[HardwareIdentifier],
                          targetFormat: TargetFormat): Json =
    RequestTargetItem(uri, checksum, Some(targetFormat), name, version, hardwareIds, length).asJson

  override def addTargetFromContent(namespace: Namespace,
                                    fileName: String,
                                    length: Int,
                                    targetFormat: TargetFormat,
                                    content: Source[ByteString, Any],
                                    name: TargetName,
                                    version: TargetVersion,
                                    hardwareIds: Seq[HardwareIdentifier]): Future[Unit] = {
    val params =
      Map("name" -> name.value, "version" -> version.value, "targetFormat" -> targetFormat.toString)

    val hwparams =
      if (hardwareIds.isEmpty)
        Map.empty
      else
        Map("hardwareIds" -> hardwareIds.map(_.value).mkString(","))

    val uri = apiUri(Path("user_repo") / "targets" / fileName).withQuery(Query(params ++ hwparams))

    val multipartForm =
      Multipart.FormData(
        Multipart.FormData.BodyPart(
          "file",
          HttpEntity(ContentTypes.`application/octet-stream`, length, content),
          Map("filename" -> fileName)
        )
      )

    Marshal(multipartForm).to[RequestEntity].flatMap { form =>
      val req = HttpRequest(HttpMethods.PUT, uri, entity = form)
      execHttpUnmarshalledWithNamespace[Unit](namespace, req).handleErrors(addTargetErrorHandler)
    }
  }

  override def refreshDelegatedRole(namespace: Namespace,
                                    fileName: DelegatedRoleName): Future[Unit] = {
    val uri = apiUri(
      Path("user_repo") / "trusted-delegations" / fileName.value / "remote" / "refresh"
    )
    val req = HttpRequest(HttpMethods.PUT, uri)
    execHttpUnmarshalledWithNamespace[Unit](namespace, req).ok
  }

}
