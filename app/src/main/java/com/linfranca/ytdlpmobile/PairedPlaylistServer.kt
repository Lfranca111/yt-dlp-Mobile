package com.linfranca.ytdlpmobile

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/** Serve a temporary HLS master over loopback so FFmpeg can forward HTTP headers to both renditions. */
internal class PairedPlaylistServer(master: String) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}/paired.m3u8"

    init {
        val body = master.toByteArray(Charsets.UTF_8)
        thread(isDaemon = true, name = "paired-hls-master") {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val input = socket.getInputStream().bufferedReader()
                        val firstLine = input.readLine().orEmpty()
                        var line = input.readLine()
                        while (line != null && line.isNotEmpty()) line = input.readLine()
                        val accepted = NativeMedia.httpPath(firstLine, true) == "/paired.m3u8"
                        val response = if (accepted) body else ByteArray(0)
                        val status = if (accepted) "200 OK" else "404 Not Found"
                        val headers = "HTTP/1.1 $status\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(headers.toByteArray(Charsets.US_ASCII))
                            write(response)
                            flush()
                        }
                    }
                } catch (_: SocketException) {
                    if (server.isClosed) break
                } catch (_: Exception) {
                    // One malformed client request must not end a recording.
                }
            }
        }
    }

    override fun close() { server.close() }
}
