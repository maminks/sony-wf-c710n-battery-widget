package app.budswidget

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// RFCOMM client: connects, keeps the ACK handshake, parses replies and notifications.

class SonyClient(
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
    private val onState: (SonyState) -> Unit,
    private var state: SonyState
) {
    private val uuid = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")
    private val outbox = ArrayDeque<Pair<Int, ByteArray>>()
    private var waitingAck = false
    private var nextSeq = 0
    private var out: OutputStream? = null
    private val sched = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket: BluetoothSocket? = null

    fun close() { try { socket?.close() } catch (_: IOException) {} }

    @SuppressLint("MissingPermission")
    fun run(): Boolean {
        val s = try {
            device.createRfcommSocketToServiceRecord(uuid).also { socket = it; it.connect() }
        } catch (e: IOException) {
            log("connect failed: ${e.message}")
            sched.shutdownNow(); close()
            return false
        }
        log("connected to ${device.name}")
        out = s.outputStream
        state = state.copy(connected = true)
        onState(state)

        enqueue(SonyFrame.T_CMD1, byteArrayOf(0x00, 0x00)) // init

        try {
            while (true) {
                val f = SonyFrame.read(s.inputStream) { log("!! $it") } ?: break
                handle(f)
            }
        } catch (e: IOException) {
            log("read error: ${e.message}")
        }
        log("disconnected")
        sched.shutdownNow()
        close()
        return true
    }

    fun setAnc(mode: String, level: Int) {
        val lv = level.coerceIn(0, 20)
        log("-> set $mode level=$lv")
        enqueue(SonyFrame.T_CMD1, SonyCmd.ancSet(mode, lv, state.voice))
    }

    private fun handle(f: SonyFrame.Frame) {
        if (f.type == SonyFrame.T_ACK) {
            synchronized(this) { nextSeq = f.seq; waitingAck = false }
            if (!sched.isShutdown) sched.schedule(Runnable { pump() }, 300, TimeUnit.MILLISECONDS)
            return
        }
        // ACK every non-ACK frame, seq flipped
        rawSend(SonyFrame.encode(SonyFrame.T_ACK, 1 - f.seq, ByteArray(0)))

        val id = f.payload.firstOrNull()?.toInt()?.and(0xff) ?: return
        when (id) {
            0x01 -> {
                log("init OK")
                enqueue(SonyFrame.T_CMD1, byteArrayOf(0x22, 0x09)) // battery L/R
                enqueue(SonyFrame.T_CMD1, byteArrayOf(0x22, 0x0a)) // battery case
                enqueue(SonyFrame.T_CMD1, byteArrayOf(0x66, 0x17)) // ANC/ambient state
            }
            0x23, 0x25 -> SonyParse.battery(f.payload, state)?.let {
                state = it; onState(it); log("battery: ${Fmt.batt(it)}")
            }
            0x67, 0x69 -> {
                log("<- %02x  %s".format(f.type, f.payload.hex()))
                SonyParse.anc(f.payload, state)?.let { state = it; onState(it); log("mode: ${Fmt.mode(it)}") }
                Store.cmdAnswered()
            }
            0xa5, 0xa9, 0x39 -> {} // playback metadata / multipoint info: noisy, ignored for now
            else -> log("<- %02x  %s".format(f.type, f.payload.hex()))
        }
    }

    private fun enqueue(type: Int, payload: ByteArray) {
        synchronized(this) { outbox.addLast(type to payload) }
        pump()
    }

    private fun pump() {
        val item: Pair<Int, ByteArray>
        val seq: Int
        synchronized(this) {
            if (waitingAck || outbox.isEmpty()) return
            item = outbox.removeFirst()
            seq = nextSeq
            waitingAck = true
        }
        rawSend(SonyFrame.encode(item.first, seq, item.second))
    }

    private fun rawSend(bytes: ByteArray) {
        try { synchronized(this) { out?.write(bytes); out?.flush() } }
        catch (e: IOException) { log("write error: ${e.message}") }
    }
}
