package com.advancedtelematic.tuf.reposerver.delegations

import akka.http.scaladsl.model.Uri
import cats.implicits._
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{DelegatedRoleName, Delegation, DelegationFriendlyName, MetaItem, MetaPath, TargetsRole, ValidMetaPath}
import com.advancedtelematic.libtuf.data.TufDataType.{JsonSignedPayload, RepoId, SignedPayload}
import com.advancedtelematic.libtuf_server.crypto.Sha256Digest
import com.advancedtelematic.libtuf_server.repo.server.DataType.SignedRole
import com.advancedtelematic.libtuf_server.repo.server.SignedRoleGeneration
import com.advancedtelematic.tuf.reposerver.db.{DelegationRepositorySupport, SignedRoleRepositorySupport}
import com.advancedtelematic.tuf.reposerver.http._
import slick.jdbc.MySQLProfile.api._

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.DelegationInfo

import java.time.Instant

class SignedRoleDelegationsFind()(implicit val db: Database, val ec: ExecutionContext) extends DelegationRepositorySupport {

  import com.advancedtelematic.libtuf.crypt.CanonicalJson._
  import com.advancedtelematic.libtuf.data.TufCodecs._
  import io.circe.syntax._

  def findSignedTargetRoleDelegations(repoId: RepoId, targetRole: SignedRole[TargetsRole]): Future[Map[MetaPath, MetaItem]] = {
    val delegatedRoleNames = targetRole.role.delegations.map(_.roles.map(_.name)).getOrElse(List.empty)
    val delegationsF =
      delegatedRoleNames
        .map { name => delegationsRepo.find(repoId, name).map((name, _)) }
        .sequence
        .recover { case Errors.DelegationNotFound => List.empty }

    for {
      delegations <- delegationsF
      delegationsAsMetaItems <- delegations.map { case (name, d) =>
        Future.fromTry { (name.value + ".json").refineTry[ValidMetaPath].product(asMetaItem(d.content)) }
      }.sequence
    } yield delegationsAsMetaItems.toMap
  }

  private def asMetaItem(content: JsonSignedPayload): Try[MetaItem] = {
    val canonicalJson = content.asJson.canonical
    val checksum = Sha256Digest.digest(canonicalJson.getBytes)
    val hashes = Map(checksum.method -> checksum.hash)
    val versionT = content.signed.hcursor.downField("version").as[Int].toTry

    versionT.map { version => MetaItem(hashes, canonicalJson.length, version) }
  }
}

class DelegationsManagement()(implicit val db: Database, val ec: ExecutionContext)
                                                  extends DelegationRepositorySupport with SignedRoleRepositorySupport {
  def create(repoId: RepoId,
             roleName: DelegatedRoleName,
             delegationMetadata: SignedPayload[TargetsRole],
             remoteUri: Option[Uri] = None,
             lastFetch: Option[Instant] = None,
             remoteHeaders: Map[String, String] = Map.empty,
             friendlyName: Option[DelegationFriendlyName] = None)
            (implicit signedRoleGeneration: SignedRoleGeneration): Future[Unit] = async {
    val targetsRole = await(signedRoleRepository.find[TargetsRole](repoId)).role
    val delegation = findDelegationMetadataByName(targetsRole, roleName)

    validateDelegationMetadataSignatures(targetsRole, delegation, delegationMetadata) match {
      case Valid(_) =>
        await(delegationsRepo.persist(repoId, roleName, delegationMetadata.asJsonSignedPayload, remoteUri, lastFetch, remoteHeaders, friendlyName))
        await(signedRoleGeneration.regenerateSnapshots(repoId))
      case Invalid(err) =>
        throw Errors.PayloadSignatureInvalid(err)
    }
  }

  def createFromRemote(repoId: RepoId,
                       uri: Uri,
                       delegationName: DelegatedRoleName,
                       remoteHeaders: Map[String, String],
                       friendlyName: Option[DelegationFriendlyName]=None)(implicit signedRoleGeneration: SignedRoleGeneration, client: RemoteDelegationClient): Future[Unit] = async {
    val signedRole = await(client.fetch[SignedPayload[TargetsRole]](uri, remoteHeaders))
    await(create(repoId, delegationName, signedRole, Option(uri), Option(Instant.now()), remoteHeaders, friendlyName))
  }

  def updateFromRemote(repoId: RepoId, delegatedRoleName: DelegatedRoleName)(implicit signedRoleGeneration: SignedRoleGeneration, client: RemoteDelegationClient): Future[Unit] = async {
    val delegation = await(delegationsRepo.find(repoId, delegatedRoleName))

    if(delegation.remoteUri.isEmpty)
      throw Errors.MissingRemoteDelegationUri(repoId, delegatedRoleName)

    val signedRole = await(client.fetch[SignedPayload[TargetsRole]](delegation.remoteUri.get, delegation.remoteHeaders))

    await(create(repoId, delegatedRoleName, signedRole, delegation.remoteUri, Option(Instant.now()), delegation.remoteHeaders))
  }

  def find(repoId: RepoId, roleName: DelegatedRoleName): Future[(JsonSignedPayload, DelegationInfo)] = async {
    val delegation = await(delegationsRepo.find(repoId, roleName))
    (delegation.content, DelegationInfo(delegation.lastFetched, delegation.remoteUri, delegation.friendlyName))
  }
  // Only allow setting friendlyName for now, add more here
  def setDelegationInfo(repoId: RepoId, roleName: DelegatedRoleName, delegationInfo: DelegationInfo): Future[Unit] = {
    delegationInfo match {
      case DelegationInfo(Some(_), _, _) =>
        throw Errors.RequestedImmutableFields(Seq("friendlyName"), Seq("lastFetched", "remoteUri"))
      case DelegationInfo(_,Some(_), _) =>
        throw Errors.RequestedImmutableFields(Seq("friendlyName"), Seq("lastFetched", "remoteUri"))
      case DelegationInfo(_, _, Some(friendlyName)) =>
        delegationsRepo.setDelegationFriendlyName(repoId, roleName, friendlyName)
      case DelegationInfo(_, _, None) =>
        throw Errors.InvalidDelegationName(NonEmptyList.one("missing friendlyName field"))
    }
  }

  private def findDelegationMetadataByName(targetsRole: TargetsRole, delegatedRoleName: DelegatedRoleName): Delegation = {
    targetsRole.delegations.flatMap(_.roles.find(_.name == delegatedRoleName)).getOrElse(throw Errors.DelegationNotDefined)
  }
  private def validateDelegationMetadataSignatures(targetsRole: TargetsRole,
                                                   delegation: Delegation,
                                                   delegationMetadata: SignedPayload[TargetsRole]): ValidatedNel[String, SignedPayload[TargetsRole]] = {
    val publicKeys = targetsRole.delegations.map(_.keys).getOrElse(Map.empty).filterKeys(delegation.keyids.contains)
    TufCrypto.payloadSignatureIsValid(publicKeys, delegation.threshold, delegationMetadata)
  }
}
