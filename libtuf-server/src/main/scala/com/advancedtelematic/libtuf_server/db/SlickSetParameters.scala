package com.advancedtelematic.libtuf_server.db

import slick.jdbc.SetParameter
import java.time.Instant
import java.sql.Timestamp
import com.advancedtelematic.libats.data.UUIDKey.UUIDKey

object SlickSetParameters {

  implicit val setOptionInstant: SetParameter[Option[Instant]] =
    SetParameter.SetTimestampOption.contramap(_.map(Timestamp.from))

  implicit def setUuid[T <: UUIDKey]: SetParameter[T] =
    SetParameter.SetString.contramap(_.uuid.toString)

}
