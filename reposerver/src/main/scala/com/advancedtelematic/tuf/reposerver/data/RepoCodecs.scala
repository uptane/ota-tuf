package com.advancedtelematic.tuf.reposerver.data

import com.advancedtelematic.tuf.reposerver.data.RepoDataType.{AddDelegationFromRemoteRequest, DelegationInfo}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libats.http.HttpCodecs._

object RepoCodecs {
  implicit val addDelegationFromRemoteRequestCodec: io.circe.Codec[AddDelegationFromRemoteRequest] = io.circe.generic.semiauto.deriveCodec[AddDelegationFromRemoteRequest]

  implicit val delegationInfoCodec: io.circe.Codec[DelegationInfo] = io.circe.generic.semiauto.deriveCodec[DelegationInfo]
}
