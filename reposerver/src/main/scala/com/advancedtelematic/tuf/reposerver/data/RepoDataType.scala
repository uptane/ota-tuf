package com.advancedtelematic.tuf.reposerver.data

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.libats.data.DataType.Checksum
import com.advancedtelematic.libtuf.data.ClientDataType.*
import com.advancedtelematic.libtuf.data.TufDataType.{
  HardwareIdentifier,
  RepoId,
  TargetFilename,
  TargetName,
  TargetVersion
}
import com.advancedtelematic.tuf.reposerver.data.RepoDataType.Package.TargetOrigin
import eu.timepit.refined.api.Refined
import eu.timepit.refined.predicates.all.NonEmpty
import io.circe.{Codec, Json}

import java.time.Instant

object RepoDataType {

  object StorageMethod {
    sealed trait StorageMethod
    object Managed extends StorageMethod
    object Unmanaged extends StorageMethod
    object CliManaged extends StorageMethod
  }

  import StorageMethod._

  case class TargetItem(repoId: RepoId,
                        filename: TargetFilename,
                        uri: Option[Uri],
                        checksum: Checksum,
                        length: Long,
                        custom: Option[TargetCustom] = None,
                        storageMethod: StorageMethod = Managed)

  case class DelegatedTargetItem(repoId: RepoId,
                                 filename: TargetFilename,
                                 roleName: DelegatedRoleName,
                                 checksum: Checksum,
                                 length: Long,
                                 custom: Option[Json])

  case class AddDelegationFromRemoteRequest(uri: Uri,
                                            remoteHeaders: Option[Map[String, String]] = None,
                                            friendlyName: Option[DelegationFriendlyName] = None)

  case class DelegationInfo(lastFetched: Option[Instant],
                            remoteUri: Option[Uri],
                            friendlyName: Option[DelegationFriendlyName] = None)

  object Package {

    type ValidTargetOrigin = NonEmpty
    type TargetOrigin = Refined[String, ValidTargetOrigin]


    implicit class ClientPackageConversion(p:Package) {
      def toClientPackage(): ClientPackage = {
        ClientPackage(p.name, p.version, p.filename, p.origin, p.length, p.hashes, p.uri, p.hardwareIds, p.createdAt, p.customData)
      }
    }

    implicit class ClientAggregatedPackageConversion(p:AggregatedPackage) {
      def toClientAggregatedPackage(): ClientAggregatedPackage = {
        ClientAggregatedPackage(p.name, p.versions, p.hardwareIds, p.origins, p.lastVersionAt)
      }
    }
  }

  case class Package(name: TargetName,
                     version: TargetVersion,
                     filename: TargetFilename,
                     origin: TargetOrigin,
                     length: Long,
                     hashes: ClientHashes,
                     uri: Option[Uri],
                     hardwareIds: List[HardwareIdentifier],
                     createdAt: Instant,
                     customData: Option[Json])

  case class AggregatedPackage(name: TargetName,
                               versions: Seq[TargetVersion],
                               hardwareIds: Seq[HardwareIdentifier],
                               origins: Seq[TargetOrigin],
                               lastVersionAt: Instant)

}
