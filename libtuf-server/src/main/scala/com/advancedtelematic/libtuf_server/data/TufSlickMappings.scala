package com.advancedtelematic.libtuf_server.data

import com.advancedtelematic.libats.codecs.CirceAts.*
import com.advancedtelematic.libats.data.DataType.Checksum
import com.advancedtelematic.libats.slick.codecs.SlickEnumMapper
import com.advancedtelematic.libats.slick.db.SlickEncryptedColumn
import com.advancedtelematic.libtuf.data.ClientCodecs.*
import com.advancedtelematic.libtuf.data.ClientDataType.TargetCustom
import com.advancedtelematic.libtuf.data.TufCodecs.*
import com.advancedtelematic.libtuf.data.TufDataType.{EcPrime256KeyType, Ed25519KeyType, JsonSignedPayload, KeyType, RoleType, RsaKeyType, TufKey, TufPrivateKey}
import com.advancedtelematic.libats.slick.db.SlickCirceMapper
import com.advancedtelematic.libtuf.data.TufCodecs
import slick.jdbc.MySQLProfile.api.*


object TufSlickMappings {

  implicit val keyTypeMapper = MappedColumnType.base[KeyType, String](
    {
      case RsaKeyType => "RSA"
      case Ed25519KeyType => "ED25519"
      case EcPrime256KeyType => "ECPRIME256V1"
    },
    {
      case "RSA" => RsaKeyType
      case "ED25519" => Ed25519KeyType
      case "ECPRIME256V1" => EcPrime256KeyType
    }
  )

  implicit val roleTypeMapper = SlickEnumMapper.enumMapper(RoleType)

  implicit val checksumMapper = SlickCirceMapper.circeMapper[Checksum]

  implicit val targetCustomMapper = SlickCirceMapper.circeMapper[TargetCustom]

  implicit val jsonSignedPayloadMapper = {
    implicit val encoder = TufCodecs.jsonSignedPayloadEncoder
    SlickCirceMapper.circeMapper[JsonSignedPayload]
  }

  implicit val tufKeyMapper = SlickCirceMapper.circeMapper[TufKey]

  implicit val encryptedTufPrivateKeyMapper = SlickEncryptedColumn.encryptedColumnJsonMapper[TufPrivateKey]
}
