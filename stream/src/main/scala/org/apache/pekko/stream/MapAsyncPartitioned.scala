/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.stream

import org.apache.pekko
import org.apache.pekko.dispatch.ExecutionContexts
import org.apache.pekko.stream.ActorAttributes.SupervisionStrategy
import org.apache.pekko.stream.Attributes.{ Name, SourceLocation }
import org.apache.pekko.stream.MapAsyncPartitioned._
import org.apache.pekko.stream.scaladsl.{ Flow, FlowWithContext, Source, SourceWithContext }
import org.apache.pekko.stream.stage._

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.control.{ NoStackTrace, NonFatal }
import scala.util.{ Failure, Success, Try }

private[stream] object MapAsyncPartitioned {

  private def extractPartitionWithCtx[In, Ctx, Partition](extract: In => Partition)(tuple: (In, Ctx)): Partition =
    extract(tuple._1)

  private def fWithCtx[In, Out, Ctx, Partition](f: (In, Partition) => Future[Out])(tuple: (In, Ctx),
      partition: Partition): Future[(Out, Ctx)] =
    f(tuple._1, partition).map(_ -> tuple._2)(ExecutionContexts.parasitic)

  def mapSourceOrdered[In, Out, Partition, Mat](source: Source[In, Mat], parallelism: Int)(
      extractPartition: In => Partition)(
      f: (In, Partition) => Future[Out]): Source[Out, Mat] =
    source.via(new MapAsyncPartitioned[In, Out, Partition](orderedOutput = true, parallelism, extractPartition, f))

  def mapSourceUnordered[In, Out, Partition, Mat](source: Source[In, Mat], parallelism: Int)(
      extractPartition: In => Partition)(
      f: (In, Partition) => Future[Out]): Source[Out, Mat] =
    source.via(new MapAsyncPartitioned[In, Out, Partition](orderedOutput = false, parallelism, extractPartition, f))

  def mapSourceWithContextOrdered[In, Ctx, T, Partition, Mat](flow: SourceWithContext[In, Ctx, Mat], parallelism: Int)(
      extractPartition: In => Partition)(
      f: (In, Partition) => Future[T]): SourceWithContext[T, Ctx, Mat] =
    flow.via(
      new MapAsyncPartitioned[(In, Ctx), (T, Ctx), Partition](
        orderedOutput = true,
        parallelism,
        extractPartitionWithCtx(extractPartition),
        fWithCtx[In, T, Ctx, Partition](f)))

  def mapSourceWithContextUnordered[In, Ctx, T, Partition, Mat](flow: SourceWithContext[In, Ctx, Mat],
      parallelism: Int)(extractPartition: In => Partition)(
      f: (In, Partition) => Future[T]): SourceWithContext[T, Ctx, Mat] =
    flow.via(
      new MapAsyncPartitioned[(In, Ctx), (T, Ctx), Partition](
        orderedOutput = false,
        parallelism,
        extractPartitionWithCtx(extractPartition),
        fWithCtx[In, T, Ctx, Partition](f)))

  def mapFlowOrdered[In, Out, T, Partition, Mat](flow: Flow[In, Out, Mat], parallelism: Int)(
      extractPartition: Out => Partition)(
      f: (Out, Partition) => Future[T]): Flow[In, T, Mat] =
    flow.via(new MapAsyncPartitioned[Out, T, Partition](orderedOutput = true, parallelism, extractPartition,
      f))

  def mapFlowUnordered[In, Out, T, Partition, Mat](flow: Flow[In, Out, Mat], parallelism: Int)(
      extractPartition: Out => Partition)(
      f: (Out, Partition) => Future[T]): Flow[In, T, Mat] =
    flow.via(new MapAsyncPartitioned[Out, T, Partition](orderedOutput = false, parallelism,
      extractPartition, f))

  def mapFlowWithContextOrdered[In, Out, CtxIn, CtxOut, T, Partition, Mat](
      flow: FlowWithContext[In, CtxIn, Out, CtxOut, Mat], parallelism: Int)(
      extractPartition: Out => Partition)(
      f: (Out, Partition) => Future[T]): FlowWithContext[In, CtxIn, T, CtxOut, Mat] =
    flow.via(
      new MapAsyncPartitioned[(Out, CtxOut), (T, CtxOut), Partition](
        orderedOutput = true,
        parallelism,
        extractPartitionWithCtx(extractPartition),
        fWithCtx[Out, T, CtxOut, Partition](f)))

  def mapFlowWithContextUnordered[In, Out, CtxIn, CtxOut, T, Partition, Mat](
      flow: FlowWithContext[In, CtxIn, Out, CtxOut, Mat], parallelism: Int)(extractPartition: Out => Partition)(
      f: (Out, Partition) => Future[T]): FlowWithContext[In, CtxIn, T, CtxOut, Mat] =
    flow.via(
      new MapAsyncPartitioned[(Out, CtxOut), (T, CtxOut), Partition](
        orderedOutput = false,
        parallelism,
        extractPartitionWithCtx(extractPartition),
        fWithCtx[Out, T, CtxOut, Partition](f)))

  private[stream] val NotYetThere: Failure[Nothing] = Failure(new Exception with NoStackTrace)

  private[stream] final class Holder[In, Out](
      val in: In,
      var out: Try[Out],
      callback: AsyncCallback[Holder[In, Out]]) extends (Try[Out] => Unit) {

    // To support both fail-fast when the supervision directive is Stop
    // and not calling the decider multiple times (#23888) we need to cache the decider result and re-use that
    private var cachedSupervisionDirective: Option[Supervision.Directive] = None

    def supervisionDirectiveFor(decider: Supervision.Decider, ex: Throwable): Supervision.Directive = {
      cachedSupervisionDirective match {
        case Some(d) => d
        case _ =>
          val d = decider(ex)
          cachedSupervisionDirective = Some(d)
          d
      }
    }

    def setOut(t: Try[Out]): Unit =
      out = t

    override def apply(t: Try[Out]): Unit = {
      setOut(t)
      callback.invoke(this)
    }
  }
}

private[stream] class MapAsyncPartitioned[In, Out, Partition](
    orderedOutput: Boolean,
    parallelism: Int,
    extractPartition: In => Partition,
    f: (In, Partition) => Future[Out]) extends GraphStage[FlowShape[In, Out]] {

  if (parallelism < 1) throw new IllegalArgumentException("parallelism must be at least 1")

  private val in = Inlet[In]("MapAsyncPartitionOrdered.in")
  private val out = Outlet[Out]("MapAsyncPartitionOrdered.out")

  override val shape: FlowShape[In, Out] = FlowShape(in, out)

  override def initialAttributes: Attributes =
    Attributes(Name("MapAsyncPartitionOrdered")) and SourceLocation.forLambda(f)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private val contextPropagation = pekko.stream.impl.ContextPropagation()

      private final class Contextual[T](context: AnyRef, val element: T) {
        private var suspended = false

        def suspend(): Unit =
          if (!suspended) {
            suspended = true
            contextPropagation.suspendContext()
          }

        def resume(): Unit =
          if (suspended) {
            suspended = false
            contextPropagation.resumeContext(context)
          }

      }

      private lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      private var partitionsInProgress: mutable.Set[Partition] = _
      private var buffer: mutable.Queue[(Partition, Contextual[Holder[In, Out]])] = _

      private val futureCB = getAsyncCallback[Holder[In, Out]](holder =>
        holder.out match {
          case Success(_) => pushNextIfPossible()
          case Failure(ex) =>
            holder.supervisionDirectiveFor(decider, ex) match {
              // fail fast as if supervision says so
              case Supervision.Stop => failStage(ex)
              case _                => pushNextIfPossible()
            }
        })

      override def preStart(): Unit = {
        partitionsInProgress = mutable.Set()
        buffer = mutable.Queue()
      }

      override def onPull(): Unit =
        pushNextIfPossible()

      override def onPush(): Unit = {
        try {
          val element = grab(in)
          val partition = extractPartition(element)

          val wrappedInput = new Contextual(
            contextPropagation.currentContext(),
            new Holder[In, Out](element, NotYetThere, futureCB))

          buffer.enqueue(partition -> wrappedInput)

          if (canStartNextElement(partition)) {
            processElement(partition, wrappedInput)
          } else {
            wrappedInput.suspend()
          }
        } catch {
          case NonFatal(ex) => if (decider(ex) == Supervision.Stop) failStage(ex)
        }

        pullIfNeeded()
      }

      override def onUpstreamFinish(): Unit =
        if (idle()) completeStage()

      private def processElement(partition: Partition, wrappedInput: Contextual[Holder[In, Out]]): Unit = {
        import wrappedInput.{ element => holder }
        val future = f(holder.in, partition)

        partitionsInProgress += partition

        future.value match {
          case None    => future.onComplete(holder)(ExecutionContexts.parasitic)
          case Some(v) =>
            // #20217 the future is already here, optimization: avoid scheduling it on the dispatcher and
            // run the logic directly on this thread
            holder.setOut(v)
            v match {
              // this optimization also requires us to stop the stage to fail fast if the decider says so:
              case Failure(ex) if holder.supervisionDirectiveFor(decider, ex) == Supervision.Stop => failStage(ex)
              case _                                                                              => pushNextIfPossible()
            }
        }
      }

      private val pushNextIfPossible: () => Unit =
        if (orderedOutput) pushNextIfPossibleOrdered _
        else pushNextIfPossibleUnordered _

      private def pushNextIfPossibleOrdered(): Unit =
        if (partitionsInProgress.isEmpty) {
          drainQueue()
          pullIfNeeded()
        } else {
          while (buffer.nonEmpty && !(buffer.front._2.element.out eq NotYetThere) && isAvailable(out)) {
            val (partition, wrappedInput) = buffer.dequeue()
            import wrappedInput.{ element => holder }
            partitionsInProgress -= partition

            holder.out match {
              case Success(elem) =>
                if (elem != null) {
                  push(out, elem)
                  pullIfNeeded()
                } else {
                  // elem is null
                  pullIfNeeded()
                }

              case Failure(NonFatal(ex)) =>
                holder.supervisionDirectiveFor(decider, ex) match {
                  // this could happen if we are looping in pushNextIfPossible and end up on a failed future before the
                  // onComplete callback has run
                  case Supervision.Stop =>
                    failStage(ex)
                  case _ =>
                  // try next element
                }
              case Failure(ex) =>
                // fatal exception in buffer, not sure that it can actually happen, but for good measure
                throw ex
            }
          }
          drainQueue()
        }

      private def pushNextIfPossibleUnordered(): Unit =
        if (partitionsInProgress.isEmpty) {
          drainQueue()
          pullIfNeeded()
        } else {
          buffer = buffer.filter { case (partition, wrappedInput) =>
            import wrappedInput.{ element => holder }

            if ((holder.out eq MapAsyncPartitioned.NotYetThere) || !isAvailable(out)) {
              true
            } else {
              partitionsInProgress -= partition

              holder.out match {
                case Success(elem) =>
                  if (elem != null) {
                    push(out, elem)
                  }

                case Failure(NonFatal(ex)) =>
                  holder.supervisionDirectiveFor(decider, ex) match {
                    // this could happen if we are looping in pushNextIfPossible and end up on a failed future before the
                    // onComplete callback has run
                    case Supervision.Stop =>
                      failStage(ex)
                    case _ =>
                    // try next element
                  }
                case Failure(ex) =>
                  // fatal exception in buffer, not sure that it can actually happen, but for good measure
                  throw ex
              }
              false
            }
          }
          pullIfNeeded()
          drainQueue()
        }

      private def drainQueue(): Unit = {
        buffer.foreach {
          case (partition, wrappedInput) =>
            if (canStartNextElement(partition)) {
              wrappedInput.resume()
              processElement(partition, wrappedInput)
            }
        }
      }

      private def pullIfNeeded(): Unit =
        if (isClosed(in) && idle()) completeStage()
        else if (buffer.size < parallelism && !hasBeenPulled(in)) tryPull(in)
      // else already pulled and waiting for next element

      private def idle(): Boolean =
        buffer.isEmpty

      private def canStartNextElement(partition: Partition): Boolean =
        !partitionsInProgress(partition) && partitionsInProgress.size < parallelism

      setHandlers(in, out, this)
    }
}
