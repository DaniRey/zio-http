package zhttp.service.server

sealed trait LogLevel { self =>

  def toNettyLogLevel: io.netty.handler.logging.LogLevel = self match {
    case LogLevel.OFF   => io.netty.handler.logging.LogLevel.ERROR
    case LogLevel.TRACE => io.netty.handler.logging.LogLevel.TRACE
    case LogLevel.DEBUG => io.netty.handler.logging.LogLevel.DEBUG
    case LogLevel.INFO  => io.netty.handler.logging.LogLevel.INFO
    case LogLevel.WARN  => io.netty.handler.logging.LogLevel.WARN
    case LogLevel.ERROR => io.netty.handler.logging.LogLevel.ERROR
  }

  def toZhttpLogging: zhttp.logging.LogLevel = self match {
    case LogLevel.OFF   => zhttp.logging.LogLevel.OFF
    case LogLevel.TRACE => zhttp.logging.LogLevel.TRACE
    case LogLevel.DEBUG => zhttp.logging.LogLevel.DEBUG
    case LogLevel.INFO  => zhttp.logging.LogLevel.INFO
    case LogLevel.WARN  => zhttp.logging.LogLevel.WARN
    case LogLevel.ERROR => zhttp.logging.LogLevel.ERROR
  }

}

object LogLevel {
  case object OFF   extends LogLevel
  case object TRACE extends LogLevel
  case object DEBUG extends LogLevel
  case object INFO  extends LogLevel
  case object WARN  extends LogLevel
  case object ERROR extends LogLevel

}
