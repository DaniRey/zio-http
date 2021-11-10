package zhttp.service.server

import io.netty.channel.epoll.{Epoll, EpollServerSocketChannel}
import io.netty.channel.kqueue.{KQueue, KQueueServerSocketChannel}
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{ChannelFactory => JChannelFactory, ServerChannel}
import io.netty.incubator.channel.uring.IOUringServerSocketChannel
import zhttp.service.{ChannelFactory, EventLoopGroup}
import zio.{Task, ZManaged}

sealed trait TransportType
object TransportType    {
  case object Nio    extends TransportType
  case object Epoll  extends TransportType
  case object KQueue extends TransportType
  case object URing  extends TransportType
  case object Auto   extends TransportType

  import zhttp.service.server.TransportFactory._

  def make(transType: TransportType, nThreads: Int = 0) = transType match {
    case Nio    => ZManaged.fromEffect(nio).zip(EventLoopGroup.Live.nio(nThreads))
    case Epoll  => ZManaged.fromEffect(epoll).zip(EventLoopGroup.Live.epoll(nThreads))
    case KQueue => ZManaged.fromEffect(kQueue).zip(EventLoopGroup.Live.kQueue(nThreads))
    case URing  => ZManaged.fromEffect(uring).zip(EventLoopGroup.Live.uring(nThreads))
    case Auto   => ZManaged.fromEffect(auto).zip(EventLoopGroup.Live.auto(nThreads))
  }
}
object TransportFactory {
  def nio: Task[JChannelFactory[ServerChannel]]    = ChannelFactory.make(() => new NioServerSocketChannel())
  def epoll: Task[JChannelFactory[ServerChannel]]  = ChannelFactory.make(() => new EpollServerSocketChannel())
  def uring: Task[JChannelFactory[ServerChannel]]  = ChannelFactory.make(() => new IOUringServerSocketChannel())
  def kQueue: Task[JChannelFactory[ServerChannel]] = ChannelFactory.make(() => new KQueueServerSocketChannel())
  def auto: Task[JChannelFactory[ServerChannel]]   =
    if (Epoll.isAvailable) epoll
    else if (KQueue.isAvailable) kQueue
    else nio
}
