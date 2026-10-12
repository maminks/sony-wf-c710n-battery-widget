package app.budswidget

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

// Sony "V2" headphone protocol over Bluetooth Classic RFCOMM, as observed on a real WF-C710N (fw 1.0.5).
//
// Service UUID: 956C7B26-D49A-4BA8-B03F-B17D393CB6E2
// Frame:  0x3e <escaped(type seq len32be payload checksum)> 0x3c
//         escaping: 0x3c/0x3d/0x3e -> 0x3d, byte & 0xef;  checksum = sum of unescaped bytes & 0xff
// Every non-ACK frame must be ACKed (type 0x01, seq flipped); one command in flight at a time.
//
//   init           0c: 00 00                       -> 01 00 03 00 30 00 00 00
//   battery L/R    0c: 22 09                       -> 23 09 L chg R chg ..   (notify 25 09 ..; 0% = earbud not reachable)
//   battery case   0c: 22 0a                       -> 23 0a lvl chg ..       (only while the case is open)
//   ANC get        0c: 66 17                       -> 67 17 01 f1 f2 voice level   (notify 69 17 ..)
//   ANC set        0c: 68 17 01 f1 f2 voice level  -> ACK, then a 69 17 .. notification
//
//   f1=0 -> OFF;  f1=1 f2=0 -> noise cancelling;  f1=1 f2=1 -> ambient sound.
//   level 0..20; the earbuds only accept a new level while in ambient mode.

object SonyFrame {
    const val START = 0x3e
    const val END = 0x3c
    const val ESC = 0x3d

    const val T_ACK = 0x01
    const val T_CMD1 = 0x0c

    class Frame(val type: Int, val seq: Int, val payload: ByteArray)

    fun encode(type: Int, seq: Int, payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(type)
        body.write(seq)
        body.write(ByteBuffer.allocate(4).putInt(payload.size).array())
        body.write(payload)
        val raw = body.toByteArray()
        val sum = raw.sumOf { it.toInt() and 0xff } and 0xff

        val out = ByteArrayOutputStream()
        out.write(START)
        for (b in raw + sum.toByte()) {
            val v = b.toInt() and 0xff
            if (v == START || v == END || v == ESC) {
                out.write(ESC)
                out.write(v and 0xef)
            } else out.write(v)
        }
        out.write(END)
        return out.toByteArray()
    }

    fun read(inp: InputStream, onBad: (String) -> Unit): Frame? {
        while (true) {
            val buf = ByteArrayOutputStream()
            var started = false
            var esc = false
            var done = false
            while (!done) {
                val b = inp.read()
                if (b < 0) return null
                when {
                    b == START -> { buf.reset(); started = true; esc = false }
                    !started -> {}
                    b == END -> done = true
                    b == ESC -> esc = true
                    esc -> { buf.write(b or 0x10); esc = false }
                    else -> buf.write(b)
                }
            }
            val body = buf.toByteArray()
            if (body.size < 7) { onBad("short frame: ${body.hex()}"); continue }
            val len = ByteBuffer.wrap(body, 2, 4).int
            if (len < 0 || body.size != 6 + len + 1) { onBad("bad length: ${body.hex()}"); continue }
            var sum = 0
            for (i in 0 until 6 + len) sum += body[i].toInt() and 0xff
            if ((sum and 0xff) != (body[6 + len].toInt() and 0xff)) { onBad("bad checksum: ${body.hex()}"); continue }
            return Frame(body[0].toInt() and 0xff, body[1].toInt() and 0xff, body.copyOfRange(6, 6 + len))
        }
    }
}

data class SonyState(
    val connected: Boolean = false,
    val left: Int = -1, val leftChg: Boolean = false, val leftLast: Int = -1,
    val right: Int = -1, val rightChg: Boolean = false, val rightLast: Int = -1,
    val caseBat: Int = -1, val caseChg: Boolean = false, val caseLast: Int = -1,
    val mode: String = "?",      // OFF / NC / AMBIENT / ?
    val ambLevel: Int = -1,
    val voice: Int = 0
)

object SonyParse {
    fun battery(p: ByteArray, s: SonyState): SonyState? {
        if (p.size < 4) return null
        val id = p[0].toInt() and 0xff
        if (id != 0x23 && id != 0x25) return null
        fun v(i: Int) = p[i].toInt() and 0xff
        return when (p[1].toInt() and 0xff) {
            9, 1 -> if (p.size >= 6) {
                val l = v(2); val r = v(4)
                s.copy(
                    left = if (l > 0) l else -1, leftLast = if (l > 0) l else s.leftLast, leftChg = p[3].toInt() != 0,
                    right = if (r > 0) r else -1, rightLast = if (r > 0) r else s.rightLast, rightChg = p[5].toInt() != 0
                )
            } else null
            10, 2 -> {
                val c = v(2)
                s.copy(caseBat = if (c > 0) c else -1, caseLast = if (c > 0) c else s.caseLast, caseChg = p[3].toInt() != 0)
            }
            else -> null
        }
    }

    fun anc(p: ByteArray, s: SonyState): SonyState? {
        if (p.size < 7) return null
        val id = p[0].toInt() and 0xff
        if ((id != 0x67 && id != 0x69) || p[1].toInt() != 0x17) return null
        val enabled = p[3].toInt() != 0
        val ambient = p[4].toInt() != 0
        val mode = if (!enabled) "OFF" else if (ambient) "AMBIENT" else "NC"
        return s.copy(mode = mode, ambLevel = p[6].toInt() and 0xff, voice = p[5].toInt() and 0xff)
    }
}

object SonyCmd {
    fun ancSet(mode: String, level: Int, voice: Int): ByteArray {
        val f1 = if (mode == "OFF") 0 else 1
        val f2 = if (mode == "NC") 0 else 1
        return byteArrayOf(0x68, 0x17, 0x01, f1.toByte(), f2.toByte(), voice.toByte(), level.coerceIn(0, 20).toByte())
    }
}
