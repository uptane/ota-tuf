package com.advancedtelematic.tuf.reposerver.target_store

import java.io.File
import java.time.Duration

import scala.async.Async._
import scala.jdk.CollectionConverters._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.stream.scaladsl.{FileIO, Source, StreamConverters}
import org.apache.pekko.util.ByteString
import com.advancedtelematic.libtuf.data.TufDataType.{
  GetSignedUrlResult,
  InitMultipartUploadResult,
  MultipartUploadId,
  RepoId,
  TargetFilename,
  UploadPartETag
}
import com.advancedtelematic.tuf.reposerver.Settings
import com.advancedtelematic.tuf.reposerver.target_store.TargetStoreEngine.{
  TargetRedirect,
  TargetRetrieveResult,
  TargetStoreResult
}
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentials,
  AwsCredentialsProvider,
  DefaultCredentialsProvider
}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  CompletedMultipartUpload,
  CompletedPart,
  CompleteMultipartUploadRequest,
  CreateMultipartUploadRequest,
  DeleteObjectRequest,
  GetObjectRequest,
  GetUrlRequest,
  HeadObjectRequest,
  PutObjectRequest,
  UploadPartRequest
}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.{
  GetObjectPresignRequest,
  PutObjectPresignRequest,
  UploadPartPresignRequest
}

import scala.concurrent._
import scala.concurrent.Future
import scala.util.Try

class S3TargetStoreEngine(credentials: S3Credentials)(implicit val system: ActorSystem)
    extends TargetStoreEngine
    with Settings {

  import system.dispatcher

  private val bucketId = credentials.bucketId

  private val log = LoggerFactory.getLogger(this.getClass)

  private val credentialsProvider: AwsCredentialsProvider =
    if (credentials.hasExplicitCredentials) {
      log.info("Using explicit AWS access/secret key credentials for S3")
      credentials
    } else {
      log.info("No explicit AWS credentials configured, using DefaultCredentialsProvider (supports IRSA/instance profile/env vars)")
      DefaultCredentialsProvider.create()
    }

  private lazy val s3client = S3Client
    .builder()
    .credentialsProvider(credentialsProvider)
    .region(credentials.region)
    .dualstackEnabled(true)
    .build()

  private lazy val presigner = S3Presigner
    .builder()
    .credentialsProvider(credentialsProvider)
    .region(credentials.region)
    .dualstackEnabled(true)
    .build()

  override def store(repoId: RepoId,
                     filename: TargetFilename,
                     fileData: Source[ByteString, Any]): Future[TargetStoreResult] = {
    val tempFile = File.createTempFile("s3file", ".tmp")

    // The s3 sdk requires us to specify the file size if using a stream
    // so we always need to cache the file into the filesystem before uploading
    val sink = FileIO.toPath(tempFile.toPath).mapMaterializedValue {
      _.flatMap { _ =>
        upload(repoId, tempFile, filename).andThen { case _ => Try(tempFile.delete()) }
      }.recoverWith { case err =>
        Try(tempFile.delete())
        FastFuture.failed(err)
      }
    }

    write(fileData, sink)
  }

  override def storeStream(repoId: RepoId,
                           filename: TargetFilename,
                           fileData: Source[ByteString, Any],
                           size: Long): Future[TargetStoreResult] = {
    val storagePath = storageFilename(repoId, filename)
    val sink = StreamConverters.asInputStream().mapMaterializedValue { is =>
      val request = PutObjectRequest.builder()
        .bucket(bucketId)
        .key(storagePath.toString)
        .contentLength(size)
        .build()

      log.info(s"Uploading $filename to amazon s3 using streaming upload")

      val uploadF = async {
        await(Future(blocking(s3client.putObject(request, RequestBody.fromInputStream(is, size)))))
        log.info(s"$filename with size $size uploaded to s3")
        await(Future(blocking(
          s3client.utilities().getUrl(GetUrlRequest.builder()
            .bucket(bucketId)
            .key(storagePath.toString)
            .build())
        )))
      }

      uploadF.map(uri => Uri(uri.toString) -> size)
    }

    write(fileData, sink)
  }

  protected def upload(repoId: RepoId,
                       file: File,
                       filename: TargetFilename): Future[(Uri, Long)] = {
    val storagePath = storageFilename(repoId, filename)
    val request = PutObjectRequest.builder()
      .bucket(credentials.bucketId)
      .key(storagePath.toString)
      .build()

    log.info(s"Uploading ${filename.value} to amazon s3")

    async {
      await(Future(blocking(s3client.putObject(request, RequestBody.fromFile(file)))))
      val uri = await(Future(blocking(
        s3client.utilities().getUrl(GetUrlRequest.builder()
          .bucket(bucketId)
          .key(storagePath.toString)
          .build())
      )))
      val metadata = await(Future {
        blocking(s3client.headObject(HeadObjectRequest.builder()
          .bucket(bucketId)
          .key(storagePath.toString)
          .build()))
      })

      log.info(s"$filename uploaded to s3")

      (Uri(uri.toString), metadata.contentLength())
    }
  }

  override def retrieve(repoId: RepoId, filename: TargetFilename): Future[TargetRetrieveResult] = {
    val storagePath = storageFilename(repoId, filename)
    val publicExpireTime = Duration.ofDays(1)
    Future {
      val signedUri = blocking {
        presigner.presignGetObject(GetObjectPresignRequest.builder()
          .signatureDuration(publicExpireTime)
          .getObjectRequest(GetObjectRequest.builder()
            .bucket(bucketId)
            .key(storagePath.toString)
            .build())
          .build())
          .url()
      }

      TargetRedirect(Uri(signedUri.toURI.toString))
    }
  }

  override def delete(repoId: RepoId, filename: TargetFilename): Future[Unit] = Future {
    blocking {
      val storagePath = storageFilename(repoId, filename)
      s3client.deleteObject(DeleteObjectRequest.builder()
        .bucket(bucketId)
        .key(storagePath.toString)
        .build())
    }
  }

  override def buildStorageUri(repoId: RepoId,
                               filename: TargetFilename,
                               length: Long): Future[Uri] = {
    val objectId = storageFilename(repoId, filename).toString

    log.info(s"Signing s3 url for $objectId")

    FastFuture.successful {
      val presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
        .signatureDuration(Duration.ofHours(3))
        .putObjectRequest(PutObjectRequest.builder()
          .bucket(bucketId)
          .key(objectId)
          .contentLength(length)
          .build())
        .build())
      log.debug(s"Signed s3 url for $objectId")
      presigned.url().toString
    }
  }

  override def initiateMultipartUpload(
    repoId: RepoId,
    filename: TargetFilename): Future[InitMultipartUploadResult] = FastFuture {
    val objectId: String = storageFilename(repoId, filename).toString
    val req = CreateMultipartUploadRequest.builder()
      .bucket(bucketId)
      .key(objectId)
      .build()
    Try(s3client.createMultipartUpload(req))
      .map(rs =>
        InitMultipartUploadResult(MultipartUploadId(rs.uploadId()), multipartUploadPartSize)
      )
  }

  override def buildSignedURL(repoId: RepoId,
                              filename: TargetFilename,
                              uploadId: MultipartUploadId,
                              partNumber: String,
                              md5: String,
                              contentLength: Int): Future[GetSignedUrlResult] = FastFuture {
    val objectId: String = storageFilename(repoId, filename).toString
    val req = UploadPartPresignRequest.builder()
      .signatureDuration(Duration.ofHours(3))
      .uploadPartRequest(UploadPartRequest.builder()
        .bucket(bucketId)
        .key(objectId)
        .uploadId(uploadId.value)
        .partNumber(partNumber.toInt)
        .contentMD5(md5)
        .contentLength(contentLength.toLong)
        .build())
      .build()
    Try(presigner.presignUploadPart(req)).map(r => GetSignedUrlResult(r.url()))
  }

  override def completeMultipartUpload(repoId: RepoId,
                                       filename: TargetFilename,
                                       uploadId: MultipartUploadId,
                                       partETags: Seq[UploadPartETag]): Future[Unit] =
    FastFuture {
      val objectId: String = storageFilename(repoId, filename).toString
      val completedParts = partETags.map(t =>
        CompletedPart.builder()
          .partNumber(t.part)
          .eTag(t.eTag.value)
          .build()
      )
      val req = CompleteMultipartUploadRequest.builder()
        .bucket(bucketId)
        .key(objectId)
        .uploadId(uploadId.value)
        .multipartUpload(CompletedMultipartUpload.builder()
          .parts(completedParts.asJava)
          .build())
        .build()
      Try(s3client.completeMultipartUpload(req))
    }

}

class S3Credentials(accessKey: String, secretKey: String, val bucketId: String, val region: Region)
    extends AwsCredentialsProvider {

  val hasExplicitCredentials: Boolean = accessKey.nonEmpty && secretKey.nonEmpty

  override def resolveCredentials(): AwsCredentials =
    AwsBasicCredentials.create(accessKey, secretKey)
}
