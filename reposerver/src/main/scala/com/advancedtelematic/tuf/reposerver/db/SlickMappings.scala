package com.advancedtelematic.tuf.reposerver.db


import com.advancedtelematic.libats.slick.db.SlickCirceMapper
import com.advancedtelematic.libats.codecs.CirceRefined._
import com.advancedtelematic.libtuf.data.ValidatedString
import com.advancedtelematic.libtuf.data.ClientDataType.DelegatedPathPattern
import com.advancedtelematic.libtuf.data.TufDataType.KeyId
import com.advancedtelematic.libtuf.data.ValidatedString.{ValidatedString, ValidatedStringValidation}
import com.advancedtelematic.tuf.reposerver.data.RepositoryDataType.StorageMethod
import com.advancedtelematic.tuf.reposerver.data.RepositoryDataType.StorageMethod.StorageMethod
import slick.jdbc.MySQLProfile.api._

import scala.reflect.ClassTag

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

  implicit val listDelegationPath = SlickCirceMapper.circeMapper[List[DelegatedPathPattern]]
  implicit val listKeyId = SlickCirceMapper.circeMapper[List[KeyId]]
}

object SlickValidatedString {
  implicit def validatedStringMapper[W <: ValidatedString : ClassTag](implicit validation: ValidatedStringValidation[W]) = {
    implicit val decoder = ValidatedString.validatedStringDecoder[W]
    implicit val encoder = ValidatedString.validatedStringEncoder[W]
    SlickCirceMapper.circeMapper[W]
  }
}