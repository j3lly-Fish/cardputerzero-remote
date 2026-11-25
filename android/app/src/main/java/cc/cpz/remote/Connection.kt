package cc.cpz.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.zip.Inflater
import kotlin.concurrent.thread

/**
 * Link to cpz_remote.py on the CardputerZero over Wi-Fi (TCP) or Bluetooth
 * (RFCOMM): receives screen frames and sends key events. The target is an IP
 * address/hostname or a Bluetooth MAC address. See the protocol description
 * at the top of cpz_remote.py.
 */
class Connection(
    private val target: String,
    private val pin: String,
    private val listener: Listener,
    private val bt: BluetoothAdapter?,
) {
    interface Listener {
        fun onConnected(c: Connection)
        fun onFrame(c: Connection, frame: Bitmap)
        fun onClosed(c: Connection, error: String?)
    }

    companion object {
        const val PORT = 7878
        val BT_UUID: UUID = UUID.fromString("6c1f8b52-4a2e-4f0c-9d3a-c2b0e5a7f001")

        fun isBluetooth(target: String) = BluetoothAdapter.checkBluetoothAddress(target)
    }

    val viaBluetooth = isBluetooth(target)

    @Volatile private var link: Closeable? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var closed = false
    private val sender = Executors.newSingleThreadExecutor()

    // Frames are decoded into a ring of bitmaps so the UI never draws one that is being written.
    private val buffers = arrayOfNulls<Bitmap>(3)
    private var next = 0

    fun start() {
        thread(name = "cpz-rx", isDaemon = true) { run() }
    }

    fun sendKey(code: Int, down: Boolean) {
        if (closed) return
        try {
            sender.execute {
                val o = out ?: return@execute
                try {
                    o.writeByte('K'.code)
                    o.writeShort(code)
                    o.writeByte(if (down) 1 else 0)
                    o.flush()
                } catch (e: IOException) {
                    close()
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    fun close() {
        closed = true
        try {
            link?.close()
        } catch (_: IOException) {
        }
        sender.shutdown()
    }

    private fun run() {
        var error: String? = null
        try {
            val (rawIn, rawOut) = if (viaBluetooth) openBluetooth() else openTcp()
            val o = DataOutputStream(BufferedOutputStream(rawOut))
            val p = pin.toByteArray()
            o.write("CPZ1".toByteArray())
            o.writeByte(p.size)
            o.write(p)
            o.flush()

            val inp = DataInputStream(BufferedInputStream(rawIn, 1 shl 16))
            when (inp.readUnsignedByte()) {
                'O'.code -> Unit
                'P'.code -> throw IOException("wrong PIN")
                else -> throw IOException("not a cpz-remote device")
            }
            out = o
            listener.onConnected(this)
            readFrames(inp)
        } catch (e: Exception) {
            if (!closed) error = e.message ?: e.javaClass.simpleName
        } finally {
            close()
            listener.onClosed(this, error)
        }
    }

    private fun openTcp(): Pair<InputStream, OutputStream> {
        val s = Socket()
        link = s
        if (closed) throw IOException("cancelled")
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(target, PORT), 5000)
        s.soTimeout = 10_000 // the device pings every 2 s when the screen is idle
        return s.getInputStream() to s.getOutputStream()
    }

    @SuppressLint("MissingPermission") // the activity asks for BLUETOOTH_CONNECT first
    private fun openBluetooth(): Pair<InputStream, OutputStream> {
        val a = bt ?: throw IOException("this phone has no Bluetooth")
        if (!a.isEnabled) throw IOException("Bluetooth is off")
        val s = a.getRemoteDevice(target).createInsecureRfcommSocketToServiceRecord(BT_UUID)
        link = s
        if (closed) throw IOException("cancelled")
        try {
            a.cancelDiscovery() // discovery slows connecting down a lot
        } catch (_: SecurityException) {
        }
        try {
            s.connect()
        } catch (e: IOException) {
            throw IOException("Bluetooth connect failed. Is cpz-remote running and the device in range?")
        }
        return s.inputStream to s.outputStream
    }

    private fun readFrames(inp: DataInputStream) {
        val inflater = Inflater()
        var comp = ByteArray(0)
        var raw = ByteArray(0) // the whole screen; frames update a band of rows in it
        var format = 0L
        var pixels = IntArray(0)
        while (!closed) {
            when (inp.readUnsignedByte()) {
                'P'.code -> Unit // keepalive
                'F'.code -> {
                    val w = inp.readUnsignedShort()
                    val h = inp.readUnsignedShort()
                    val y0 = inp.readUnsignedShort()
                    val rows = inp.readUnsignedShort()
                    val bpp = inp.readUnsignedByte()
                    val flags = inp.readUnsignedByte()
                    val bgr = flags and 1 != 0
                    val len = inp.readInt()
                    if (w == 0 || h == 0 || w > 4096 || h > 4096 || y0 + rows > h ||
                        bpp !in intArrayOf(16, 24, 32) || len < 0 || len > (64 shl 20)
                    ) {
                        throw IOException("bad frame header")
                    }
                    if (comp.size < len) comp = ByteArray(len)
                    inp.readFully(comp, 0, len)

                    val rowBytes = w * (bpp / 8)
                    val rawLen = h * rowBytes
                    val fmt = (w.toLong() shl 32) or (h.toLong() shl 16) or (bpp.toLong() shl 8) or flags.toLong()
                    if (fmt != format) {
                        format = fmt
                        raw = ByteArray(rawLen)
                    }
                    inflater.reset()
                    inflater.setInput(comp, 0, len)
                    val start = y0 * rowBytes
                    val end = (y0 + rows) * rowBytes
                    var n = start
                    while (n < end) {
                        val k = inflater.inflate(raw, n, end - n)
                        if (k == 0) break
                        n += k
                    }
                    if (n < end) throw IOException("truncated frame")

                    val direct565 = bpp == 16 && !bgr
                    val cfg = if (direct565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                    var bmp = buffers[next]
                    if (bmp == null || bmp.width != w || bmp.height != h || bmp.config != cfg) {
                        bmp = Bitmap.createBitmap(w, h, cfg)
                        buffers[next] = bmp
                    }
                    next = (next + 1) % buffers.size

                    if (direct565 && bmp.rowBytes == w * 2) {
                        // Linux RGB565 framebuffers are little-endian, same as Android's RGB_565.
                        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(raw, 0, rawLen))
                    } else {
                        if (pixels.size < w * h) pixels = IntArray(w * h)
                        toArgb(raw, bpp, bgr, pixels, w * h)
                        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
                    }
                    listener.onFrame(this, bmp)
                }
                else -> throw IOException("protocol error")
            }
        }
    }

    private fun toArgb(src: ByteArray, bpp: Int, bgr: Boolean, dst: IntArray, count: Int) {
        val opaque = 0xFF shl 24
        when (bpp) {
            16 -> for (i in 0 until count) {
                val v = (src[2 * i].toInt() and 0xFF) or ((src[2 * i + 1].toInt() and 0xFF) shl 8)
                var r = (v shr 11) and 0x1F
                val g = (v shr 5) and 0x3F
                var b = v and 0x1F
                if (bgr) r = b.also { b = r }
                dst[i] = opaque or ((r * 255 / 31) shl 16) or ((g * 255 / 63) shl 8) or (b * 255 / 31)
            }
            24, 32 -> {
                val step = bpp / 8
                for (i in 0 until count) {
                    val o = i * step
                    val c0 = src[o].toInt() and 0xFF
                    val c1 = src[o + 1].toInt() and 0xFF
                    val c2 = src[o + 2].toInt() and 0xFF
                    // XRGB8888 is stored B,G,R in memory; red_offset 0 means R,G,B.
                    dst[i] = if (bgr) opaque or (c0 shl 16) or (c1 shl 8) or c2
                    else opaque or (c2 shl 16) or (c1 shl 8) or c0
                }
            }
            else -> throw IOException("unsupported framebuffer format: $bpp bpp")
        }
    }
}

/** Finds devices on the local network by UDP broadcast. Blocking: call off the UI thread. */
object Discovery {
    const val PORT = 7879

    data class Device(
        val name: String,
        val address: String,
        val bluetooth: Boolean = false,
        val btAddress: String? = null,
    )

    fun scan(timeoutMs: Long = 1500): List<Device> {
        val found = LinkedHashMap<String, Device>()
        val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { targets.add(it) }
            }
        } catch (_: Exception) {
        }
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 250
            val msg = "CPZ?".toByteArray()
            val buf = ByteArray(128)
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (t in targets) {
                        try {
                            s.send(DatagramPacket(msg, msg.size, t, PORT))
                        } catch (_: IOException) {
                        }
                    }
                    nextSend = System.currentTimeMillis() + 500
                }
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    s.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(pkt.data, 0, pkt.length)
                val addr = pkt.address?.hostAddress ?: continue
                if (text.startsWith("CPZ! ")) {
                    val parts = text.substring(5).trim().split('\t')
                    val bt = parts.drop(1).firstOrNull { it.startsWith("bt=") }?.substring(3)
                    found[addr] = Device(parts[0], addr, btAddress = bt)
                }
            }
        }
        return found.values.toList()
    }
}
