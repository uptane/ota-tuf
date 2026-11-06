package com.advancedtelematic.libtuf_server.repo.client

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.Errors.RemoteServiceError
import com.advancedtelematic.libats.http.tracing.NullServerRequestTracing
import com.advancedtelematic.libats.http.tracing.Tracing.ServerRequestTracing
import com.advancedtelematic.libtuf.data.TufDataType.TargetFilename
import eu.timepit.refined.api.Refined
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.headers.Location
import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpRequest,
  HttpResponse,
  ResponseEntity,
  StatusCodes
}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

class ReposerverHttpClientSpec
    extends AnyFunSuite
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  implicit val system: ActorSystem = ActorSystem("reposerver-http-client-spec")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val tracing: ServerRequestTracing = new NullServerRequestTracing

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(15, Millis))

  override def afterAll(): Unit = system.terminate()

  private val namespace = Namespace(UUID.randomUUID().toString)
  private val filename: TargetFilename = Refined.unsafeApply("pkg-1.0.0")
  private val content = ByteString("some,data,to,send\nanother,row,to,send\n")

  private def singleUseEntity(bytes: ByteString): ResponseEntity = {
    val used = new AtomicBoolean(false)
    val source = Source.lazySource { () =>
      if (used.getAndSet(true)) Source.failed(new IllegalStateException("body already consumed"))
      else Source.single(bytes)
    }
    HttpEntity(ContentTypes.`application/octet-stream`, bytes.length.toLong, source)
  }

  private def clientAnswering(response: HttpResponse): ReposerverHttpClient =
    new ReposerverHttpClient(
      "http://reposerver",
      (_: HttpRequest) => Future.successful(response)
    )

  test("fetchTargetContent returns the body of a target the reposerver serves itself") {
    val client = clientAnswering(HttpResponse(StatusCodes.OK, entity = singleUseEntity(content)))

    val response = client.fetchTargetContent(namespace, filename).futureValue

    response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).futureValue shouldBe content
  }

  test("fetchTargetContent returns the location of a target stored out of band") {
    val url = "https://bucket.s3.amazonaws.com/pkg?X-Amz-Signature=abc"
    val client = clientAnswering(HttpResponse(StatusCodes.Found, headers = List(Location(url))))

    val response = client.fetchTargetContent(namespace, filename).futureValue

    response.status shouldBe StatusCodes.Found
    response.header[Location].map(_.uri.toString) shouldBe Some(url)
  }

  test("fetchTargetContent fails when the target does not exist") {
    val client = clientAnswering(HttpResponse(StatusCodes.NotFound))

    client.fetchTargetContent(namespace, filename).failed.futureValue shouldBe a[
      RemoteServiceError
    ]
  }
}
