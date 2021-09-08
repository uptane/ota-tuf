package com.advancedtelematic.tuf.reposerver.db


import com.advancedtelematic.tuf.reposerver.data.RepositoryDataType.StorageMethod
import com.advancedtelematic.tuf.reposerver.data.RepositoryDataType.StorageMethod.StorageMethod
import slick.jdbc.MySQLProfile.api._

object SlickMappings {
  implicit val storageMethodMapping = MappedColumnType.base[StorageMethod, String](
    {
      case StorageMethod.CliManaged => "CliManaged"
      case StorageMethod.Managed => "Managed"
      case StorageMethod.Unmanaged => "Unmanaged"
    },
    {
      case "CliManaged" => StorageMethod.CliManaged
      case "Managed" => StorageMethod.Managed
      case "Unmanaged" =>  StorageMethod.Unmanaged
    }
  )
}
