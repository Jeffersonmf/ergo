package org.ergoplatform

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import io.iohk.iodb.ByteArrayWrapper
import org.ergoplatform.ErgoSanity._
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.network.ErgoNodeViewSynchronizer
import org.ergoplatform.nodeView.WrappedUtxoState
import org.ergoplatform.nodeView.history.ErgoSyncInfoMessageSpec
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.settings.ErgoSettings
import org.scalacheck.Gen
import scorex.core.idToBytes
import scorex.core.network.{ConnectedPeer, Handshake, Outgoing}
import scorex.core.utils.NetworkTimeProvider
import scorex.core.app.{Version => HandshakeV}

class ErgoSanityUTXO extends ErgoSanity[UTXO_ST] {

  override val historyGen: Gen[HT] = generateHistory(verifyTransactions = true, StateType.Utxo, PoPoWBootstrap = false, -1)

  override val stateGen: Gen[WrappedUtxoState] = boxesHolderGen.map(WrappedUtxoState(_, createTempDir, emission, None))

  override def semanticallyValidModifier(state: UTXO_ST): PM = validFullBlock(None, state.asInstanceOf[WrappedUtxoState])

  override def semanticallyInvalidModifier(state: UTXO_ST): PM = invalidErgoFullBlockGen.sample.get

  override def totallyValidModifier(history: HT, state: UTXO_ST): PM = {
    val parentOpt = history.bestHeaderOpt
    validFullBlock(parentOpt, state.asInstanceOf[WrappedUtxoState]).header
  }

  override def totallyValidModifiers(history: HT, state: UTXO_ST, count: Int): Seq[PM] = {
    require(count >= 1)
    val headerOpt = history.bestHeaderOpt
    (0 until count).foldLeft((headerOpt, Seq.empty[PM])) { case (acc, _) =>
      val pm = validFullBlock(headerOpt, state.asInstanceOf[WrappedUtxoState])
      (Some(pm.header), acc._2 :+ pm)
    }._2.map(_.asInstanceOf[ErgoFullBlock].header)
  }

  override def nodeViewSynchronizer(implicit system: ActorSystem):
  (ActorRef, SI, PM, TX, ConnectedPeer, TestProbe, TestProbe, TestProbe, TestProbe) = {
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    val h = historyGen.sample.get
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    val s = stateGen.sample.get
    val v = h.openSurfaceIds().last
    s.store.update(ByteArrayWrapper(idToBytes(v)), Seq(), Seq())
    implicit val ec = system.dispatcher
    val settings = ErgoSettings.read(None)
    val tp = new NetworkTimeProvider(settings.scorexSettings.ntp)
    val ncProbe = TestProbe("NetworkControllerProbe")
    val vhProbe = TestProbe("ViewHolderProbe")
    val pchProbe = TestProbe("PeerHandlerProbe")
    val eventListener = TestProbe("EventListener")
    val ref = ErgoNodeViewSynchronizer(
      ncProbe.ref,
      vhProbe.ref,
      ErgoSyncInfoMessageSpec,
      settings.scorexSettings.network,
      tp
    )
    val m = totallyValidModifier(h, s)
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    val tx = validErgoTransactionGenTemplate(0, 0).sample.get._2
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    val p: ConnectedPeer = ConnectedPeer(inetSocketAddressGen.sample.get, pchProbe.ref, Outgoing,
      Handshake("", HandshakeV(0, 1, 2), "", None, Seq(), 0L))
    (ref, h.syncInfo, m, tx, p, pchProbe, ncProbe, vhProbe, eventListener)
  }
}
