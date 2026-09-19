package com.animoratv

import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaLocalServer {

    private const val CHUNK = 6 * 1024 * 1024
    private const val MEGA_API = "https://g.api.mega.co.nz/cs"

    private var server: ServerSocket? = null

    private data class MegaFile(
        val storageUrl: String,
        val size: Long
    )

    fun start(
        handle: String,
        keyB64: String
    ): String {

        if (server == null || server!!.isClosed) {

            server = ServerSocket(
                0,
                50,
                java.net.InetAddress.getByName("127.0.0.1")
            )

            val localServer = server!!

            Thread {

                while (!localServer.isClosed) {

                    try {

                        val socket =
                            localServer.accept()

                        Thread {

                            handleClient(
                                socket,
                                handle,
                                keyB64
                            )

                        }.start()

                    } catch (_: Exception) {

                        break
                    }
                }

            }.apply {

                isDaemon = true
                name = "AnimoraTV-MegaServer"
                start()
            }
        }

        val port =
            server!!.localPort

        val localUrl =
            "http://127.0.0.1:$port/mega/$handle"

        println(
            "AnimoraTV: Mega local server -> $localUrl"
        )

        return localUrl
    }

    private fun handleClient(
        socket: Socket,
        handle: String,
        keyB64: String
    ) {

        socket.use { s ->

            try {

                s.soTimeout = 30000

                val input =
                    BufferedInputStream(
                        s.getInputStream()
                    )

                val output =
                    BufferedOutputStream(
                        s.getOutputStream()
                    )

                val request =
                    readRequest(input)

                println(
                    "AnimoraTV: Mega request:\n$request"
                )

                val range =
                    Regex(
                        "(?im)^Range:\\s*bytes=(\\d+)-(\\d*)"
                    )
                        .find(request)
                        ?.let { match ->

                            val start =
                                match.groupValues[1]
                                    .toLong()

                            val end =
                                match.groupValues[2]
                                    .takeIf {
                                        it.isNotBlank()
                                    }
                                    ?.toLong()

                            start to end
                        }

                val megaFile =
                    resolveMegaFile(handle)

                val start =
                    range?.first ?: 0L

                val requestedEnd =
                    range?.second

                val end =
                    requestedEnd
                        ?: minOf(
                            megaFile.size - 1,
                            start + CHUNK - 1
                        )

                if (
                    start < 0L ||
                    start >= megaFile.size ||
                    end < start
                ) {

                    writeResponse(
                        output,
                        "HTTP/1.1 416 Range Not Satisfiable",
                        mapOf(
                            "Content-Range" to
                                "bytes */${megaFile.size}",
                            "Content-Length" to
                                "0",
                            "Connection" to
                                "close"
                        ),
                        ByteArray(0)
                    )

                    return
                }

                val actualEnd =
                    minOf(
                        end,
                        megaFile.size - 1
                    )

                val alignedStart =
                    (start / 16L) * 16L

                val encrypted =
                    downloadRange(
                        megaFile.storageUrl,
                        alignedStart,
                        actualEnd
                    )

                val decrypted =
                    decryptRange(
                        encrypted,
                        keyB64,
                        alignedStart
                    )

                val offset =
                    (start - alignedStart)
                        .toInt()

                val length =
                    (actualEnd - start + 1)
                        .toInt()

                val body =
                    decrypted.copyOfRange(
                        offset,
                        offset + length
                    )

                val status =
                    if (range != null) {
                        "HTTP/1.1 206 Partial Content"
                    } else {
                        "HTTP/1.1 200 OK"
                    }

                writeResponse(
                    output,
                    status,
                    mapOf(
                        "Content-Type" to
                            "video/mp4",
                        "Accept-Ranges" to
                            "bytes",
                        "Content-Length" to
                            body.size.toString(),
                        "Content-Range" to
                            "bytes $start-$actualEnd/${megaFile.size}",
                        "Cache-Control" to
                            "no-store",
                        "Connection" to
                            "close"
                    ),
                    body
                )

                println(
                    "AnimoraTV: Mega served " +
                        "$start-$actualEnd " +
                        "(${body.size} bytes)"
                )

            } catch (e: Exception) {

                println(
                    "AnimoraTV: Mega server ERROR " +
                        "${e.javaClass.simpleName}: " +
                        e.message
                )

                try {

                    val output =
                        BufferedOutputStream(
                            s.getOutputStream()
                        )

                    writeResponse(
                        output,
                        "HTTP/1.1 500 Internal Server Error",
                        mapOf(
                            "Content-Type" to
                                "text/plain",
                            "Content-Length" to
                                "0",
                            "Connection" to
                                "close"
                        ),
                        ByteArray(0)
                    )

                } catch (_: Exception) {
                }
            }
        }
    }

    private fun readRequest(
        input: BufferedInputStream
    ): String {

        val buffer =
            ByteArray(8192)

        var total = 0

        while (total < buffer.size) {

            val value =
                input.read()

            if (value == -1) {
                break
            }

            buffer[total++] =
                value.toByte()

            if (
                total >= 4 &&
                buffer[total - 4] ==
                    '\r'.code.toByte() &&
                buffer[total - 3] ==
                    '\n'.code.toByte() &&
                buffer[total - 2] ==
                    '\r'.code.toByte() &&
                buffer[total - 1] ==
                    '\n'.code.toByte()
            ) {
                break
            }
        }

        return String(
            buffer,
            0,
            total,
            Charsets.ISO_8859_1
        )
    }

    private fun resolveMegaFile(
        handle: String
    ): MegaFile {

        val connection =
            URL(
                "$MEGA_API?id=0&n=$handle"
            ).openConnection()
                as HttpURLConnection

        connection.requestMethod =
            "POST"

        connection.setRequestProperty(
            "Content-Type",
            "application/json"
        )

        connection.doOutput = true

        val body =
            JSONArray()
                .put(
                    org.json.JSONObject()
                        .put("a", "g")
                        .put("g", 1)
                        .put("ssl", 1)
                        .put("p", handle)
                )
                .toString()

        connection.outputStream.use {
            it.write(
                body.toByteArray()
            )
        }

        val response =
            connection.inputStream
                .bufferedReader()
                .use {
                    it.readText()
                }

        val array =
            JSONArray(response)

        val item =
            array.getJSONObject(0)

        val storageUrl =
            item.getString("g")

        val size =
            item.getLong("s")

        println(
            "AnimoraTV: Mega resolved " +
                "size=$size"
        )

        return MegaFile(
            storageUrl,
            size
        )
    }

    private fun downloadRange(
        storageUrl: String,
        start: Long,
        end: Long
    ): ByteArray {

        val connection =
            URL(storageUrl)
                .openConnection()
                as HttpURLConnection

        connection.setRequestProperty(
            "Range",
            "bytes=$start-$end"
        )

        connection.connectTimeout =
            15000

        connection.readTimeout =
            30000

        return connection.inputStream
            .use {
                it.readBytes()
            }
    }

    private fun decryptRange(
        encrypted: ByteArray,
        keyB64: String,
        byteOffset: Long
    ): ByteArray {

        val rawKey =
            decodeBase64Url(keyB64)

        require(
            rawKey.size == 32
        ) {
            "Mega key inválida: " +
                "${rawKey.size} bytes"
        }

        val view =
            ByteBuffer.wrap(rawKey)

        val k =
            LongArray(8)

        for (i in 0 until 8) {

            k[i] =
                view.int.toLong() and
                    0xffffffffL
        }

        fun xor4(
            a: Long,
            b: Long
        ): ByteArray {

            val value =
                (a xor b) and
                    0xffffffffL

            return byteArrayOf(
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte()
            )
        }

        val aesKey =
            xor4(k[0], k[4]) +
            xor4(k[1], k[5]) +
            xor4(k[2], k[6]) +
            xor4(k[3], k[7])

        val nonce =
            xor4(k[4], 0L) +
            xor4(k[5], 0L)

        val blockIndex =
            byteOffset / 16L

        val iv =
            ByteBuffer
                .allocate(16)
                .put(nonce)
                .putLong(blockIndex)
                .array()

        val cipher =
            Cipher.getInstance(
                "AES/CTR/NoPadding"
            )

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(
                aesKey,
                "AES"
            ),
            IvParameterSpec(iv)
        )

        return cipher.doFinal(
            encrypted
        )
    }

    private fun decodeBase64Url(
        value: String
    ): ByteArray {

        val normalized =
            value
                .replace('-', '+')
                .replace('_', '/')
                .let {
                    it + "=".repeat(
                        (4 - it.length % 4) % 4
                    )
                }

        return java.util.Base64
            .getDecoder()
            .decode(normalized)
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: String,
        headers: Map<String, String>,
        body: ByteArray
    ) {

        val response =
            StringBuilder()

        response
            .append(status)
            .append("\r\n")

        for ((key, value) in headers) {

            response
                .append(key)
                .append(": ")
                .append(value)
                .append("\r\n")
        }

        response.append("\r\n")

        output.write(
            response
                .toString()
                .toByteArray(
                    Charsets.ISO_8859_1
                )
        )

        output.write(body)
        output.flush()
    }
}
