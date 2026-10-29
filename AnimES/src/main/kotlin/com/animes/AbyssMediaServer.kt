package com.animes

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Loopback-only HTTP server. It caches 1 MiB upstream chunks, decrypts the first 64 KiB once, and supports player Range seeks.
 * The MP4 prefix algorithm is provisional until checked against known-good files from the live player.
 */
internal class AbyssMediaServer {
    private val random = SecureRandom()
    private val cache = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "animes-abyss-chunks").apply { mkdirs() }
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var source: AbyssSource? = null
    @Volatile private var token: String? = null
    @Volatile private var probedUrl: String? = null
    @Volatile private var probedLength: Long = -1L
    @Volatile private var layoutProbeUrl: String? = null
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()
        private set
    // Keep direct byte-range reads small so seeking into an uncached area does not wait
    // for a full MiB download. Sora uses its own metadata-sized segments below.
    private val chunkSize = 256 * 1024
    private val DEFAULT_SORA_SEGMENT_SIZE = 2L * 1024L * 1024L
    // Bound persistent disk usage; old cache blocks are disposable and can be fetched again.
    private val maxCacheBytes = 128L * 1024L * 1024L

    // Abyss's token path embeds the segment size advertised by fristDatas.partSize.
    // It can differ between videos; hardcoding 2 MiB produces valid-looking tokens for
    // the wrong endpoint and the CDN answers 404 for every segment.
    private fun soraSegmentSize(src: AbyssSource): Long =
        src.partSize.takeIf { it > 0L } ?: DEFAULT_SORA_SEGMENT_SIZE
    // Track segment prefetches so repeated player reads don't start duplicate workers.
    private val soraPrefetchScheduled = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    // Prevent a permanently failing CDN segment from triggering a tight retry loop.
    private val soraPrefetchRetryAfter = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Track the segment currently requested by the player and cancel obsolete Sora downloads on seeks.
    @Volatile private var soraWantedTarget: String? = null
    private val soraActiveConnections = java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection>()
    // Direct byte-range chunks are fetched ahead of playback to avoid a synchronous
    // network wait every 256 KiB. The set prevents duplicate workers for the same chunk.
    private val directPrefetchScheduled = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    @Synchronized fun register(next: AbyssSource): String {
        // A CDN host is reused across episodes; compare the full source metadata, not just the host URL.
        val sameContent = source == next
        if (!sameContent) {
            probedUrl = null
            probedLength = -1L
            layoutProbeUrl = null
            token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        } else if (token == null) {
            token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        }
        source = next
        if (socket == null || socket?.isClosed != false) {
            val s = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            socket = s
            Thread {
                while (!s.isClosed) {
                    try { val client = s.accept(); Thread { serve(client) }.start() } catch (_: Exception) { break }
                }
            }.apply { name = "abyss-http"; isDaemon = true }.start()
        }
        return "http://127.0.0.1:${socket!!.localPort}/s/${token}/video.mp4"
    }

    fun stop() { runCatching { socket?.close() }; socket = null; source = null }

    private fun serve(sock: Socket) = sock.use { client ->
        var headersSent = false
        val requestStartedAt = System.currentTimeMillis()
        var requestMethod = "unknown"
        var requestRange = "unknown"
        var requestStart = -1L
        var requestEnd = -1L
        var bytesWritten = 0L
        var knownTotal = -1L
        var failureStage = "request_setup"
        try {
            client.soTimeout = 30000
            val input = BufferedInputStream(client.getInputStream())
            val request = readHeader(input) ?: return
            lastActivityMs = System.currentTimeMillis()
            val line = request.first().split(' ')
            val method = line.getOrNull(0) ?: return
            requestMethod = method
            val path = line.getOrNull(1) ?: return
            val headers = request.drop(1).mapNotNull {
                val i = it.indexOf(':'); if (i <= 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }.toMap()
            val expected = "/s/${token}/video.mp4"
            if (method == "OPTIONS") {
                client.getOutputStream().write("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            if (path.substringBefore('?') != expected || (method != "GET" && method != "HEAD")) { reply(client, 404, "not found"); return }
            val src = source ?: run { reply(client, 503, "stream unavailable"); return }
            val total = totalLength(src)
            knownTotal = total
            val range = headers["range"]
            requestRange = range ?: "full"
            val bounds = parseRange(range, total) ?: (0L to total - 1)
            if (bounds.first < 0 || bounds.second < bounds.first || bounds.first >= total) {
                client.getOutputStream().write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            val (start, end) = bounds
            requestStart = start
            requestEnd = end
            val partial = range != null
            diagnostic("media_request_start", "$method range=$requestRange status=${if (partial) 206 else 200} start=$start end=$end total=$total")
            val out = BufferedOutputStream(client.getOutputStream())
            out.write(("HTTP/1.1 ${if (partial) 206 else 200} ${if (partial) "Partial Content" else "OK"}\r\n" +
                "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n" +
                (if (partial) "Content-Range: bytes $start-$end/$total\r\n" else "") +
                "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range\r\nAccess-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, X-Resolver-Error\r\nConnection: close\r\n\r\n").toByteArray())
            headersSent = true
            if (method == "HEAD") {
                out.flush()
                diagnostic("media_request", "$method range=$requestRange bytes=0 total=$total durationMs=${System.currentTimeMillis() - requestStartedAt}")
                return
            }
            var pos = start
            val buf = ByteArray(256 * 1024)
            while (pos <= end) {
                val n = min(buf.size.toLong(), end - pos + 1).toInt()
                failureStage = "upstream_read"
                val bytes = readDecrypted(src, pos, buf, n, total)
                if (bytes <= 0) throw IllegalStateException("unexpected end of upstream stream")
                failureStage = "client_write"
                out.write(buf, 0, bytes)
                bytesWritten += bytes
                pos += bytes
            }
            failureStage = "client_flush"
            out.flush()
            diagnostic("media_request", "$method range=$requestRange sent=$bytesWritten expected=${end - start + 1} total=$total durationMs=${System.currentTimeMillis() - requestStartedAt}")
        } catch (e: Throwable) {
            val detail = "$requestMethod range=$requestRange stage=$failureStage sent=$bytesWritten pos=$requestStart..$requestEnd total=$knownTotal durationMs=${System.currentTimeMillis() - requestStartedAt} ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            val clientClosed = (failureStage == "client_write" || failureStage == "client_flush") &&
                (e is java.net.SocketException || e.cause is java.net.SocketException)
            diagnostic(if (clientClosed) "media_client_disconnected" else "media_request_error", detail)
            if (!headersSent) runCatching { reply(client, 503, "Abyss upstream unavailable") }
        }
    }

    private fun diagnostic(stage: String, detail: String) {
        println("AnimES: Abyss $stage: $detail")
    }

    private fun readDecrypted(src: AbyssSource, offset: Long, out: ByteArray, len: Int, total: Long): Int {
        if (offset >= total) return -1
        // Sora segment boundaries come from Abyss metadata (partSize), not our local read buffer size.
        val segmentSize = if (src.segmented) soraSegmentSize(src) else chunkSize.toLong()
        val chunk = (offset / segmentSize).toInt()
        // Include the actual upstream segment size in the cache key.
        val file = File(cache, md5(src.url + "|" + src.filename) + "-${segmentSize}-$chunk.bin")
        if (src.segmented) {
            // Prioritize the newly requested segment and stop downloads for obsolete positions.
            prioritizeSoraTarget(file)
            // Do not make the player wait for an entire 4–10 MiB Sora segment.
            // Start/continue the segment download in the background and serve bytes
            // from its growing .part file as soon as they arrive.
            if (!file.exists()) scheduleSoraPrefetch(src, chunk, total)
            // Avoid competing with the requested segment during startup and seeking.
            // The next segment is scheduled once this segment has made substantial progress,
            // or immediately after it is already cached (below), preserving ahead-of-playback buffering.
            val part = File(file.parentFile, file.name + ".part")
            val inChunk = (offset % segmentSize).toInt()
            val deadline = System.currentTimeMillis() + 90_000L
            while (true) {
                // A failed prefetch may leave no committed .bin file. Re-enter the scheduler
                // so a transient late-segment failure can recover instead of waiting until timeout.
                if (!file.exists()) scheduleSoraPrefetch(src, chunk, total)
                if (file.exists()) {
                    val available = file.length() - inChunk
                    val count = min(len.toLong(), available).toInt()
                    if (count > 0) {
                        java.io.RandomAccessFile(file, "r").use { it.seek(inChunk.toLong()); it.readFully(out, 0, count) }
                        markCacheAccess(file)
                        break
                    }
                }
                if (part.exists()) {
                    // Start the next segment near the end of the current download to
                    // preserve the established Sora rolling buffer. If the player seeks,
                    // the requested segment is still scheduled independently above.
                    if (part.length() >= (segmentSize * 3L / 4L)) {
                        scheduleSoraPrefetch(src, chunk + 1, total, lookahead = true)
                    }
                    val available = part.length() - inChunk
                    // Never expose the MP4 header until enough bytes exist to validate it.
                    val prefixReady = chunk != 0 || part.length() >= 65536L
                    // Sora seeks can land deep inside a segment; return any available bytes
                    // immediately so the player does not wait for an artificial batch threshold.
                    if (available > 0L && prefixReady) {
                        try {
                            if (chunk == 0) {
                                val prefix = ByteArray(8)
                                java.io.RandomAccessFile(part, "r").use { it.readFully(prefix) }
                                if (String(prefix, 4, 4, Charsets.US_ASCII) != "ftyp") {
                                    throw IllegalStateException("MP4 prefix check failed while streaming Sora segment")
                                }
                            }
                            val count = min(len.toLong(), available).toInt()
                            java.io.RandomAccessFile(part, "r").use { it.seek(inChunk.toLong()); it.readFully(out, 0, count) }
                            return count
                        } catch (_: java.io.FileNotFoundException) {
                            // The downloader may atomically rename .part to .bin between exists() and open().
                            // Re-enter the loop and read the completed cache file instead of failing the player.
                            continue
                        } catch (_: java.io.EOFException) {
                            // The writer may still be extending the file after length() was sampled.
                            continue
                        }
                    }
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw IllegalStateException("Timed out waiting for Sora segment bytes index=$chunk offset=$inChunk")
                }
                Thread.sleep(25L)
            }
            // Keep exactly one segment ahead. Fetching two complete Sora segments
            // concurrently can compete with the segment requested after a seek and
            // wastes bandwidth/cache on segments the player may never reach.
            scheduleSoraPrefetch(src, chunk + 1, total, lookahead = true)
            return min(len, (file.length() - inChunk).toInt().coerceAtLeast(0)).also { count ->
                if (count <= 0) throw IllegalStateException("Sora segment cache has no bytes index=$chunk")
                java.io.RandomAccessFile(file, "r").use { it.seek(inChunk.toLong()); it.readFully(out, 0, count) }
                markCacheAccess(file)
            }
        }

        val inChunk = (offset % segmentSize).toInt()
        if (!file.exists() && chunk > 0) {
            // For direct URLs, do not make the player wait for the complete 256 KiB
            // upstream request after a seek. Fetch in the background and stream from
            // the growing .part file at the requested offset, as the Sora path does.
            scheduleDirectPrefetch(src, chunk, total)
            val part = File(file.parentFile, file.name + ".part")
            val deadline = System.currentTimeMillis() + 90_000L
            while (true) {
                if (file.exists()) {
                    val available = file.length() - inChunk
                    val count = min(len.toLong(), available).toInt()
                    if (count > 0) {
                        java.io.RandomAccessFile(file, "r").use { it.seek(inChunk.toLong()); it.readFully(out, 0, count) }
                        markCacheAccess(file)
                        scheduleDirectPrefetch(src, chunk + 1, total)
                        return count
                    }
                }
                if (part.exists()) {
                    val available = part.length() - inChunk
                    // Batch small increments from the growing file instead of returning a few bytes
                    // on every poll; the player still starts as soon as a small block is available.
                    val minBatch = min(16L * 1024L, segmentSize - inChunk)
                    if (available >= minBatch) {
                        try {
                            val count = min(len.toLong(), available).toInt()
                            java.io.RandomAccessFile(part, "r").use { it.seek(inChunk.toLong()); it.readFully(out, 0, count) }
                            // Start the next chunk as soon as the current one is almost ready.
                            if (part.length() >= segmentSize * 3L / 4L) {
                                scheduleDirectPrefetch(src, chunk + 1, total)
                            }
                            return count
                        } catch (_: java.io.FileNotFoundException) {
                            // The downloader may have committed .part as .bin between checks.
                            continue
                        } catch (_: java.io.EOFException) {
                            // The upstream writer may still be extending the temporary file.
                            continue
                        }
                    }
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw IllegalStateException("Timed out waiting for direct chunk bytes index=$chunk offset=$inChunk")
                }
                Thread.sleep(20L)
            }
        }
        // Keep the first direct chunk synchronous: its encrypted prefix must be decrypted
        // and validated before any bytes are exposed to ExoPlayer.
        if (!file.exists()) synchronized(file.absolutePath.intern()) {
            if (!file.exists()) {
                var failure: Throwable? = null
                for (attempt in 0..2) {
                    try {
                        fetchChunk(src, chunk, file, total)
                        failure = null
                        break
                    } catch (e: Exception) {
                        failure = e
                        diagnostic("upstream_chunk_retry", "chunk=$chunk attempt=${attempt + 1}/3 ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                        if (attempt < 2) Thread.sleep(400L * (attempt + 1))
                    }
                }
                if (failure != null) throw failure
            }
        }
        val count = min(len, (file.length() - inChunk).toInt().coerceAtLeast(0))
        if (count <= 0) return -1
        java.io.RandomAccessFile(file, "r").use { it.seek(inChunk.toLong()); it.readFully(out, 0, count) }
        markCacheAccess(file)
        scheduleDirectPrefetch(src, chunk + 1, total)
        return count
    }


    /**
     * Download the next direct byte-range chunk in the background. Use the same per-file
     * monitor as foreground reads so a seek and a prefetch cannot corrupt the same .part file.
     */
    private fun scheduleDirectPrefetch(src: AbyssSource, index: Int, total: Long) {
        val segmentSize = chunkSize.toLong()
        if (index < 0 || index.toLong() * segmentSize >= total) return
        val target = File(cache, md5(src.url + "|" + src.filename) + "-${segmentSize}-${index}.bin")
        if (target.exists() || !directPrefetchScheduled.add(target.absolutePath)) return
        Thread({
            try {
                synchronized(target.absolutePath.intern()) {
                    if (target.exists()) return@synchronized
                    var failure: Throwable? = null
                    for (attempt in 0..2) {
                        try {
                            fetchChunk(src, index, target, total)
                            failure = null
                            break
                        } catch (e: Exception) {
                            failure = e
                            diagnostic("upstream_direct_prefetch_retry", "chunk=$index attempt=${attempt + 1}/3 ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                            if (attempt < 2) Thread.sleep(250L * (attempt + 1))
                        }
                    }
                    if (failure != null) {
                        File(target.parentFile, target.name + ".part").delete()
                        diagnostic("upstream_direct_prefetch_failed", "chunk=$index ${failure.javaClass.simpleName}: ${failure.message ?: "no message"}")
                    } else {
                        diagnostic("upstream_direct_prefetch_ok", "chunk=$index")
                    }
                }
            } catch (e: Exception) {
                diagnostic("upstream_direct_prefetch_error", "chunk=$index ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            } finally {
                directPrefetchScheduled.remove(target.absolutePath)
            }
        }, "abyss-direct-prefetch-$index").apply { isDaemon = true; start() }
    }

    /**
     * Download one Sora segment ahead in the background. Playback reads are synchronous,
     * so preparing the next 2 MiB while the current one is consumed hides intermittent CDN stalls.
     */
    private fun prioritizeSoraTarget(target: File) {
        val wanted = target.absolutePath
        if (soraWantedTarget == wanted) return
        soraWantedTarget = wanted
        soraActiveConnections.entries.toList().forEach { (path, connection) ->
            if (path != wanted) runCatching { connection.disconnect() }
        }
    }

    private fun scheduleSoraPrefetch(src: AbyssSource, index: Int, total: Long, lookahead: Boolean = false) {
        val segmentSize = soraSegmentSize(src)
        if (index < 0 || index.toLong() * segmentSize >= total) return
        val target = File(cache, md5(src.url + "|" + src.filename) + "-${segmentSize}-${index}.bin")
        if (target.exists()) return
        val now = System.currentTimeMillis()
        if (now < (soraPrefetchRetryAfter[target.absolutePath] ?: 0L)) return
        if (!soraPrefetchScheduled.add(target.absolutePath)) return
        val requestedAtSchedule = soraWantedTarget
        Thread({
            try {
                synchronized(target.absolutePath.intern()) {
                    if (target.exists()) return@synchronized
                    var failure: Throwable? = null
                    for (attempt in 0..2) {
                        val stillRelevant = if (lookahead) {
                            soraWantedTarget == requestedAtSchedule || soraWantedTarget == target.absolutePath
                        } else {
                            soraWantedTarget == target.absolutePath
                        }
                        if (!stillRelevant) break
                        try {
                            fetchSoraSegment(src, index, target, total)
                            failure = null
                            break
                        } catch (e: Exception) {
                            failure = e
                            diagnostic("upstream_prefetch_retry", "index=$index attempt=${attempt + 1}/3 ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                            if (attempt < 2) Thread.sleep(300L * (attempt + 1))
                        }
                    }
                    if (failure != null) {
                        diagnostic("upstream_prefetch_failed", "index=$index ${failure.javaClass.simpleName}: ${failure.message ?: "no message"}")
                        // Do not leave a partial segment looking like usable buffered data.
                        File(target.parentFile, target.name + ".part").delete()
                    } else {
                        diagnostic("upstream_prefetch_ok", "index=$index")
                    }
                }
            } catch (e: Exception) {
                diagnostic("upstream_prefetch_error", "index=$index ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            } finally {
                // Retry after a short cooldown, not on every polling iteration.
                if (target.exists()) soraPrefetchRetryAfter.remove(target.absolutePath)
                else soraPrefetchRetryAfter[target.absolutePath] = System.currentTimeMillis() + 1500L
                soraPrefetchScheduled.remove(target.absolutePath)
            }
        }, "abyss-sora-prefetch-$index").apply { isDaemon = true; start() }
    }

    private fun fetchChunk(src: AbyssSource, index: Int, target: File, total: Long) {
        if (src.segmented) {
            fetchSoraSegment(src, index, target, total)
            return
        }
        val started = System.currentTimeMillis()
        val start = index.toLong() * chunkSize
        val end = min(total - 1, start + chunkSize - 1)
        val expected = end - start + 1
        val conn = URL(src.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 12000; conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
        // Legacy direct byte-range sources must keep the originating player referer.
        conn.setRequestProperty("Referer", src.referer.ifBlank { "https://player.abyssplayer.com/" })
        conn.setRequestProperty("Origin", "https://player.abyssplayer.com")
        conn.setRequestProperty("Range", "bytes=$start-$end")
        diagnostic("upstream_chunk_start", "chunk=$index range=$start-$end expected=$expected")
        var code = -1
        var received = 0L
        try {
            code = conn.responseCode
            val contentRange = conn.getHeaderField("Content-Range")
            if (code != 206 && !(code == 200 && start == 0L)) {
                throw IllegalStateException("upstream HTTP $code range=$start-$end contentRange=${contentRange ?: "none"}")
            }
            if (code == 206) {
                val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
                    .matchEntire(contentRange?.trim() ?: "")
                    ?: throw IllegalStateException("invalid upstream Content-Range")
                val actualStart = match.groupValues[1].toLong()
                val actualEnd = match.groupValues[2].toLong()
                if (actualStart != start || actualEnd < actualStart || actualEnd > end) {
                    throw IllegalStateException("upstream range mismatch requested=$start-$end got=$actualStart-$actualEnd")
                }
            }
            val temp = File(target.parentFile, target.name + ".part")
            conn.inputStream.use { input -> temp.outputStream().use { output ->
                var left = expected
                val buf = ByteArray(16384)
                while (left > 0) {
                    val n = input.read(buf, 0, min(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    output.write(buf, 0, n); left -= n; received += n
                }
                if (left > 0) throw IllegalStateException("upstream truncated chunk expected=$expected received=$received")
            } }
            if (start == 0L) {
                val bytes = temp.readBytes()
                val prefixLength = min(65536, bytes.size)
                val encryptedMarker = if (bytes.size >= 8) String(bytes, 4, 4, Charsets.US_ASCII) else "short"
                if (prefixLength >= 8) {
                    decryptPrefix(bytes, prefixLength, src.filename)
                    val marker = String(bytes, 4, 4, Charsets.US_ASCII)
                    val ftypSize = if (bytes.size >= 4) readUInt32(bytes, 0) else -1L
                    val nextType = if (ftypSize >= 8 && ftypSize + 8 <= bytes.size) String(bytes, ftypSize.toInt() + 4, 4, Charsets.US_ASCII) else "outside-prefix"
                    val nextSize = if (ftypSize >= 8 && ftypSize + 8 <= bytes.size) readUInt32(bytes, ftypSize.toInt()) else -1L
                    diagnostic("upstream_prefix_check", "chunk=$index bytes=$prefixLength markerBefore=$encryptedMarker markerAfter=$marker ftypSize=$ftypSize next=$nextType nextSize=$nextSize")
                    if (marker != "ftyp") throw IllegalStateException("MP4 prefix check failed marker=$marker")
                    temp.writeBytes(bytes)
                } else throw IllegalStateException("upstream first chunk too short bytes=${bytes.size}")
            }
            if (!temp.renameTo(target)) throw IllegalStateException("could not commit cache chunk")
            trimCache(target)
            diagnostic("upstream_chunk_ok", "chunk=$index status=$code received=$received contentRange=${contentRange ?: "none"} durationMs=${System.currentTimeMillis() - started}")
        } catch (e: Throwable) {
            diagnostic("upstream_chunk_error", "chunk=$index status=$code range=$start-$end received=$received durationMs=${System.currentTimeMillis() - started} ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            throw e
        } finally { conn.disconnect() }
    }

    /**
     * Abyss split MP4 sources are not byte-range URLs. Each piece is requested through
     * /sora/{totalSize}/{double-base64(AES-CTR("/mp4/{md5_id}/{res_id}/{totalSize}/2097152/{index}"))}.
     */
    private fun fetchSoraSegment(src: AbyssSource, index: Int, target: File, total: Long) {
        val started = System.currentTimeMillis()
        val segmentBytes = soraSegmentSize(src)
        val start = index.toLong() * segmentBytes
        if (start >= total) throw IllegalStateException("segment index outside source size index=$index total=$total")
        val expected = min(segmentBytes, total - start)
        val plainPath = "/mp4/${src.md5Id}/${src.resId}/${src.expectedSize}/${segmentBytes}/${index}"
        val keyHex = AbyssCrypto.md5NumericDigits(src.expectedSize)
        val key = keyHex.toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
        val encrypted = cipher.doFinal(plainPath.toByteArray(Charsets.UTF_8))
        val first = Base64.encodeToString(encrypted, Base64.NO_WRAP).replace("=", "")
        val token = Base64.encodeToString(first.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).replace("=", "")
        // The token is standard Base64, not URL-safe Base64. Escape path-reserved characters
        // so '+' and '/' remain inside the single token path segment.
        val pathToken = token.replace("+", "%2B").replace("/", "%2F")
        val endpoint = "${src.url.trimEnd('/')}/sora/${src.expectedSize}/$pathToken"
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        soraActiveConnections[target.absolutePath] = conn
        // Fail a stalled segment sooner so the existing retry loop can try again
        // before Kino's playback buffer is exhausted.
        conn.connectTimeout = 10000
        // Normal Sora segments usually arrive quickly. A 60s read timeout delayed retries on stalled late-file segments;
        // use 20s so the existing retry loop can recover without leaving a seek blocked for a full minute.
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
        // Match the reference Sora downloader's required CDN referer.
        conn.setRequestProperty("Referer", "https://abysscdn.com/")
        diagnostic("upstream_segment_start", "index=$index expected=$expected total=$total tokenSlash=${token.count { it == '/' }} tokenPlus=${token.count { it == '+' }} endpoint=${endpoint.substringBefore("/sora/")}/sora/…")
        var code = -1
        var received = 0L
        try {
            code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("Abyss segment HTTP $code index=$index")
            val temp = File(target.parentFile, target.name + ".part")
            conn.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        received += n
                        if (received > segmentBytes + 65536L) throw IllegalStateException("Abyss segment too large index=$index bytes=$received")
                    }
                }
            }
            if (received <= 0L) throw IllegalStateException("Abyss returned empty segment index=$index")
            if (index < ((total + segmentBytes - 1) / segmentBytes) - 1 && received != segmentBytes) {
                throw IllegalStateException("Abyss segment length mismatch index=$index expected=$segmentBytes actual=$received")
            }
            if (index == 0) {
                val bytes = temp.readBytes()
                val prefixLength = min(65536, bytes.size)
                if (prefixLength < 8) throw IllegalStateException("Abyss first segment too short bytes=${bytes.size}")
                // Sora segment bodies are already MP4 bytes. The reference downloader
                // concatenates them verbatim; AES-CTR is only used to construct the URL token.
                // Decrypting this prefix again corrupts the MP4 header.
                val marker = String(bytes, 4, 4, Charsets.US_ASCII)
                diagnostic("upstream_prefix_check", "transport=sora index=0 bytes=$prefixLength markerRaw=$marker")
                if (marker != "ftyp") throw IllegalStateException("MP4 prefix check failed rawMarker=$marker")
            }
            if (!temp.renameTo(target)) throw IllegalStateException("could not commit Abyss segment cache")
            trimCache(target)
            diagnostic("upstream_segment_ok", "index=$index status=$code received=$received expected=$expected durationMs=${System.currentTimeMillis() - started}")
        } catch (e: Throwable) {
            diagnostic("upstream_segment_error", "index=$index status=$code received=$received durationMs=${System.currentTimeMillis() - started} ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            throw e
        } finally {
            soraActiveConnections.remove(target.absolutePath, conn)
            conn.disconnect()
        }
    }

    private fun scheduleLayoutProbe(src: AbyssSource, total: Long) {
        // The legacy byte-range tail probe does not apply to Sora segments.
        if (src.segmented) return
        synchronized(this) {
            if (layoutProbeUrl == src.url) return
            layoutProbeUrl = src.url
        }
        Thread({
            try {
                val lastIndex = ((total - 1) / chunkSize).toInt()
                val lastFile = File(cache, md5(src.url + "|" + src.filename) + "-${chunkSize}-$lastIndex.bin")
                if (!lastFile.exists()) synchronized(lastFile.absolutePath.intern()) {
                    if (!lastFile.exists()) fetchChunk(src, lastIndex, lastFile, total)
                }
                val bytes = lastFile.readBytes()
                val needle = "moov".toByteArray(Charsets.US_ASCII)
                val offsets = mutableListOf<Long>()
                for (i in 0..(bytes.size - needle.size).coerceAtLeast(-1)) {
                    if (i + 4 <= bytes.size && bytes.copyOfRange(i, i + 4).contentEquals(needle)) {
                        offsets.add(lastIndex.toLong() * chunkSize + i - 4)
                        if (offsets.size >= 4) break
                    }
                }
                diagnostic("mp4_tail_probe", "tailStart=${lastIndex.toLong() * chunkSize} tailBytes=${bytes.size} moovHeaderOffsets=${if (offsets.isEmpty()) "none" else offsets.joinToString(",")}")
            } catch (e: Throwable) {
                diagnostic("mp4_tail_probe_error", "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            }
        }, "abyss-mp4-layout").apply { isDaemon = true; start() }
    }


    private fun markCacheAccess(file: File) {
        // Use lastModified as a lightweight LRU timestamp for recently read blocks.
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * Keep Abyss's on-disk cache bounded. Completed blocks are disposable; .part files
     * currently being downloaded are left alone, while abandoned partials expire after
     * ten minutes. Recently read blocks and the block just committed are protected.
     */
    private fun trimCache(protectedFile: File? = null) {
        synchronized(cache.absolutePath.intern()) {
            val now = System.currentTimeMillis()
            val files = cache.listFiles() ?: return
            files.filter { it.isFile && it.name.endsWith(".part") && now - it.lastModified() > 10L * 60L * 1000L }
                .forEach { runCatching { it.delete() } }
            val completed = files.filter { it.isFile && it.name.endsWith(".bin") }
            var totalBytes = completed.sumOf { it.length() }
            if (totalBytes <= maxCacheBytes) return
            val recentCutoff = now - 45_000L
            for (candidate in completed.sortedBy { it.lastModified() }) {
                if (totalBytes <= maxCacheBytes) break
                if (protectedFile != null && candidate.absolutePath == protectedFile.absolutePath) continue
                if (candidate.lastModified() >= recentCutoff) continue
                val size = candidate.length()
                if (runCatching { candidate.delete() }.getOrDefault(false)) totalBytes -= size
            }
            val remaining = cache.listFiles()?.count { it.isFile && it.name.endsWith(".bin") } ?: 0
            diagnostic("cache_trim", "limitBytes=" + maxCacheBytes + " remainingBytes=" + totalBytes + " filesRemaining=" + remaining)
        }
    }
    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return -1L
        return ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    }

    private fun decryptPrefix(bytes: ByteArray, length: Int, filename: String) {
        AbyssCrypto.decryptMp4Prefix(bytes, length, filename)
    }

    private fun totalLength(src: AbyssSource): Long {
        if (probedUrl == src.url && probedLength > 0L) return probedLength
        synchronized(this) {
            if (probedUrl == src.url && probedLength > 0L) return probedLength
            // Split Abyss/Sora URLs expose individual encrypted segments, not the full MP4.
            // Their full content length is already supplied by the decrypted metadata.
            val length = if (src.segmented) {
                src.expectedSize.takeIf { it > 0L }
                    ?: throw IllegalStateException("Sora source length unavailable in metadata")
            } else {
                probe(src).first
            }
            probedUrl = src.url
            probedLength = length
            return length
        }
    }

    private fun probe(src: AbyssSource): Pair<Long, Boolean> {
        val conn = URL(src.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000; conn.readTimeout = 15000; conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
        conn.setRequestProperty("Range", "bytes=0-0")
        conn.setRequestProperty("Referer", src.referer.ifBlank { "https://player.abyssplayer.com/" })
        conn.setRequestProperty("Origin", "https://player.abyssplayer.com")
        try {
            val code = conn.responseCode
            val total = if (code == 206) conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                else conn.contentLengthLong.takeIf { it > 0 }
            val actualTotal = total ?: throw IllegalStateException("source length unavailable")
            // Preserve the original direct-URL behavior: some Abyss URLs expose only
            // the first part (often exactly partSize) and report that part's length.
            // Do not reject the source merely because metadata advertises the full video size.
            // Sora transport uses expectedSize separately and does not probe the direct URL.
            return actualTotal to (code == 206)
        } finally { conn.disconnect() }
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null) return null
        val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: return -1L to -1L
        val a = m.groupValues[1].toLongOrNull()
        val b = m.groupValues[2].toLongOrNull()
        return when {
            a != null -> a to min(b ?: (total - 1), total - 1)
            b != null && b > 0 -> maxOf(0L, total - b) to total - 1
            else -> -1L to -1L
        }
    }

    private fun readHeader(input: BufferedInputStream): List<String>? {
        val out = StringBuilder()
        while (out.length < 8192) {
            val c = input.read()
            if (c < 0) return null
            out.append(c.toChar())
            if (out.endsWith("\r\n\r\n")) return out.toString().trimEnd().split("\r\n")
        }
        return null
    }

    private fun reply(client: Socket, status: Int, message: String) {
        val ascii = message.replace(Regex("[^ -~]"), " ").take(120)
        val bytes = "HTTP/1.1 $status Error\r\nX-Resolver-Error: unavailable; $ascii\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
        client.getOutputStream().write(bytes)
    }

    private fun md5(s: String) = AbyssCrypto.md5Hex(s)
}
