package popeye.transport.legacy

import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import akka.actor._
import com.google.protobuf.{ByteString => GoogleByteString}
import spray.can.Http
import spray.can.server.Stats
import spray.util._
import spray.http._
import HttpMethods._
import MediaTypes._
import org.codehaus.jackson.{JsonParseException, JsonFactory}
import popeye.transport.proto.Message.{Event, Batch}
import akka.actor.SupervisorStrategy.{Stop, Escalate}
import scala.util.{Failure, Success}
import popeye.transport.kafka.PersistBatch
import java.util.concurrent.TimeoutException
import scala.collection.JavaConversions.asJavaIterable


/**
 * @author Andrey Stepachev
 */
class LegacyHttpHandler(kafkaProducer: ActorRef) extends Actor with SprayActorLogging {

  implicit val timeout: Timeout = 1.second // for the actor 'asks'
  val kafkaTimeout: Timeout = new Timeout(context.system.settings.config
      .getDuration("kafka.transport.send.timeout").asInstanceOf[FiniteDuration])

  import context.dispatcher

  override val supervisorStrategy = {
    OneForOneStrategy()({
      case _: Exception => Stop
    })
  }


  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected => sender ! Http.Register(self)

    case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
      sender ! index

    case HttpRequest(GET, Uri.Path("/read"), _, _, _) =>
      sender ! index

    case HttpRequest(POST, Uri.Path("/write"), _, entity, _) =>
      val client = sender
      val parser: ActorRef = context.actorOf(Props[ParserActor])

      val future = for {
        parsed <- ask(parser, ParseRequest(entity.buffer)).mapTo[ParseResult]
        stored <- ask(kafkaProducer, PersistBatch(
          Batch.newBuilder().addAllEvent(parsed.batch).build
        ))(kafkaTimeout)
      } yield {
        stored
      }
      future onComplete {
        case Success(r) => client ! HttpResponse(200, "Ok")
        case Failure(ex: JsonParseException) =>
          client ! HttpResponse(status = StatusCodes.UnprocessableEntity, entity = ex.getMessage)
        case Failure(ex) =>
          log.error(ex, "Failed")
          client ! HttpResponse(status = StatusCodes.InternalServerError, entity = ex.getMessage)
      }

    case HttpRequest(GET, Uri.Path("/server-stats"), _, _, _) =>
      val client = sender
      context.actorFor("/user/IO-HTTP/listener-0") ? Http.GetStats onSuccess {
        case x: Stats => client ! statsPresentation(x)
      }

    case _: HttpRequest => sender ! HttpResponse(status = 404, entity = "Unknown resource!")

  }


  def statsPresentation(s: Stats) = HttpResponse(
    entity = HttpEntity(`text/html`,
      <html>
        <body>
          <h1>HttpServer Stats</h1>
          <table>
            <tr>
              <td>uptime:</td> <td>
              {s.uptime.formatHMS}
            </td>
            </tr>
            <tr>
              <td>totalRequests:</td> <td>
              {s.totalRequests}
            </td>
            </tr>
            <tr>
              <td>openRequests:</td> <td>
              {s.openRequests}
            </td>
            </tr>
            <tr>
              <td>maxOpenRequests:</td> <td>
              {s.maxOpenRequests}
            </td>
            </tr>
            <tr>
              <td>totalConnections:</td> <td>
              {s.totalConnections}
            </td>
            </tr>
            <tr>
              <td>openConnections:</td> <td>
              {s.openConnections}
            </td>
            </tr>
            <tr>
              <td>maxOpenConnections:</td> <td>
              {s.maxOpenConnections}
            </td>
            </tr>
            <tr>
              <td>requestTimeouts:</td> <td>
              {s.requestTimeouts}
            </td>
            </tr>
          </table>
        </body>
      </html>.toString()
    )
  )

  def eventsPresentation(events: Traversable[Event]) = {
    val eventList = events.map({
      _.toString
    }).mkString("<br/>")
    HttpResponse(
      entity = HttpEntity(`text/html`,
        <html>
          <body>
            <h1>Got events</h1>{eventList}
          </body>
        </html>.toString()
      )
    )
  }


  lazy val index = HttpResponse(
    entity = HttpEntity(`text/html`,
      <html>
        <body>
          <h1>Say hello to
            <i>Popeye</i>
            !</h1>
          <ul>
            <li>
              <a href="/read">Read points</a>
            </li>
            <li>
              <a href="/write">Write points</a>
            </li>
            <li>
              <a href="/q">Query</a>
            </li>
            <li>
              <a href="/server-stats">/server-stats</a>
            </li>
          </ul>
        </body>
      </html>.toString()
    )
  )
}


object LegacyHttpHandler {
  val parserFactory: JsonFactory = new JsonFactory()
}
