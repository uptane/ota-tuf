package com.advancedtelematic.tuf.reposerver.http

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.Errors.MissingEntityId
import com.advancedtelematic.libtuf.crypt.CanonicalJson.*
import com.advancedtelematic.libtuf.data.ClientCodecs.*
import com.advancedtelematic.libtuf.data.ClientDataType.{
  ClientTargetItem,
  DelegatedRoleName,
  Delegation,
  Delegations,
  TargetsRole
}
import com.advancedtelematic.libtuf.data.TufDataType.{
  JsonSignedPayload,
  KeyId,
  RepoId,
  RoleType,
  TargetFilename,
  TufKey
}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import com.advancedtelematic.libtuf_server.repo.client.ReposerverClient.EditTargetItem
import com.advancedtelematic.libtuf_server.repo.server.SignedRoleGeneration
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.TargetItem
import com.advancedtelematic.tuf.reposerver.db.{
  FilenameCommentRepository,
  SignedRoleRepositorySupport,
  TargetItemRepositorySupport
}
import com.advancedtelematic.tuf.reposerver.target_store.TargetStore
import io.circe.Json
import io.circe.syntax.*
import org.apache.pekko.http.scaladsl.util.FastFuture
import org.bouncycastle.util.encoders.Base64
import slick.jdbc.MySQLProfile.api.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

sealed trait TargetKeyAvailability
case object TargetKeysOnline extends TargetKeyAvailability
case class TargetKeysOffline(keyIds: Seq[KeyId], threshold: Int) extends TargetKeyAvailability

class TargetRoleEdit(
  keyserverClient: KeyserverClient,
  signedRoleGeneration: SignedRoleGeneration,
  tufTargetsPublisher: TufTargetsPublisher)(implicit val db: Database, val ec: ExecutionContext)
    extends TargetItemRepositorySupport
    with SignedRoleRepositorySupport
    with FilenameCommentRepository.Support {

  private def checkTargetKeyAvailability(repoId: RepoId): Future[TargetKeyAvailability] =
    signedRoleGeneration
      .ensureTargetsCanBeSigned(repoId)
      .recover { case _: MissingEntityId[_] =>
        ()
      } // This happens when there is no targets role yet
      .map(_ => TargetKeysOnline)
      .recoverWith { case KeyserverClient.RoleKeyNotFound =>
        keyserverClient.fetchRootRole(repoId).map { root =>
          val targetsRoleInfo = root.signed.roles(RoleType.TARGETS)
          TargetKeysOffline(targetsRoleInfo.keyids, targetsRoleInfo.threshold)
        }
      }

  private def generateUnsignedMetadata(
    repoId: RepoId,
    metadataTransform: Seq[TargetItem] => Try[Seq[TargetItem]],
    delegations: () => Future[Option[Delegations]]): Future[TargetsRole] =
    for {
      allItems <- targetItemRepo.findFor(repoId)
      transformedItems <- Future.fromTry(metadataTransform(allItems))
      delegationsBlock <- delegations()
      baseTargetsRole <- signedRoleGeneration.genTargetsFromExistingItems(repoId, delegationsBlock)
      itemsMap = transformedItems.map { item =>
        val hashes = Map(item.checksum.method -> item.checksum.hash)
        item.filename -> ClientTargetItem(hashes, item.length, item.custom.map(_.asJson))
      }.toMap
      targetsRole = baseTargetsRole.copy(targets = itemsMap)
    } yield targetsRole

  private def runTargetsChanges(
    repoId: RepoId,
    metadataTransform: Seq[TargetItem] => Try[Seq[TargetItem]],
    delegations: () => Future[Option[Delegations]],
    repoStateChanges: () => Future[JsonSignedPayload]): Future[JsonSignedPayload] =
    checkTargetKeyAvailability(repoId).flatMap {
      case TargetKeysOnline =>
        repoStateChanges()

      case TargetKeysOffline(keyIds, threshold) =>
        generateUnsignedMetadata(repoId, metadataTransform, delegations).flatMap { metadata =>
          val metadataBase64 = Base64.toBase64String(metadata.asJson.canonical.getBytes)

          FastFuture.failed(
            Errors.TargetsKeysNotFoundWithMetadata(metadataBase64, keyIds, threshold)
          )
        }
    }

  def addTarget(namespace: Namespace, item: TargetItem): Future[JsonSignedPayload] =
    runTargetsChanges(
      repoId = item.repoId,
      metadataTransform = TargetItemTransformations.transformItemsForAdd(item),
      delegations = () => Future.successful(None),
      repoStateChanges = () =>
        for {
          _ <- targetItemRepo.persist(item)
          json <- signedRoleGeneration.regenerateAllSignedRoles(item.repoId)
          _ <- tufTargetsPublisher.targetAdded(namespace, item)
        } yield json
    )

  def deleteTarget(namespace: Namespace,
                   repoId: RepoId,
                   filename: TargetFilename,
                   targetStore: TargetStore): Future[JsonSignedPayload] =
    runTargetsChanges(
      repoId = repoId,
      metadataTransform = TargetItemTransformations.transformItemsForDelete(filename),
      delegations = () => Future.successful(None),
      repoStateChanges = () =>
        for {
          targetItem <- targetStore.targetItemRepo.findByFilename(repoId, filename)
          _ <- signedRoleGeneration.ensureTargetsCanBeSigned(repoId)
          _ <- targetItemRepo.deleteItemAndComments(filenameCommentRepo)(repoId, filename)
          json <- signedRoleGeneration.regenerateAllSignedRoles(repoId)
          _ <- targetStore.delete(targetItem)
          _ <- tufTargetsPublisher.targetsMetaModified(namespace)
        } yield json
    )

  def editTarget(repoId: RepoId,
                 filename: TargetFilename,
                 targetEdit: EditTargetItem): Future[JsonSignedPayload] =
    runTargetsChanges(
      repoId = repoId,
      metadataTransform = TargetItemTransformations.transformItemsForEdit(filename, targetEdit),
      delegations = () => Future.successful(None),
      repoStateChanges = () =>
        for {
          _ <- signedRoleGeneration.ensureTargetsCanBeSigned(repoId)
          existingTarget <- targetItemRepo.findByFilename(repoId, filename)
          newCustomJson = existingTarget.custom.map { existingCustom =>
            TargetItemTransformations.transformTargetCustomForEdit(existingCustom, targetEdit)
          }
          _ <- targetItemRepo.setCustom(repoId, filename, newCustomJson)
          json <- signedRoleGeneration.regenerateAllSignedRoles(repoId)
        } yield json
    )

  def updateProprietaryCustom(repoId: RepoId,
                              filename: TargetFilename,
                              proprietaryJson: Json): Future[JsonSignedPayload] =
    runTargetsChanges(
      repoId = repoId,
      metadataTransform =
        TargetItemTransformations.transformItemsForProprietaryUpdate(filename, proprietaryJson),
      delegations = () => Future.successful(None),
      repoStateChanges = () =>
        for {
          existing <- targetItemRepo.findByFilename(repoId, filename)
          newCustomJson = existing.custom.map { custom =>
            TargetItemTransformations.transformTargetCustomForProprietaryUpdate(
              custom,
              proprietaryJson
            )
          }
          _ <- targetItemRepo.setCustom(repoId, filename, newCustomJson)
          json <- signedRoleGeneration.regenerateAllSignedRoles(repoId)
        } yield json
    )

  def addDelegations(namespace: Namespace,
                     repoId: RepoId,
                     payload: List[Delegation],
                     trustedDelegations: TrustedDelegations,
                     signedRoleGeneration: SignedRoleGeneration,
                     signedRoleRepo: SignedRoleRepositorySupport): Future[JsonSignedPayload] =
    runTargetsChanges(
      repoId = repoId,
      metadataTransform = items => Success(items),
      delegations = () =>
        for {
          existingTargetsRole <- signedRoleRepo.signedRoleRepository.find[TargetsRole](repoId)
          delegationsBlock <- trustedDelegations
            .validate(payload, existingTargetsRole.role)
            .fold(
              errors => FastFuture.failed(Errors.InvalidTrustedDelegations(errors)),
              FastFuture.successful
            )
        } yield Some(delegationsBlock),
      repoStateChanges = () =>
        for {
          json <- trustedDelegations.add(repoId, payload)(signedRoleGeneration)
          _ <- tufTargetsPublisher.targetsMetaModified(namespace)
        } yield json
    )

  def removeDelegation(namespace: Namespace,
                       repoId: RepoId,
                       delegatedRoleName: DelegatedRoleName,
                       trustedDelegations: TrustedDelegations,
                       signedRoleGeneration: SignedRoleGeneration): Future[JsonSignedPayload] =
    runTargetsChanges(
      repoId = repoId,
      metadataTransform = items => Success(items),
      delegations = () =>
        trustedDelegations.getTrustedDelegationsBlock(repoId).map { delegations =>
          delegations.map { d =>
            val newRoles = d.roles.filter(_.name != delegatedRoleName)
            d.copy(roles = newRoles)
          }
        },
      repoStateChanges = () =>
        for {
          json <- trustedDelegations.remove(repoId, delegatedRoleName)(signedRoleGeneration)
          _ <- tufTargetsPublisher.targetsMetaModified(namespace)
        } yield json
    )

  def setDelegationKeys(namespace: Namespace,
                        repoId: RepoId,
                        keys: List[TufKey],
                        trustedDelegations: TrustedDelegations,
                        signedRoleGeneration: SignedRoleGeneration): Future[JsonSignedPayload] =
    runTargetsChanges(
      repoId = repoId,
      metadataTransform = items => Success(items), // No item transformation for delegations
      delegations = () =>
        trustedDelegations.getTrustedDelegationsBlock(repoId).map { maybeDelegations =>
          maybeDelegations
            .map { delegations =>
              delegations.copy(keys = keys.map(k => (k.id, k)).toMap)
            }
            .orElse(Some(Delegations(keys.map(k => (k.id, k)).toMap, List())))
        },
      repoStateChanges = () =>
        for {
          json <- trustedDelegations.setKeys(repoId, keys)(signedRoleGeneration)
          _ <- tufTargetsPublisher.targetsMetaModified(namespace)
        } yield json
    )

}
