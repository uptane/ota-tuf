package com.advancedtelematic.tuf.reposerver.delegations

import akka.http.scaladsl.util.FastFuture
import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.implicits._
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{DelegatedRoleName, Delegation, Delegations, MetaItem, MetaPath, TargetsRole, ValidMetaPath}
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

//  Interface to set/get delegation entities. These should return non database types
class DelegationsManagement()(implicit val db: Database, val ec: ExecutionContext)
                                                  extends DelegationRepositorySupport with SignedRoleRepositorySupport {
  import com.advancedtelematic.libtuf.data.TufDataType.TufKey
  def create(repoId: RepoId, roleName: DelegatedRoleName, delegationMetadata: SignedPayload[TargetsRole])
            (implicit signedRoleGeneration: SignedRoleGeneration): Future[Unit] = async {
    val targetsRole = await(signedRoleRepository.find[TargetsRole](repoId)).role
    val delegation = findDelegationMetadataByName(targetsRole, roleName)

    validateDelegationMetadataSignatures(targetsRole, delegation, delegationMetadata) match {
      case Valid(_) =>
        await(delegationsRepo.persist(repoId, roleName, delegationMetadata.asJsonSignedPayload))
        await(signedRoleGeneration.regenerateSnapshots(repoId))
      case Invalid(err) =>
        throw Errors.PayloadSignatureInvalid(err)
    }
  }

  def validateTrustedDelegations(repoId: RepoId, newDelegations : List[Delegation], existingTargets: TargetsRole): ValidatedNel[String, List[Delegation]] = {
    existingTargets.delegations match {
      case Some(delegations) => {
        // Need to change the return type to match the list of `delegation` vs `Delegations`
        validateDelegations(repoId, delegations.copy(roles = newDelegations)) match {
          case Valid(d) => d.roles.validNel
          case Invalid(errors) => errors.invalid[List[Delegation]]
        }
      }
      case _ => "Invalid or non-existent reference keys used by trusted delegations".invalidNel[List[Delegation]]
    }
  }
  def validateDelegations(repoId: RepoId, delegations: Option[Delegations]): ValidatedNel[String, Delegations] = {
    delegations match {
      case Some(s) => validateDelegations(repoId, s)
      // an empty delegations block is valid
      case _ => Delegations(Map(), List()).validNel[String]
    }
  }

  def validateDelegations(repoId: RepoId, delegations: Delegations): ValidatedNel[String, Delegations] = {
    val keyErrors = for {
      delegation <- delegations.roles
      keyid <- delegation.keyids
      if (delegations.keys.contains(keyid) === false)
    } yield "Invalid delegation key referenced by: " + delegation.name
    if (keyErrors.nonEmpty) {
      //NonEmptyList.of(badKeys.head, badKeys.tail.asInstanceOf[List[String]]).invalid[Delegations]
      keyErrors.toNel match {
        case Some(k) => k.invalid[Delegations]
      }
    } else
      delegations.validNel[String]
  }

  def find(repoId: RepoId, roleName: DelegatedRoleName): Future[JsonSignedPayload] =
    delegationsRepo.find(repoId, roleName).map(_.content)

  def addTrustedDelegations(repoId: RepoId, delegations:List[Delegation]): Future[Seq[Any]] = {
    for {
      signedTargets <- signedRoleRepository.find[TargetsRole](repoId)
      validDelegations <- validateTrustedDelegations(repoId, delegations, signedTargets.role) match {
        case Valid(delegations) =>
          delegationsRepo.persistTrustedDelegations(repoId, delegations)
        case i @ Invalid(errors) => throw Errors.InvalidTrustedDelegations(errors)
      }
    } yield validDelegations
  }

  def getTrustedDelegations(repoId: RepoId): Future[Seq[Delegation]] = {
      delegationsRepo.findAllTrustedDelegations(repoId).map(f => f.map(d => Delegation(d.name,
      d.keyids, d.paths, d.threshold, d.terminating)))
  }
  def addTrustedKeys(repoId: RepoId, keys: List[TufKey]): Future[Seq[Unit]] = {
    delegationsRepo.persistTrustedDelegationKeys(repoId, keys)
  }
  def getTrustedKeys(repoId: RepoId): Future[Seq[TufKey]] = {
    delegationsRepo.findAllTrustedDelegationKeys(repoId).map(f => f.map(k => k.keyValue))
  }

  def getDelegationsBlock(repoId: RepoId): Future[Delegations] = {
    for {
      keys <- getTrustedKeys(repoId)
      trustedDelegations <- getTrustedDelegations(repoId)
    }yield Delegations(keys.map(k=>(k.id, k)).toMap, trustedDelegations.toList)
  }

  def replaceDelegationsBlock(repoId: RepoId, delegations: Delegations): Future[Unit] = {
    for {
      _ <- delegationsRepo.deleteAllTrustedDelegationKeys(repoId)
      _ <- delegationsRepo.deleteAllTrustedDelegations(repoId)
      trustedKeys <- if (delegations.keys.nonEmpty) {
          addTrustedKeys(repoId, delegations.keys.values.toList)
        } else {
          Future.successful (Unit)
        }
      trustedDelegations <- if(delegations.roles.nonEmpty) {
          addTrustedDelegations(repoId, delegations.roles)
        }
        else {
          Future.successful (Unit)
        }
    } yield (trustedKeys, trustedDelegations)
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
