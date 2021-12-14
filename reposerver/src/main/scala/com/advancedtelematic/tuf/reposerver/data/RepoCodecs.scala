package com.advancedtelematic.tuf.reposerver.data

import com.advancedtelematic.tuf.reposerver.data.RepoDataType.AddDelegationFromRemoteRequest

import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libats.http.HttpCodecs._

object RepoCodecs {
  implicit val addDelegationFromRemoteRequestCodec: io.circe.Codec[AddDelegationFromRemoteRequest] = io.circe.generic.semiauto.deriveCodec[AddDelegationFromRemoteRequest]
}
