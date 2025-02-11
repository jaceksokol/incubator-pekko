/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.remote.classic.transport

import scala.concurrent.{ Await, Future }

import scala.annotation.nowarn

import org.apache.pekko
import pekko.actor.{ Address, ExtendedActorSystem }
import pekko.remote.RemoteActorRefProvider
import pekko.remote.transport.{ AssociationRegistry => _, _ }
import pekko.remote.transport.AssociationHandle.{ ActorHandleEventListener, Disassociated, InboundPayload }
import pekko.remote.transport.TestTransport._
import pekko.remote.transport.Transport._
import pekko.testkit.{ DefaultTimeout, ImplicitSender, PekkoSpec }
import pekko.util.ByteString

@nowarn("msg=deprecated")
abstract class GenericTransportSpec(withAkkaProtocol: Boolean = false)
    extends PekkoSpec("""
         pekko.remote.artery.enabled = false
         pekko.actor.provider = remote
         # test is using Java serialization and not priority to rewrite
         pekko.actor.allow-java-serialization = on
         pekko.actor.warn-about-java-serializer-usage = off
      """)
    with DefaultTimeout
    with ImplicitSender {

  def transportName: String
  def schemeIdentifier: String

  val addressATest: Address = Address("test", "testsytemA", "testhostA", 4321)
  val addressBTest: Address = Address("test", "testsytemB", "testhostB", 5432)

  val addressA: Address = addressATest.copy(protocol = s"$schemeIdentifier.${addressATest.protocol}")
  val addressB: Address = addressBTest.copy(protocol = s"$schemeIdentifier.${addressATest.protocol}")
  val nonExistingAddress = Address(schemeIdentifier + ".test", "nosystem", "nohost", 0)

  def freshTransport(testTransport: TestTransport): Transport
  def wrapTransport(transport: Transport): Transport =
    if (withAkkaProtocol) {
      val provider = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider]
      new PekkoProtocolTransport(
        transport,
        system,
        new PekkoProtocolSettings(provider.remoteSettings.config),
        PekkoPduProtobufCodec$)
    } else transport

  def newTransportA(registry: AssociationRegistry): Transport =
    wrapTransport(freshTransport(new TestTransport(addressATest, registry)))
  def newTransportB(registry: AssociationRegistry): Transport =
    wrapTransport(freshTransport(new TestTransport(addressBTest, registry)))

  transportName must {

    "return an Address and promise when listen is called" in {
      val registry = new AssociationRegistry
      val transportA = newTransportA(registry)

      val result = Await.result(transportA.listen, timeout.duration)

      result._1 should ===(addressA)
      result._2 should not be null

      registry.logSnapshot.exists {
        case ListenAttempt(address) => address == addressATest
        case _                      => false
      } should ===(true)
    }

    "associate successfully with another transport of its kind" in {
      val registry = new AssociationRegistry
      val transportA = newTransportA(registry)
      val transportB = newTransportB(registry)

      // Must complete the returned promise to receive events
      Await.result(transportA.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))
      Await.result(transportB.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))

      awaitCond(registry.transportsReady(addressATest, addressBTest))

      transportA.associate(addressB)
      expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA =>
      }

      registry.logSnapshot.contains(AssociateAttempt(addressATest, addressBTest)) should ===(true)
      awaitCond(registry.existsAssociation(addressATest, addressBTest))
    }

    "fail to associate with nonexisting address" in {
      val registry = new AssociationRegistry
      val transportA = newTransportA(registry)

      Await.result(transportA.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))
      awaitCond(registry.transportsReady(addressATest))

      // TestTransport throws InvalidAssociationException when trying to associate with non-existing system
      intercept[InvalidAssociationException] {
        Await.result(transportA.associate(nonExistingAddress), timeout.duration)
      }
    }

    "successfully send PDUs" in {
      val registry = new AssociationRegistry
      val transportA = newTransportA(registry)
      val transportB = newTransportB(registry)

      Await.result(transportA.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))
      Await.result(transportB.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))

      awaitCond(registry.transportsReady(addressATest, addressBTest))

      val associate: Future[AssociationHandle] = transportA.associate(addressB)
      val handleB = expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA => handle
      }

      val handleA = Await.result(associate, timeout.duration)

      // Initialize handles
      handleA.readHandlerPromise.success(ActorHandleEventListener(self))
      handleB.readHandlerPromise.success(ActorHandleEventListener(self))

      val payload = ByteString("PDU")
      val pdu = if (withAkkaProtocol) PekkoPduProtobufCodec$.constructPayload(payload) else payload

      awaitCond(registry.existsAssociation(addressATest, addressBTest))

      handleA.write(payload)
      expectMsgPF(timeout.duration, "Expect InboundPayload from A") {
        case InboundPayload(p) if payload == p =>
      }

      registry.logSnapshot.exists {
        case WriteAttempt(`addressATest`, `addressBTest`, sentPdu) => sentPdu == pdu
        case _                                                     => false
      } should ===(true)
    }

    "successfully disassociate" in {
      val registry = new AssociationRegistry
      val transportA = newTransportA(registry)
      val transportB = newTransportB(registry)

      Await.result(transportA.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))
      Await.result(transportB.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))

      awaitCond(registry.transportsReady(addressATest, addressBTest))

      val associate: Future[AssociationHandle] = transportA.associate(addressB)
      val handleB: AssociationHandle = expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA => handle
      }

      val handleA = Await.result(associate, timeout.duration)

      // Initialize handles
      handleA.readHandlerPromise.success(ActorHandleEventListener(self))
      handleB.readHandlerPromise.success(ActorHandleEventListener(self))

      awaitCond(registry.existsAssociation(addressATest, addressBTest))

      handleA.disassociate("Test disassociation", log)

      expectMsgPF(timeout.duration) {
        case Disassociated(_) =>
      }

      awaitCond(!registry.existsAssociation(addressATest, addressBTest))

      awaitCond {
        registry.logSnapshot.exists {
          case DisassociateAttempt(`addressATest`, `addressBTest`) => true
          case _                                                   => false
        }
      }
    }

  }
}
