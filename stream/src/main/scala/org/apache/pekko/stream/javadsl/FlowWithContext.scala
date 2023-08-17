/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2014-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.stream.javadsl

import java.util.concurrent.CompletionStage

import scala.annotation.unchecked.uncheckedVariance

import org.apache.pekko
import pekko.annotation.ApiMayChange
import pekko.event.{ LogMarker, LoggingAdapter, MarkerLoggingAdapter }
import pekko.japi.{ function, Pair, Util }
import pekko.stream._
import pekko.util.ConstantFun
import pekko.util.ccompat.JavaConverters._
import pekko.util.FutureConverters._
import pekko.util.JavaDurationConverters._

object FlowWithContext {

  def create[In, Ctx](): FlowWithContext[In, Ctx, In, Ctx, pekko.NotUsed] =
    new FlowWithContext(Flow.create[Pair[In, Ctx]]())

  /**
   * Creates a FlowWithContext from a regular flow that operates on `Pair<data, context>` elements.
   */
  def fromPairs[In, CtxIn, Out, CtxOut, Mat](
      under: Flow[Pair[In, CtxIn], Pair[Out, CtxOut], Mat]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    new FlowWithContext(under)

}

/**
 * A flow that provides operations which automatically propagate the context of an element.
 * Only a subset of common operations from [[Flow]] is supported. As an escape hatch you can
 * use [[FlowWithContext.via]] to manually provide the context propagation for otherwise unsupported
 * operations.
 *
 * An "empty" flow can be created by calling `FlowWithContext[Ctx, T]`.
 */
final class FlowWithContext[In, CtxIn, Out, CtxOut, +Mat](
    delegate: javadsl.Flow[Pair[In, CtxIn], Pair[Out, CtxOut], Mat])
    extends GraphDelegate(delegate) {

  /**
   * Transform this flow by the regular flow. The given flow must support manual context propagation by
   * taking and producing tuples of (data, context).
   *
   *  It is up to the implementer to ensure the inner flow does not exhibit any behaviour that is not expected
   *  by the downstream elements, such as reordering. For more background on these requirements
   *  see https://pekko.apache.org/docs/pekko/current/stream/stream-context.html.
   *
   * This can be used as an escape hatch for operations that are not (yet) provided with automatic
   * context propagation here.
   *
   * @see [[pekko.stream.javadsl.Flow.via]]
   */
  def via[Out2, CtxOut2, Mat2](
      viaFlow: Graph[FlowShape[Pair[Out @uncheckedVariance, CtxOut @uncheckedVariance], Pair[Out2, CtxOut2]], Mat2])
      : FlowWithContext[In, CtxIn, Out2, CtxOut2, Mat] = {
    val under = asFlow().via(viaFlow)
    FlowWithContext.fromPairs(under)
  }

  /**
   * Transform this flow by the regular flow. The given flow works on the data portion of the stream and
   * ignores the context.
   *
   * The given flow *must* not re-order, drop or emit multiple elements for one incoming
   * element, the sequence of incoming contexts is re-combined with the outgoing
   * elements of the stream. If a flow not fulfilling this requirement is used the stream
   * will not fail but continue running in a corrupt state and re-combine incorrect pairs
   * of elements and contexts or deadlock.
   *
   * For more background on these requirements
   *  see https://pekko.apache.org/docs/pekko/current/stream/stream-context.html.
   */
  @ApiMayChange def unsafeDataVia[Out2, Mat2](
      viaFlow: Graph[FlowShape[Out @uncheckedVariance, Out2], Mat2]): FlowWithContext[In, CtxIn, Out2, CtxOut, Mat] =
    viaScala(_.unsafeDataVia(viaFlow))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.withAttributes]].
   *
   * @see [[pekko.stream.javadsl.Flow.withAttributes]]
   */
  override def withAttributes(attr: Attributes): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    viaScala(_.withAttributes(attr))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.mapError]].
   *
   * @see [[pekko.stream.javadsl.Flow.mapError]]
   */
  def mapError(pf: PartialFunction[Throwable, Throwable]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    viaScala(_.mapError(pf))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.mapMaterializedValue]].
   *
   * @see [[pekko.stream.javadsl.Flow.mapMaterializedValue]]
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat2] =
    new FlowWithContext(delegate.mapMaterializedValue[Mat2](f))

  /**
   * Creates a regular flow of pairs (data, context).
   */
  def asFlow(): Flow[Pair[In, CtxIn], Pair[Out, CtxOut], Mat] @uncheckedVariance =
    delegate

  // remaining operations in alphabetic order

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.collect]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[pekko.stream.javadsl.Flow.collect]]
   */
  def collect[Out2](pf: PartialFunction[Out, Out2]): FlowWithContext[In, CtxIn, Out2, CtxOut, Mat] =
    viaScala(_.collect(pf))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.filter]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[pekko.stream.javadsl.Flow.filter]]
   */
  def filter(p: function.Predicate[Out]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    viaScala(_.filter(p.test))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.filterNot]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[pekko.stream.javadsl.Flow.filterNot]]
   */
  def filterNot(p: function.Predicate[Out]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    viaScala(_.filterNot(p.test))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.grouped]].
   *
   * Each output group will be associated with a `Seq` of corresponding context elements.
   *
   * @see [[pekko.stream.javadsl.Flow.grouped]]
   */
  def grouped(n: Int): FlowWithContext[
    In, CtxIn,
    java.util.List[Out @uncheckedVariance],
    java.util.List[CtxOut @uncheckedVariance], Mat] =
    viaScala(_.grouped(n).map(_.asJava).mapContext(_.asJava))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.map]].
   *
   * @see [[pekko.stream.javadsl.Flow.map]]
   */
  def map[Out2](f: function.Function[Out, Out2]): FlowWithContext[In, CtxIn, Out2, CtxOut, Mat] =
    viaScala(_.map(f.apply))

  def mapAsync[Out2](
      parallelism: Int,
      f: function.Function[Out, CompletionStage[Out2]]): FlowWithContext[In, CtxIn, Out2, CtxOut, Mat] =
    viaScala(_.mapAsync[Out2](parallelism)(o => f.apply(o).asScala))

  /**
   * @since 1.1.0
   */
  def mapAsyncPartitioned[Out2, P](parallelism: Int,
      bufferSize: Int,
      extractPartition: function.Function[Out, P],
      f: function.Function2[Out, P, CompletionStage[Out2]]): FlowWithContext[In, CtxIn, Out2, CtxOut, Mat] = {
    viaScala(_.mapAsyncPartitioned(parallelism, bufferSize)(extractPartition(_))(f(_, _).asScala))
  }

  /**
   * @since 1.1.0
   */
  def mapAsyncPartitionedUnordered[Out2, P](parallelism: Int,
      bufferSize: Int,
      extractPartition: function.Function[Out, P],
      f: function.Function2[Out, P, CompletionStage[Out2]]): FlowWithContext[In, CtxIn, Out2, CtxOut, Mat] = {
    viaScala(_.mapAsyncPartitionedUnordered(parallelism, bufferSize)(extractPartition(_))(f(_, _).asScala))
  }

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.mapConcat]].
   *
   * The context of the input element will be associated with each of the output elements calculated from
   * this input element.
   *
   * Example:
   *
   * ```
   * def dup(element: String) = Seq(element, element)
   *
   * Input:
   *
   * ("a", 1)
   * ("b", 2)
   *
   * inputElements.mapConcat(dup)
   *
   * Output:
   *
   * ("a", 1)
   * ("a", 1)
   * ("b", 2)
   * ("b", 2)
   * ```
   *
   * @see [[pekko.stream.javadsl.Flow.mapConcat]]
   */
  def mapConcat[Out2](
      f: function.Function[Out, _ <: java.lang.Iterable[Out2]]): FlowWithContext[In, CtxIn, Out2, CtxOut, Mat] =
    viaScala(_.mapConcat(elem => Util.immutableSeq(f.apply(elem))))

  /**
   * Apply the given function to each context element (leaving the data elements unchanged).
   */
  def mapContext[CtxOut2](
      extractContext: function.Function[CtxOut, CtxOut2]): FlowWithContext[In, CtxIn, Out, CtxOut2, Mat] = {
    viaScala(_.mapContext(extractContext.apply))
  }

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.sliding]].
   *
   * Each output group will be associated with a `Seq` of corresponding context elements.
   *
   * @see [[pekko.stream.javadsl.Flow.sliding]]
   */
  def sliding(n: Int, step: Int = 1): FlowWithContext[
    In, CtxIn,
    java.util.List[Out @uncheckedVariance],
    java.util.List[CtxOut @uncheckedVariance], Mat] =
    viaScala(_.sliding(n, step).map(_.asJava).mapContext(_.asJava))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.log]].
   *
   * @see [[pekko.stream.javadsl.Flow.log]]
   */
  def log(
      name: String,
      extract: function.Function[Out, Any],
      log: LoggingAdapter): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    viaScala(_.log(name, e => extract.apply(e))(log))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.log]].
   *
   * @see [[pekko.stream.javadsl.Flow.log]]
   */
  def log(name: String, extract: function.Function[Out, Any]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    this.log(name, extract, null)

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.log]].
   *
   * @see [[pekko.stream.javadsl.Flow.log]]
   */
  def log(name: String, log: LoggingAdapter): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], log)

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.log]].
   *
   * @see [[pekko.stream.javadsl.Flow.log]]
   */
  def log(name: String): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], null)

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.logWithMarker]].
   *
   * @see [[pekko.stream.javadsl.Flow.logWithMarker]]
   */
  def logWithMarker(
      name: String,
      marker: function.Function2[Out, CtxOut, LogMarker],
      extract: function.Function[Out, Any],
      log: MarkerLoggingAdapter): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    viaScala(_.logWithMarker(name, (e, c) => marker.apply(e, c), e => extract.apply(e))(log))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.logWithMarker]].
   *
   * @see [[pekko.stream.javadsl.Flow.logWithMarker]]
   */
  def logWithMarker(
      name: String,
      marker: function.Function2[Out, CtxOut, LogMarker],
      extract: function.Function[Out, Any]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    this.logWithMarker(name, marker, extract, null)

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.logWithMarker]].
   *
   * @see [[pekko.stream.javadsl.Flow.logWithMarker]]
   */
  def logWithMarker(
      name: String,
      marker: function.Function2[Out, CtxOut, LogMarker],
      log: MarkerLoggingAdapter): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    this.logWithMarker(name, marker, ConstantFun.javaIdentityFunction[Out], log)

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.logWithMarker]].
   *
   * @see [[pekko.stream.javadsl.Flow.logWithMarker]]
   */
  def logWithMarker(
      name: String,
      marker: function.Function2[Out, CtxOut, LogMarker]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    this.logWithMarker(name, marker, ConstantFun.javaIdentityFunction[Out], null)

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.throttle]].
   *
   * @see [[pekko.stream.javadsl.Flow.throttle]]
   */
  def throttle(elements: Int, per: java.time.Duration): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    viaScala(_.throttle(elements, per.asScala))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.throttle]].
   *
   * @see [[pekko.stream.javadsl.Flow.throttle]]
   */
  def throttle(
      elements: Int,
      per: java.time.Duration,
      maximumBurst: Int,
      mode: ThrottleMode): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    viaScala(_.throttle(elements, per.asScala, maximumBurst, mode))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.throttle]].
   *
   * @see [[pekko.stream.javadsl.Flow.throttle]]
   */
  def throttle(
      cost: Int,
      per: java.time.Duration,
      costCalculation: function.Function[Out, Integer]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    viaScala(_.throttle(cost, per.asScala, costCalculation.apply))

  /**
   * Context-preserving variant of [[pekko.stream.javadsl.Flow.throttle]].
   *
   * @see [[pekko.stream.javadsl.Flow.throttle]]
   */
  def throttle(
      cost: Int,
      per: java.time.Duration,
      maximumBurst: Int,
      costCalculation: function.Function[Out, Integer],
      mode: ThrottleMode): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    viaScala(_.throttle(cost, per.asScala, maximumBurst, costCalculation.apply, mode))

  def asScala: scaladsl.FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    scaladsl.FlowWithContext.fromTuples(
      scaladsl
        .Flow[(In, CtxIn)]
        .map { case (i, c) => Pair(i, c) }
        .viaMat(delegate.asScala.map(_.toScala))(scaladsl.Keep.right))

  private[this] def viaScala[In2, CtxIn2, Out2, CtxOut2, Mat2](
      f: scaladsl.FlowWithContext[In, CtxIn, Out, CtxOut, Mat] => scaladsl.FlowWithContext[
        In2, CtxIn2, Out2, CtxOut2, Mat2]): FlowWithContext[In2, CtxIn2, Out2, CtxOut2, Mat2] =
    f(this.asScala).asJava

}
