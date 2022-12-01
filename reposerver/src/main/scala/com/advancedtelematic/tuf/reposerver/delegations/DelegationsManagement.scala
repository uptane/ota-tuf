package com.advancedtelematic.tuf.reposerver.delegations

import akka.http.scaladsl.model.Uri
import cats.implicits._
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientTargetItem, DelegatedRoleName, Delegation, DelegationClientTargetItem, DelegationFriendlyName, MetaItem, MetaPath, TargetCustom, TargetsRole, ValidMetaPath}
import com.advancedtelematic.libtuf.data.TufDataType.{JsonSignedPayload, RepoId, SignedPayload, TargetFilename}
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
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.{DelegationInfo, TargetItem}

import java.nio.file.{FileSystems, Paths}
import java.time.Instant
import java.time.temporal.ChronoUnit

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
        validateDelegationTargetPaths(targetsRole, roleName, delegationMetadata) match {
          case Valid(_) =>
            await(delegationsRepo.persist(repoId, roleName, delegationMetadata.asJsonSignedPayload, remoteUri, lastFetch, remoteHeaders, friendlyName))
            await(signedRoleGeneration.regenerateSnapshots(repoId))
          case Invalid(err) =>
            throw Errors.InvalidDelegatedTarget(err)
        }

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


  private def GetAllDelegationTargets(repoId: RepoId):  Future[Seq[(DelegatedRoleName, Map[TargetFilename, ClientTargetItem])]] = {
    val expiresAt = Instant.now().plus(1, ChronoUnit.DAYS)
    delegationsRepo.findAll(repoId).flatMap { delegations =>
      Future.sequence {
        delegations.map { delegation =>
          SignedRole.withChecksum[TargetsRole](delegation.content, 1, expiresAt)
            .map(signedRole => (delegation.roleName, signedRole.role.targets))
        }
      }
    }
  }

  def findTargetByFilename(repoId: RepoId, targetFilename: TargetFilename): Future[DelegationClientTargetItem] = {
    val delegationItems = GetAllDelegationTargets(repoId).map{delegations =>
      delegations.flatMap{ case(delegatedRoleName, targetsMap) =>
        targetsMap.filter(_._1 == targetFilename).map{ case (filename, targetItem) =>
          DelegationClientTargetItem(filename, delegatedRoleName, targetItem)
        }
      }
    }
    // So having the same named target across delegations is actually ok...
    // Should we just get rid of this function?
    delegationItems.map(_.head)
  }
  // delegations dont have a database table for targetItems so we must use the json directly
  def findTargets(repoId: RepoId, nameContains: Option[String] = None): Future[List[DelegationClientTargetItem]] = {
    val allDelegationTargets = GetAllDelegationTargets(repoId)
    allDelegationTargets.map {
      _.flatMap { case (delegatedRoleName, targetsMap) =>
        val filteredMap = if(nameContains.isDefined)
          targetsMap.filter(_._1.value.contains(nameContains.getOrElse("")))
        else targetsMap
        filteredMap.map(kv => DelegationClientTargetItem(kv._1, delegatedRoleName, kv._2))
      }.toList
    }
  }

  //withChecksum[T : TufRole : Decoder](content: JsonSignedPayload, version: Int, expireAt: Instant)
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

  private def validateDelegationTargetPaths(targetsRole: TargetsRole,
                                            delegatedRoleName: DelegatedRoleName,
                                            delegationMetadata: SignedPayload[TargetsRole]): ValidatedNel[String, SignedPayload[TargetsRole]] = {
    val delegationRef = findDelegationMetadataByName(targetsRole, delegatedRoleName)
    val matcher = FileSystems.getDefault().getPathMatcher(s"glob:${}")
    // Find invalid targets by running all targetNames (map keys) through the pathPattern regexes in trusted delegations
    val invalidTargets: List[TargetFilename] = delegationMetadata.signed.targets.filterKeys{ target =>
      val matchedPaths = delegationRef.paths.filter{pathPattern =>
      /*
         So Aktualizr uses glob patterns on a regular string (not a file-path) via this function without any flags: https://man7.org/linux/man-pages/man3/fnmatch.3.html
         Here we are using java's path matcher which is different because it assumes we are operating on a path and naturally implies directory boundaries!
         We escape curly brackets {} because aktualizr wont treat them as special characters and we replace * with ** to match 0 or more characters across directory
         boundaries which gets us closer to aktualizr-parity, However, java will treat all other special characters
         as if they were a path, not a string! So unexpected behavior could occur here when using ? and [] among directory boundaries.
         Something like this is really what we need (but it's old and abandoned): https://github.com/morgen-peschke/scala-glob
      */
        FileSystems.getDefault().getPathMatcher(s"glob:${pathPattern.value.replace("{", "\\{")
                                                                          .replace("}", "\\}")
                                                                          .replace("*","**")}").matches(Paths.get(target.value))
      }
      // if no delegation paths matched targetName, we return true, which designates this targetName as invalid
      matchedPaths.length < 1
    }.toList.map(_._1)

    Right(delegationMetadata).ensure(s"Target(s): ${invalidTargets} does not match any registered path patterns of this delegation: ${delegationRef.paths.map(_.value)}") {
      _ => invalidTargets.length < 1
    }.toValidatedNel
  }
}
