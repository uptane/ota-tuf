package com.advancedtelematic.tuf.reposerver.http

import com.advancedtelematic.libtuf.data.ClientDataType.TargetCustom
import com.advancedtelematic.libtuf_server.repo.client.ReposerverClient.EditTargetItem
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.TargetItem
import com.advancedtelematic.tuf.reposerver.http.Errors.TargetNotFoundError
import com.advancedtelematic.libtuf.data.TufDataType.TargetFilename
import io.circe.Json
import scala.util.{Failure, Success, Try}

/**
 * Pure transformation functions for target items. These are used mostly with offline
 * keys, so we can return the metadata as it would have been transformed if the operation
 * had succeeded, but we also use them directly when editing custom metadata fields, just
 * so we have a single source of truth.
 *
 * Note that we have various tests that should break if these transformations do something
 * different from what reposerver does when the targets key is online.
 */
object TargetItemTransformations {

  def mergeCustomJson(existing: Json, provided: Json): Json =
    if (provided == Json.obj())
      provided
    else
      (existing.asObject, provided.asObject) match {
        case (Some(lhs), Some(rhs)) =>
          Json.fromJsonObject(rhs.toIterable.foldLeft(lhs) { case (acc, (k, v)) =>
            acc.add(k, v)
          })
        case _ => provided
      }

  def transformTargetCustomForEdit(
    existingCustom: TargetCustom,
    targetEdit: EditTargetItem
  ): TargetCustom = {
    existingCustom.copy(
      uri = if (targetEdit.uri.isDefined) targetEdit.uri else existingCustom.uri,
      hardwareIds =
        if (targetEdit.hardwareIds.nonEmpty) targetEdit.hardwareIds
        else existingCustom.hardwareIds,
      proprietary = targetEdit.proprietaryCustom.getOrElse(existingCustom.proprietary)
    )
  }

  def transformTargetCustomForProprietaryUpdate(
    existingCustom: TargetCustom,
    proprietaryJson: Json
  ): TargetCustom = {
    val mergedProprietary = if (proprietaryJson == Json.obj()) {
      proprietaryJson
    } else {
      mergeCustomJson(existingCustom.proprietary, proprietaryJson)
    }
    existingCustom.copy(proprietary = mergedProprietary)
  }

  def transformItemsForAdd(newItem: TargetItem)(allItems: Seq[TargetItem]): Try[Seq[TargetItem]] =
    Success(allItems :+ newItem)

  def transformItemsForDelete(filename: TargetFilename)(allItems: Seq[TargetItem]): Try[Seq[TargetItem]] =
    Success(allItems.filter(_.filename != filename))

  def transformItemsForEdit(filename: TargetFilename, targetEdit: EditTargetItem)(allItems: Seq[TargetItem]): Try[Seq[TargetItem]] = {
    allItems.find(_.filename == filename)
      .map { existingTarget =>
        val newCustomJson = existingTarget.custom.map { existingCustom =>
          transformTargetCustomForEdit(existingCustom, targetEdit)
        }
        Success(
          allItems.map { item =>
            if (item.filename == filename) {
              item.copy(custom = newCustomJson)
            } else {
              item
            }
          }
        )
      }
      .getOrElse(Failure(TargetNotFoundError))
  }

  def transformItemsForProprietaryUpdate(filename: TargetFilename, proprietaryJson: Json)(allItems: Seq[TargetItem]): Try[Seq[TargetItem]] = {
    allItems.find(_.filename == filename)
      .map { existingTarget =>
        val newCustomJson = existingTarget.custom.map { custom =>
          transformTargetCustomForProprietaryUpdate(custom, proprietaryJson)
        }
        Success(
          allItems.map { item =>
            if (item.filename == filename) {
              item.copy(custom = newCustomJson)
            } else {
              item
            }
          }
        )
      }
      .getOrElse(Failure(TargetNotFoundError))
  }
}

