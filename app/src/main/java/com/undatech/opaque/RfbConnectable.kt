package com.undatech.opaque

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Thrown when [RfbConnectable.connect] cannot be completed because the
 * feature itself is unavailable. Kept for source-compatibility with older
 * call sites; the real client below never throws it — it always attempts a
 * genuine RFB connection.
 */
class VncNotImplementedException(message: String) : UnsupportedOperationException(message)

/**
 * Real, hand-written RFB (Remote Framebuffer / VNC) client implemented in
 * pure Kotlin — no native library, no third-party AAR, works on every ABI
 * and every Android device the rest of the app supports (minSdk 26+).
 *
 * Supports:
 *  - RFB protocol versions 3.3 / 3.7 / 3.8 handshake negotiation.
 *  - Security types: None (1) and VNC Authentication (2, DES challenge/response).
 *  - A fixed client pixel format (32bpp, true-colour, little-endian, R/G/B
 *    shifts 16/8/0) requested via SetPixelFormat — this means the server's
 *    own native pixel format never has to be parsed/converted, which keeps
 *    the decoder simple and fast on every device.
 *  - Encodings: Raw (mandatory for every RFB-compliant server) and CopyRect.
 *    This intentionally trades a little bandwidth efficiency (no Tight/ZRLE
 *    compression) for maximum server compatibility — every VNC server that
 *    exists supports Raw, so connections never fail due to "no common
 *    encoding" the way they could with a Tight/Hextile-only client.
 *  - TCP keep-alive on the underlying socket so idle sessions are not
 *    silently dropped by carrier-grade NAT / aggressive OEM power management.
 *
 * Not supported in this version (documented limitation, not silently
 * dropped): VeNCrypt / TLS-wrapped RFB, ARD/RA2 (macOS Screen Sharing) and
 * other vendor security types, and File Transfer extensions. Plain RFB with
 * None/VNC-Auth security covers the overwhelming majority of real-world VNC
 * servers (TigerVNC, TightVNC, RealVNC Server in "VNC Password" mode,
 * x11vnc, Vino, UltraVNC).
 */
class RfbConnectable(
    private val connection: Connection,
    @Suppress("UNUSED_PARAMETER") private val context: Context,
) {

    companion object {
        private const val TAG = "RfbConnectable"
        private const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_READ_TIMEOUT_MS = 20_000

        /** RFB §7.2.2 — VNC Authentication key prep: reverse the bits of every byte. */
        private fun reverseBits(b: Byte): Byte {
            var v = b.toInt() and 0xFF
            var r = 0
            for (i in 0 until 8) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            return r.toByte()
        }

        /** Builds the 8-byte DES key bVNC/RFB servers expect from a plaintext password. */
        private fun vncAuthKey(password: String): ByteArray {
            val raw = password.toByteArray(Charsets.ISO_8859_1)
            val key = ByteArray(8)
            for (i in 0 until 8) {
                key[i] = if (i < raw.size) reverseBits(raw[i]) else 0
            }
            return key
        }

        private fun desEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            return cipher.doFinal(block)
        }
    }

    /** Last Bitmap received from the server. Becomes null again if the connection drops. */
    var framebuffer: Bitmap? = null
        private set

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val writeLock = Any()

    @Volatile private var running = false
    private var readerThread: Thread? = null

    private var fbWidth = 0
    private var fbHeight = 0

    /**
     * Performs the full RFB handshake synchronously (version → security →
     * auth → init) then starts a background thread that keeps pulling
     * FramebufferUpdate messages and applying them to [framebuffer].
     *
     * @throws AuthenticationException if the server rejects the password.
     * @throws java.io.IOException on any network / protocol failure.
     */
    fun connect() {
        val host = connection.address
        val port = if (connection.port > 0) connection.port else 5900
        val sock = Socket()
        socket = sock
        try {
            sock.tcpNoDelay = true
            // Keep idle RFB connections alive across carrier NAT timeouts and
            // OEM Doze-style network suspension (Xiaomi/Honor/Oppo/etc.) —
            // the OS sends periodic empty TCP keep-alive probes so the link
            // is not silently reclaimed while the user isn't touching the
            // screen.
            sock.keepAlive = true
            sock.connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS)
            sock.soTimeout = SOCKET_READ_TIMEOUT_MS

            val rawIn = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            val rawOut = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 64 * 1024))
            input = rawIn
            output = rawOut

            negotiateVersion(rawIn, rawOut)
            negotiateSecurity(rawIn, rawOut)
            clientAndServerInit(rawIn, rawOut)
            setPixelFormatAndEncodings(rawOut)

            framebuffer = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)

            // Initial full-screen, non-incremental request.
            sendFramebufferUpdateRequest(incremental = false, x = 0, y = 0, w = fbWidth, h = fbHeight)

            running = true
            readerThread = Thread({ readLoop() }, "rfb-reader").apply {
                isDaemon = true
                start()
            }
        } catch (e: AuthenticationException) {
            closeQuietly()
            throw e
        } catch (e: IOException) {
            closeQuietly()
            throw e
        } catch (e: Exception) {
            closeQuietly()
            throw IOException("VNC connection failed: ${e.message}", e)
        }
    }

    // ── Handshake ────────────────────────────────────────────────────────

    private fun negotiateVersion(input: DataInputStream, output: DataOutputStream) {
        val versionBytes = ByteArray(12)
        input.readFully(versionBytes)
        val versionStr = String(versionBytes, Charsets.US_ASCII)
        // Expected form: "RFB 003.0XX\n"
        val match = Regex("""RFB (\d{3})\.(\d{3})""").find(versionStr)
            ?: throw IOException("Not an RFB server (bad version greeting)")
        val serverMajor = match.groupValues[1].toInt()
        val serverMinor = match.groupValues[2].toInt()

        // We support 3.3 through 3.8. Reply with whichever is lower so we
        // never claim to support handshake semantics the server doesn't.
        negotiatedMinor = when {
            serverMajor < 3 -> throw IOException("Unsupported RFB protocol version: $versionStr")
            serverMinor >= 8 -> 8
            else -> serverMinor.coerceIn(3, 8)
        }
        val reply = "RFB 003.%03d\n".format(negotiatedMinor)
        output.write(reply.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private var negotiatedMinor = 8

    private fun negotiateSecurity(input: DataInputStream, output: DataOutputStream) {
        val chosenType: Int
        if (negotiatedMinor <= 3) {
            // RFB 3.3: server dictates a single security type directly.
            val type = input.readInt()
            if (type == 0) {
                throw IOException("Server refused connection: ${readFailureReason(input)}")
            }
            chosenType = type
        } else {
            // RFB 3.7+: server offers a list, client picks one.
            val count = input.readUnsignedByte()
            if (count == 0) {
                throw IOException("Server refused connection: ${readFailureReason(input)}")
            }
            val types = IntArray(count) { input.readUnsignedByte() }
            chosenType = when {
                types.contains(2) -> 2 // prefer VNC Authentication when offered
                types.contains(1) -> 1 // fall back to None
                else -> throw IOException(
                    "No supported VNC security type offered by server " +
                        "(server offered: ${types.joinToString()}); only None/VNC-Auth are supported"
                )
            }
            output.writeByte(chosenType)
            output.flush()
        }

        when (chosenType) {
            1 -> {
                // None. RFB 3.3/3.7 send no SecurityResult for type None;
                // 3.8 does — read it defensively without blocking forever.
                if (negotiatedMinor >= 8) readSecurityResult(input)
            }
            2 -> {
                val challenge = ByteArray(16)
                input.readFully(challenge)
                val key = vncAuthKey(connection.password)
                val response = ByteArray(16)
                desEncryptBlock(key, challenge.copyOfRange(0, 8)).copyInto(response, 0)
                desEncryptBlock(key, challenge.copyOfRange(8, 16)).copyInto(response, 8)
                output.write(response)
                output.flush()
                readSecurityResult(input)
            }
            else -> throw IOException("Unsupported VNC security type: $chosenType")
        }
    }

    private fun readSecurityResult(input: DataInputStream) {
        val result = input.readInt()
        if (result != 0) {
            val reason = if (negotiatedMinor >= 8) readFailureReason(input) else "authentication failed"
            throw AuthenticationException(reason)
        }
    }

    private fun readFailureReason(input: DataInputStream): String = try {
        val len = input.readInt()
        if (len in 0..4096) {
            val bytes = ByteArray(len)
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        } else {
            "unknown reason"
        }
    } catch (_: Exception) {
        "unknown reason"
    }

    private fun clientAndServerInit(input: DataInputStream, output: DataOutputStream) {
        // ClientInit: share the desktop with other viewers (1 = shared).
        output.writeByte(1)
        output.flush()

        // ServerInit
        fbWidth = input.readUnsignedShort()
        fbHeight = input.readUnsignedShort()
        // Server pixel format (16 bytes) — discarded; we override it below.
        val serverPixelFormat = ByteArray(16)
        input.readFully(serverPixelFormat)
        val nameLength = input.readInt()
        if (nameLength in 0..(1 shl 20)) {
            val nameBytes = ByteArray(nameLength)
            input.readFully(nameBytes)
        }
        if (fbWidth <= 0 || fbHeight <= 0 || fbWidth > 16384 || fbHeight > 16384) {
            throw IOException("Server reported an invalid framebuffer size: ${fbWidth}x$fbHeight")
        }
    }

    private fun setPixelFormatAndEncodings(output: DataOutputStream) {
        // SetPixelFormat (message-type 0): force 32bpp little-endian
        // true-colour with R/G/B shifts 16/8/0 so every received pixel can
        // be decoded with a fixed, branch-free formula regardless of what
        // the server's native format is.
        output.writeByte(0)
        output.write(byteArrayOf(0, 0, 0)) // padding
        output.writeByte(32) // bits-per-pixel
        output.writeByte(24) // depth
        output.writeByte(0)  // big-endian-flag = false
        output.writeByte(1)  // true-colour-flag = true
        output.writeShort(255) // red-max
        output.writeShort(255) // green-max
        output.writeShort(255) // blue-max
        output.writeByte(16) // red-shift
        output.writeByte(8)  // green-shift
        output.writeByte(0)  // blue-shift
        output.write(byteArrayOf(0, 0, 0)) // padding
        output.flush()

        // SetEncodings (message-type 2): Raw + CopyRect only — both are
        // mandatory-to-support for every compliant RFB server, so this list
        // is guaranteed to be accepted no matter what the server is.
        val encodings = intArrayOf(0, 1) // Raw, CopyRect
        output.writeByte(2)
        output.writeByte(0) // padding
        output.writeShort(encodings.size)
        encodings.forEach { output.writeInt(it) }
        output.flush()
    }

    // ── Ongoing message loop ────────────────────────────────────────────

    private fun readLoop() {
        val inp = input ?: return
        try {
            while (running) {
                val messageType = try {
                    inp.readUnsignedByte()
                } catch (e: SocketTimeoutException) {
                    // No update in the read-timeout window — request again
                    // (covers servers that don't push spontaneously) and keep
                    // the loop alive instead of treating this as fatal.
                    sendFramebufferUpdateRequest(incremental = true, x = 0, y = 0, w = fbWidth, h = fbHeight)
                    continue
                }
                when (messageType) {
                    0 -> handleFramebufferUpdate(inp)
                    1 -> skipSetColourMapEntries(inp)
                    2 -> { /* Bell — no payload, nothing to render */ }
                    3 -> skipServerCutText(inp)
                    else -> throw IOException("Unknown RFB server message type: $messageType")
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "RFB read loop ended: ${e.message}")
            }
        } finally {
            running = false
            framebuffer = null // signals VncClient's frame loop that the session is gone
            closeQuietly()
        }
    }

    private fun handleFramebufferUpdate(inp: DataInputStream) {
        inp.readUnsignedByte() // padding
        val numRects = inp.readUnsignedShort()
        val fb = framebuffer ?: return
        repeat(numRects) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            val encoding = inp.readInt()
            when (encoding) {
                0 -> applyRawRect(inp, fb, x, y, w, h)
                1 -> applyCopyRect(inp, fb, x, y, w, h)
                else -> throw IOException("Unsupported RFB encoding from server: $encoding")
            }
        }
        // Ask for the next incremental update — keeps the session live
        // without us having to poll on a fixed timer.
        sendFramebufferUpdateRequest(incremental = true, x = 0, y = 0, w = fbWidth, h = fbHeight)
    }

    private fun applyRawRect(inp: DataInputStream, fb: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val byteCount = w * h * 4
        val raw = ByteArray(byteCount)
        inp.readFully(raw)
        val pixels = IntArray(w * h)
        var p = 0
        var i = 0
        while (i < byteCount) {
            // Bytes on the wire are little-endian B,G,R,pad (per the fixed
            // SetPixelFormat we requested above).
            val b = raw[i].toInt() and 0xFF
            val g = raw[i + 1].toInt() and 0xFF
            val r = raw[i + 2].toInt() and 0xFF
            pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            p++
            i += 4
        }
        fb.setPixels(pixels, 0, w, x, y, w, h)
    }

    private fun applyCopyRect(inp: DataInputStream, fb: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val srcX = inp.readUnsignedShort()
        val srcY = inp.readUnsignedShort()
        if (w <= 0 || h <= 0) return
        val pixels = IntArray(w * h)
        fb.getPixels(pixels, 0, w, srcX, srcY, w, h)
        fb.setPixels(pixels, 0, w, x, y, w, h)
    }

    private fun skipSetColourMapEntries(inp: DataInputStream) {
        inp.readUnsignedByte() // padding
        inp.readUnsignedShort() // first-colour
        val n = inp.readUnsignedShort()
        inp.skipBytesFully(n * 6)
    }

    private fun skipServerCutText(inp: DataInputStream) {
        inp.skipBytesFully(3) // padding
        val len = inp.readInt()
        if (len > 0) inp.skipBytesFully(len)
    }

    private fun DataInputStream.skipBytesFully(count: Int) {
        var remaining = count
        val buf = ByteArray(minOf(remaining, 8192).coerceAtLeast(1))
        while (remaining > 0) {
            val n = read(buf, 0, minOf(remaining, buf.size))
            if (n < 0) throw EOFException()
            remaining -= n
        }
    }

    // ── Outgoing client messages ────────────────────────────────────────

    private fun sendFramebufferUpdateRequest(incremental: Boolean, x: Int, y: Int, w: Int, h: Int) {
        val out = output ?: return
        synchronized(writeLock) {
            try {
                out.writeByte(3)
                out.writeByte(if (incremental) 1 else 0)
                out.writeShort(x)
                out.writeShort(y)
                out.writeShort(w)
                out.writeShort(h)
                out.flush()
            } catch (_: IOException) { /* connection already closing */ }
        }
    }

    /** Sends a pointer (mouse) event. mask: 1=left, 2=middle, 4=right, 8/16=wheel. */
    fun sendPointerEvent(x: Int, y: Int, mask: Int) {
        val out = output ?: return
        synchronized(writeLock) {
            try {
                out.writeByte(5)
                out.writeByte(mask and 0xFF)
                out.writeShort(x.coerceIn(0, 65535))
                out.writeShort(y.coerceIn(0, 65535))
                out.flush()
            } catch (_: IOException) { /* connection already closing */ }
        }
    }

    /** Sends a keyboard event. keysym: X11 keysym code. down: true=press, false=release. */
    fun sendKeyEvent(keysym: Int, down: Boolean) {
        val out = output ?: return
        synchronized(writeLock) {
            try {
                out.writeByte(4)
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0) // padding
                out.writeInt(keysym)
                out.flush()
            } catch (_: IOException) { /* connection already closing */ }
        }
    }

    /** Closes the connection and releases all resources. */
    fun close() {
        running = false
        closeQuietly()
        readerThread?.let {
            if (it !== Thread.currentThread()) {
                try { it.join(1000) } catch (_: InterruptedException) {}
            }
        }
        framebuffer = null
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }
}
