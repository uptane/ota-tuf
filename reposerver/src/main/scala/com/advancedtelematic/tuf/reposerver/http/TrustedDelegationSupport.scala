package com.advancedtelematic.tuf.reposerver.http

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._
import com.advancedtelematic.libats.http.Errors.MissingEntityId
import com.advancedtelematic.libtuf.data.ClientDataType.{Delegation, Delegations, TargetsRole}
import com.advancedtelematic.libtuf.data.TufDataType.TufKey
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf_server.repo.server.SignedRoleGeneration
import com.advancedtelematic.tuf.reposerver.db.SignedRoleRepositorySupport
import akka.http.scaladsl.util.FastFuture

import scala.concurrent.Future

trait TrustedDelegationSupport extends SignedRoleRepositorySupport {

  def validateTrustedDelegations(repoId: RepoId, newDelegations : List[Delegation], existingTargets: TargetsRole): ValidatedNel[String, Delegations] = {
    existingTargets.delegations match {
      case Some(delegations) => {
        validateTrustedDelegations(repoId, delegations.copy(roles = newDelegations)) match {
          case Valid(d) => d.validNel
          case Invalid(errors) => errors.invalid[Delegations]
        }
      }
      case _ => "Invalid or non-existent reference keys used by trusted delegations".invalidNel[Delegations]
    }
  }

  def validateTrustedDelegations(repoId: RepoId, delegations: Option[Delegations]): ValidatedNel[String, Delegations] = {
    delegations match {
      case Some(s) => validateTrustedDelegations(repoId, s)
      // an empty delegations block is valid
      case _ => Delegations(Map(), List()).validNel[String]
    }
  }

  def validateTrustedDelegations(repoId: RepoId, delegations: Delegations): ValidatedNel[String, Delegations] = {
    val keyErrors = for {
      delegation <- delegations.roles
      keyid <- delegation.keyids
      if (delegations.keys.contains(keyid) === false)
    } yield "Invalid delegation key referenced by: " + delegation.name
    if (keyErrors.nonEmpty) {
      NonEmptyList.fromListUnsafe(keyErrors).invalid[Delegations]
    } else
      delegations.validNel[String]
  }

  def getTrustedDelegationsBlock(repoId: RepoId): Future[Option[Delegations]] =
    signedRoleRepository.find[TargetsRole](repoId).map { signedTargetRole =>
      signedTargetRole.role.delegations
    }.recover {
      case _: MissingEntityId[_] => None
    }

  def getTrustedDelegations(repoId: RepoId): Future[List[Delegation]] = for {
    existingDelegations <- getTrustedDelegationsBlock(repoId)
      outDelegations = existingDelegations match {
        case Some(delegations) => delegations.roles
        case _ => List[Delegation]()
      }
  } yield outDelegations

  def getTrustedDelegationKeys(repoId: RepoId): Future[List[TufKey]] = for {
    existingDelegations <- getTrustedDelegationsBlock(repoId)
    keys: List[TufKey] = existingDelegations match {
      case Some(delegations) => delegations.keys.values.toList
      case _ => List[TufKey]()
    }
  } yield keys

  def addTrustedDelegations(repoId: RepoId, delegations: List[Delegation])(signedRoleGeneration: SignedRoleGeneration): Future[Any] = for {
    existingTargetsRole <- signedRoleRepository.find[TargetsRole](repoId)
    delegationsBlock = validateTrustedDelegations(repoId, delegations, existingTargetsRole.role) match {
      case Valid(delegations) => delegations
      case Invalid(errors) => throw Errors.InvalidTrustedDelegations(errors)
    }
    json <- signedRoleGeneration.regenerateAllSignedRoles(repoId, Some(delegationsBlock))
  } yield json

  def addTrustedDelegationKeys(repoId: RepoId, inKeys: List[TufKey])(signedRoleGeneration: SignedRoleGeneration): Future[Any] = for {
    existingDelegations <- getTrustedDelegationsBlock(repoId)
    delegationsBlock = existingDelegations match {
      case Some(delegations) => delegations.copy(keys = inKeys.map(k => (k.id, k)).toMap)
      case _ => Delegations(inKeys.map(k => (k.id, k)).toMap, List())
    }
    json <- signedRoleGeneration.regenerateAllSignedRoles(repoId, Some(delegationsBlock))
  } yield json
}
