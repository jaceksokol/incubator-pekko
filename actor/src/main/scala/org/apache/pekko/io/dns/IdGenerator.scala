/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

package org.apache.pekko.io.dns

import org.apache.pekko.annotation.InternalApi
import org.apache.pekko.util.UniqueRandomShortProvider

import java.security.SecureRandom
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

/**
 * INTERNAL API
 *
 * These are called by an actor, however they are called inside composed futures so need to be
 * nextId needs to be thread safe.
 */
@InternalApi
private[pekko] trait IdGenerator {
  def nextId(): Short
}

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] object IdGenerator {
  sealed trait Policy

  object Policy {
    case object ThreadLocalRandom extends Policy
    case object SecureRandom extends Policy

    case object EnhancedDoubleHashRandom extends Policy
    val Default: Policy = ThreadLocalRandom

    def apply(name: String): Option[Policy] = name.toLowerCase match {
      case "thread-local-random"         => Some(ThreadLocalRandom)
      case "secure-random"               => Some(SecureRandom)
      case "enhanced-double-hash-random" => Some(EnhancedDoubleHashRandom)
      case _                             => Some(EnhancedDoubleHashRandom)
    }
  }

  def apply(policy: Policy): IdGenerator = policy match {
    case Policy.ThreadLocalRandom        => random(ThreadLocalRandom.current())
    case Policy.SecureRandom             => random(new SecureRandom())
    case Policy.EnhancedDoubleHashRandom => new UniqueRandomShortProvider with IdGenerator
  }

  def apply(): IdGenerator = random(ThreadLocalRandom.current())

  /**
   * @return a random sequence of ids for production
   */
  def random(rand: java.util.Random): IdGenerator = new IdGenerator {
    override def nextId(): Short = rand.nextInt(Short.MaxValue).toShort
  }

  /**
   * @return a predictable sequence of ids for tests
   */
  def sequence(): IdGenerator = new IdGenerator {
    val requestId: AtomicInteger = new AtomicInteger(0)

    @tailrec
    override final def nextId(): Short = {
      val oldId = requestId.get()
      val newId = (oldId + 1) % Short.MaxValue

      if (requestId.compareAndSet(oldId, newId.intValue())) {
        newId.toShort
      } else {
        nextId()
      }
    }
  }
}
