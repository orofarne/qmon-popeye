package popeye.transport.legacy

import popeye.transport.proto.Message.{Batch, Event}
import org.codehaus.jackson.{JsonToken, JsonParser}
import com.google.protobuf.{ByteString => GoogleByteString}
import akka.actor.{ActorLogging, Actor}
import akka.actor.Status.Failure

class ParserActor extends Actor with ActorLogging {
  def receive = {
    case ParseRequest(data) => {
      try {
        sender ! ParseResult(new JsonToEventParser(data).toList)
      } catch {
        case ex: Throwable => sender ! Failure(ex)
        throw ex
      }
    }
  }
}

case class ParseRequest(data: Array[Byte])

case class ParseResult(batch: List[Event])

class JsonToEventParser(data: Array[Byte]) extends Traversable[Event] {

  def parseValue[U](metric: String, f: (Event) => U, parser: JsonParser) = {
    val event = Event.newBuilder()
    require(parser.getCurrentToken == JsonToken.START_OBJECT)
    while (parser.nextToken != JsonToken.END_OBJECT) {
      require(parser.getCurrentToken == JsonToken.FIELD_NAME)
      parser.nextToken
      parser.getCurrentName match {
        case "type" => {
          require(parser.getText.equalsIgnoreCase("numeric"))
        }
        case "timestamp" => {
          require(parser.getCurrentToken == JsonToken.VALUE_NUMBER_INT)
          event.setTimestamp(parser.getLongValue)
        }
        case "value" => {
          parser.getCurrentToken match {
            case JsonToken.VALUE_NUMBER_INT => {
              event.setIntValue(parser.getLongValue)
            }
            case JsonToken.VALUE_NUMBER_FLOAT => {
              event.setFloatValue(parser.getFloatValue)
            }
            case _ => throw new IllegalArgumentException("Value expected to be float or long")
          }
        }
      }
    }
    event.setMetric(metric)
    f(event.build())
    require(parser.getCurrentToken == JsonToken.END_OBJECT)
  }

  def parseMetric[U](f: (Event) => U, parser: JsonParser) = {
    require(parser.getCurrentToken == JsonToken.START_OBJECT)
    parser.nextToken
    val metric = parser.getCurrentName
    parser.nextToken match {
      case JsonToken.START_ARRAY => {
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          parseValue(metric, f, parser)
        }
      }
      case JsonToken.START_OBJECT => parseValue(metric, f, parser)
      case _ => throw new IllegalArgumentException("Object or Array expected, got " + parser.getCurrentToken)
    }
  }

  def parseArray[U](f: (Event) => U, parser: JsonParser) = {
    require(parser.getCurrentToken == JsonToken.START_ARRAY)
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      parseMetric(f, parser)
    }
    parser.nextToken
  }

  def foreach[U](f: (Event) => U) {
    val parser: JsonParser = LegacyHttpHandler.parserFactory.createJsonParser(data)

    parser.nextToken match {
      case JsonToken.START_ARRAY => parseArray(f, parser)
      case JsonToken.START_OBJECT => parseMetric(f, parser)
      case _ => throw new IllegalArgumentException("Object or Array expected, got " + parser.getCurrentToken)
    }
  }
}
