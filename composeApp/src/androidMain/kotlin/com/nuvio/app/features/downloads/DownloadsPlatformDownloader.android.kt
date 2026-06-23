package com.nuvio.app.features.downloads

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val downloadHttpClient = OkHttpClient.Builder()
    .dns(com.nuvio.app.core.network.AndroidDnsProvider)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal actual object DownloadsPlatformDownloader {
    private var appContext: Context? = null

    private fun resolveTarget(context: Context, uriString: String): DownloadTarget? {
        val uri = Uri.parse(uriString)
        return if (uri.scheme == "content") {
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
            DocumentSingleTarget(context, doc)
        } else {
            val file = if (uriString.startsWith("file:")) {
                File(URI(uriString))
            } else {
                File(uriString)
            }
            FileDownloadTarget(file)
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        var call: Call? = null

        scope.launch {
            val context = appContext
            if (context == null) {
                onFailure(runBlocking { getString(Res.string.downloads_error_not_initialized) })
                return@launch
            }

            DownloadsSettingsRepository.ensureLoaded()
            val customLocationUriString = DownloadsSettingsRepository.downloadLocationUri.value
            val customLocationUri = customLocationUriString?.let { Uri.parse(it) }

            val destination: DownloadTarget
            val tempFile: DownloadTarget

            if (customLocationUri != null && customLocationUri.scheme == "content") {
                val tree = DocumentFile.fromTreeUri(context, customLocationUri)
                if (tree == null || !tree.canWrite()) {
                    onFailure(runBlocking { getString(Res.string.downloads_error_cannot_write_location) })
                    return@launch
                }
                destination = DocumentDownloadTarget(context, tree, request.destinationFileName)
                tempFile = DocumentDownloadTarget(context, tree, "${request.destinationFileName}.part")
            } else {
                val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
                destination = FileDownloadTarget(File(downloadsDir, request.destinationFileName))
                tempFile = FileDownloadTarget(File(downloadsDir, "${request.destinationFileName}.part"))
            }

            try {
                var resumeFromBytes = if (tempFile.exists()) tempFile.length().coerceAtLeast(0L) else 0L

                fun buildRequest(rangeStart: Long?): Request {
                    val requestBuilder = Request.Builder().url(request.sourceUrl)
                    request.sourceHeaders.forEach { (key, value) ->
                        requestBuilder.header(key, value)
                    }
                    if (rangeStart != null && rangeStart > 0L) {
                        requestBuilder.header("Range", "bytes=$rangeStart-")
                    }
                    return requestBuilder.get().build()
                }

                var attemptedRangeRequest = resumeFromBytes > 0L
                var httpRequest = buildRequest(if (attemptedRangeRequest) resumeFromBytes else null)
                call = downloadHttpClient.newCall(httpRequest)
                var response = call?.execute() ?: error(
                    runBlocking { getString(Res.string.downloads_error_request_failed) },
                )

                if (attemptedRangeRequest && response.code == 416) {
                    response.close()
                    tempFile.delete()
                    resumeFromBytes = 0L
                    attemptedRangeRequest = false
                    httpRequest = buildRequest(null)
                    call = downloadHttpClient.newCall(httpRequest)
                    response = call?.execute() ?: error(
                        runBlocking { getString(Res.string.downloads_error_request_failed) },
                    )
                }

                response.use { response ->
                    if (!response.isSuccessful) {
                        error(
                            runBlocking {
                                getString(Res.string.downloads_error_http_failed, response.code)
                            },
                        )
                    }

                    val isPartialResume = attemptedRangeRequest && response.code == 206 && resumeFromBytes > 0L
                    val appendToTemp = isPartialResume
                    val startingBytes = if (appendToTemp) resumeFromBytes else 0L

                    if (!appendToTemp && tempFile.exists()) {
                        tempFile.delete()
                    }

                    val body = response.body ?: error(
                        runBlocking { getString(Res.string.downloads_error_empty_body) },
                    )
                    val totalBytes = resolveTotalBytes(
                        startingBytes = startingBytes,
                        isPartialResume = isPartialResume,
                        contentRangeHeader = response.header("Content-Range"),
                        contentLength = body.contentLength().takeIf { it > 0L },
                    )
                    var downloadedBytes = startingBytes
                    onProgress(downloadedBytes, totalBytes)

                    body.byteStream().use { input ->
                        tempFile.openOutputStream(appendToTemp).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read.toLong()
                                onProgress(downloadedBytes, totalBytes)
                            }
                            output.flush()
                        }
                    }

                    if (destination.exists()) {
                        destination.delete()
                    }
                    if (!tempFile.renameTo(destination)) {
                        tempFile.copyTo(destination)
                        tempFile.delete()
                    }

                    val finalSize = destination.length()
                    onSuccess(destination.toUriString(), totalBytes ?: finalSize)
                }
            } catch (error: Throwable) {
                onFailure(error.message ?: runBlocking { getString(Res.string.download_failed) })
            }
        }

        job.invokeOnCompletion {
            call?.cancel()
        }

        return AndroidDownloadsTaskHandle(job)
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val context = appContext ?: return false
        val target = resolveTarget(context, localFileUri) ?: return false
        return target.delete()
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val context = appContext ?: return false
        DownloadsSettingsRepository.ensureLoaded()
        val customLocationUriString = DownloadsSettingsRepository.downloadLocationUri.value
        val customLocationUri = customLocationUriString?.let { Uri.parse(it) }

        val tempFile: DownloadTarget = if (customLocationUri != null && customLocationUri.scheme == "content") {
            val tree = DocumentFile.fromTreeUri(context, customLocationUri) ?: return false
            DocumentDownloadTarget(context, tree, "$destinationFileName.part")
        } else {
            val downloadsDir = File(context.filesDir, "downloads")
            FileDownloadTarget(File(downloadsDir, "$destinationFileName.part"))
        }

        if (!tempFile.exists()) return true
        return tempFile.delete()
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        val context = appContext ?: return null
        if (!localFileUri.isNullOrBlank()) {
            val target = resolveTarget(context, localFileUri)
            if (target?.exists() == true) {
                return target.toUriString()
            }
        }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.let { Uri.parse(it).lastPathSegment }
            ?: return null

        DownloadsSettingsRepository.ensureLoaded()
        val customLocationUriString = DownloadsSettingsRepository.downloadLocationUri.value
        val customLocationUri = customLocationUriString?.let { Uri.parse(it) }

        val localFileTarget: DownloadTarget = if (customLocationUri != null && customLocationUri.scheme == "content") {
            val tree = DocumentFile.fromTreeUri(context, customLocationUri) ?: return null
            DocumentDownloadTarget(context, tree, fileName)
        } else {
            val downloadsDir = File(context.filesDir, "downloads")
            FileDownloadTarget(File(downloadsDir, fileName))
        }

        return localFileTarget.takeIf { it.exists() }?.toUriString()
    }

    actual fun fetchUrlAsString(url: String, headers: Map<String, String>): String? {
        return try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            val response = downloadHttpClient.newCall(requestBuilder.get().build()).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.string()
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    actual fun probeHlsContentType(url: String, headers: Map<String, String>): Boolean {
        return try {
            val requestBuilder = Request.Builder().url(url).head()
            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            val response = downloadHttpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val contentType = resp.header("Content-Type")
                    HlsPlaylistParser.isHlsContentType(contentType)
                } else false
            }
        } catch (_: Exception) {
            false
        }
    }

    actual fun downloadAndRemuxHls(
        request: HlsRemuxRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)

        scope.launch {
            val context = appContext
            if (context == null) {
                onFailure(runBlocking { getString(Res.string.downloads_error_not_initialized) })
                return@launch
            }

            DownloadsSettingsRepository.ensureLoaded()
            val customLocationUriString = DownloadsSettingsRepository.downloadLocationUri.value
            val customLocationUri = customLocationUriString?.let { Uri.parse(it) }

            val workDir = File(context.cacheDir, "hls-remux-${request.destinationFileName}").apply { mkdirs() }

            try {
                val destination: DownloadTarget = if (customLocationUri != null && customLocationUri.scheme == "content") {
                    val tree = DocumentFile.fromTreeUri(context, customLocationUri)
                    if (tree == null || !tree.canWrite()) {
                        error(runBlocking { getString(Res.string.downloads_error_cannot_write_location) })
                    }
                    DocumentDownloadTarget(context, tree, request.destinationFileName)
                } else {
                    val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
                    FileDownloadTarget(File(downloadsDir, request.destinationFileName))
                }

                // 1. Fetch + parse video playlist
                val videoPlaylistContent = fetchUrlAsString(
                    request.video.playlistUrl,
                    request.sourceHeaders,
                ) ?: error("Failed to fetch video playlist")
                val videoPlaylist = try {
                    HlsPlaylistParser.parseMediaPlaylist(
                        videoPlaylistContent, request.video.playlistUrl,
                    )
                } catch (e: Exception) {
                    error(
                        "Failed to parse video playlist: ${e.message}. " +
                            "Playlist URL: ${request.video.playlistUrl}. " +
                            "First 500 chars of playlist content: " +
                            videoPlaylistContent.take(500).replace('\n', ' ').replace('\r', ' '),
                    )
                }
                if (videoPlaylist.segments.isEmpty()) error("Empty video playlist")
                validateEncryption(videoPlaylist.encryption)

                // 2. Fetch + parse audio playlists
                // Skip tracks with an empty playlist URL — they are embedded in the
                // video stream (no separate playlist to fetch). MediaExtractor will
                // harvest the embedded audio automatically during remux.
                val audioPlaylists = request.audioTracks
                    .filter { it.playlistUrl.isNotBlank() }
                    .map { track ->
                        val content = fetchUrlAsString(track.playlistUrl, request.sourceHeaders)
                            ?: error("Failed to fetch audio playlist: ${track.name}")
                        val pl = HlsPlaylistParser.parseMediaPlaylist(content, track.playlistUrl)
                        if (pl.segments.isEmpty()) error("Empty audio playlist: ${track.name}")
                        validateEncryption(pl.encryption)
                        TrackPlaylist(track, pl)
                    }

                // 3. Fetch + parse subtitle playlists
                // Skip tracks with an empty playlist URL — they are embedded in the
                // video stream and cannot be downloaded as a separate file.
                val subtitlePlaylists = request.subtitleTracks
                    .filter { it.playlistUrl.isNotBlank() }
                    .map { track ->
                        val content = fetchUrlAsString(track.playlistUrl, request.sourceHeaders)
                            ?: error("Failed to fetch subtitle playlist: ${track.name}")
                        val pl = HlsPlaylistParser.parseMediaPlaylist(content, track.playlistUrl)
                        if (pl.segments.isEmpty()) error("Empty subtitle playlist: ${track.name}")
                        validateEncryption(pl.encryption)
                        TrackPlaylist(track, pl)
                    }

                // 4. Download all segments into temp files (and .vtt for subtitles)
                var totalDownloaded = 0L

                // For fMP4-based HLS, the init segment (ftyp+moov) must be
                // prepended to the concatenated media segments so that
                // MediaExtractor can parse the result. For MPEG-TS streams
                // there is no init segment and concatenation is sufficient.
                val videoInitFile = videoPlaylist.initSegmentUri?.let { initUri ->
                    val f = File(workDir, "video_init.mp4")
                    fetchInitSegment(initUri, request.sourceHeaders, f)
                    f
                }
                val videoTsFile = File(workDir, if (videoInitFile != null) "video.mp4" else "video.ts")
                downloadSegmentsToTsFile(
                    segmentUrls = videoPlaylist.segments.map { it.url },
                    headers = request.sourceHeaders,
                    outFile = videoTsFile,
                    encryption = videoPlaylist.encryption,
                    mediaSequenceStart = videoPlaylist.mediaSequence,
                    onChunk = { bytes ->
                        totalDownloaded += bytes
                        onProgress(totalDownloaded, null)
                    },
                    job = job,
                )

                val audioTsFiles = audioPlaylists.mapIndexed { index, tp ->
                    val f = File(workDir, "audio_$index.ts")
                    downloadSegmentsToTsFile(
                        segmentUrls = tp.playlist.segments.map { it.url },
                        headers = request.sourceHeaders,
                        outFile = f,
                        encryption = tp.playlist.encryption,
                        mediaSequenceStart = tp.playlist.mediaSequence,
                        onChunk = { bytes ->
                            totalDownloaded += bytes
                            onProgress(totalDownloaded, null)
                        },
                        job = job,
                    )
                    f
                }

                val subtitleVttFiles = subtitlePlaylists.mapIndexed { index, tp ->
                    val f = File(workDir, "subtitle_$index.vtt")
                    downloadAndConcatVttSegments(
                        segments = tp.playlist.segments,
                        headers = request.sourceHeaders,
                        outFile = f,
                        onChunk = { bytes ->
                            totalDownloaded += bytes
                            onProgress(totalDownloaded, null)
                        },
                        job = job,
                    )
                    f
                }

                // 5. For fMP4 streams, prepend the init segment to the
                //    concatenated media segments so that MediaExtractor can
                //    parse the file. Without the init segment (ftyp+moov),
                //    MediaExtractor throws "Failed to instantiate extractor".
                if (videoInitFile != null && videoInitFile.exists()) {
                    val combinedVideo = File(workDir, "video_combined.mp4")
                    videoInitFile.inputStream().use { initInput ->
                        combinedVideo.outputStream().use { combinedOutput ->
                            initInput.copyTo(combinedOutput)
                            videoTsFile.inputStream().use { mediaInput ->
                                mediaInput.copyTo(combinedOutput)
                            }
                        }
                    }
                    videoTsFile.delete()
                    combinedVideo.renameTo(videoTsFile)
                }

                // 6. Remux video + audio into MP4 via MediaMuxer
                val tempMp4 = File(workDir, "output.mp4")
                remuxToMp4(
                    videoTs = videoTsFile,
                    audioTsFiles = audioTsFiles,
                    audioTracks = audioPlaylists.map { it.track },
                    outputMp4 = tempMp4,
                    videoInitSegmentPresent = videoInitFile != null,
                )

                // 7. Copy MP4 to destination
                if (destination.exists()) destination.delete()
                tempMp4.inputStream().use { input ->
                    destination.openOutputStream(false).use { output ->
                        input.copyTo(output)
                    }
                }

                // 8. Copy subtitle sidecar files (.vtt) next to the MP4
                // Android MediaMuxer does not support mov_text subtitle tracks in MP4 output,
                // so subtitles are saved as sidecar WebVTT files. ExoPlayer auto-loads them
                // when they share the same base filename (e.g. "movie.mp4" + "movie.it.vtt").
                val baseName = request.destinationFileName.substringBeforeLast('.')
                subtitlePlaylists.forEachIndexed { index, tp ->
                    val lang = tp.track.language?.takeIf { it.isNotBlank() }
                        ?.sanitizeFileNameForSidecar()
                        ?: (index + 1).toString()
                    val sidecarName = "${baseName}.$lang.vtt"
                    val sidecarTarget = if (customLocationUri != null && customLocationUri.scheme == "content") {
                        val tree = DocumentFile.fromTreeUri(context, customLocationUri)
                            ?: return@forEachIndexed
                        DocumentDownloadTarget(context, tree, sidecarName)
                    } else {
                        val downloadsDir = File(context.filesDir, "downloads")
                        FileDownloadTarget(File(downloadsDir, sidecarName))
                    }
                    if (sidecarTarget.exists()) sidecarTarget.delete()
                    subtitleVttFiles[index].inputStream().use { input ->
                        sidecarTarget.openOutputStream(false).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                // 9. Cleanup temp dir
                workDir.deleteRecursively()

                val finalSize = destination.length()
                onSuccess(destination.toUriString(), finalSize)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                runCatching { workDir.deleteRecursively() }
                onFailure(error.message ?: runBlocking { getString(Res.string.download_failed) })
            }
        }

        return AndroidDownloadsTaskHandle(job)
    }

    private data class TrackPlaylist(
        val track: HlsRemuxTrack,
        val playlist: HlsMediaPlaylist,
    )

    /**
     * Download the HLS Initialization Segment (#EXT-X-MAP) and write it to
     * [outFile]. The init segment contains the `ftyp` + `moov` boxes that
     * describe the track structure of an fMP4 stream and MUST be prepended
     * to the concatenated media segments for MediaExtractor to parse them.
     */
    private fun fetchInitSegment(
        initUri: String,
        headers: Map<String, String>,
        outFile: File,
    ) {
        val requestBuilder = Request.Builder().url(initUri)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val response = downloadHttpClient.newCall(requestBuilder.get().build()).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                error("Failed to fetch init segment: HTTP ${resp.code}")
            }
            val body = resp.body ?: error("Empty init segment response body")
            outFile.outputStream().use { output ->
                output.write(body.bytes())
            }
        }
    }

    /**
     * Reject playlists that cannot be downloaded. AES-128 with http(s)/data: URI
     * is allowed (decryption happens in [downloadSegmentsToTsFile]). Everything
     * else (SAMPLE-AES, FairPlay, Widevine, PlayReady, or AES-128 with a custom
     * non-http URI) is rejected with a clear error.
     */
    private fun validateEncryption(encryption: HlsEncryption?) {
        if (encryption == null) return
        if (encryption.isAes128Decryptable) return
        if (encryption.isProprietaryDrm) {
            error(runBlocking { getString(Res.string.downloads_error_hls_drm) })
        }
        // AES-128 with a non-http URI we cannot fetch (e.g. custom scheme).
        error(runBlocking { getString(Res.string.downloads_error_hls_encrypted) })
    }

    /**
     * Fetch the AES-128 key. Supports both HTTP(S) URIs (with headers) and
     * inline `data:` URIs (RFC 2397). Returns 16 raw bytes per HLS spec.
     */
    private fun fetchKey(
        encryption: HlsEncryption,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        val uri = encryption.keyUri ?: error("AES-128 encryption with no URI")
        val lower = uri.lowercase()
        if (lower.startsWith("data:")) {
            return HlsPlaylistParser.decodeDataUri(uri)
                ?: error("Failed to decode data: key URI")
        }
        val requestBuilder = Request.Builder().url(uri)
        // Propagate the same headers used for segments (Authorization, Referer,
        // User-Agent, ...). Some CDNs require them even for the key endpoint.
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val response = downloadHttpClient.newCall(requestBuilder.get().build()).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                error("Failed to fetch AES-128 key: HTTP ${resp.code}")
            }
            val body = resp.body ?: error("Empty AES-128 key response body")
            val bytes = body.bytes()
            if (bytes.size != 16) {
                error("AES-128 key must be 16 bytes, got ${bytes.size}")
            }
            return bytes
        }
    }

    /**
     * Decrypt one HLS segment with AES-128-CBC. HLS uses NoPadding: each
     * segment is padded to a 16-byte boundary by the packager (the last
     * block is completed with arbitrary bytes), so the cleartext length
     * equals the ciphertext length. After decryption, trailing pad bytes
     * must be discarded by the muxer (MediaExtractor handles them as part
     * of the TS framing, so we keep them).
     */
    private fun decryptSegment(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
        require(iv.size == 16) { "AES IV must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun downloadSegmentsToTsFile(
        segmentUrls: List<String>,
        headers: Map<String, String>,
        outFile: File,
        encryption: HlsEncryption?,
        mediaSequenceStart: Long,
        onChunk: (bytesDelta: Long) -> Unit,
        job: Job,
    ) {
        // Resolve the key once per playlist; the IV is per-segment (either
        // fixed by #EXT-X-KEY or derived from MEDIA-SEQUENCE per HLS spec).
        val aesKey: ByteArray? = if (encryption?.isAes128Decryptable == true) {
            fetchKey(encryption, headers)
        } else {
            null
        }

        // === Custom-header detection (per-stream) ===
        // Some CDNs (notably StreamingCommunity's edge) prepend a fixed-size
        // non-TS header to every segment of a stream. The header survives
        // AES-128 decryption (it's prepended by the CDN, not the packager),
        // so we cannot detect it from the encrypted bytes — only from the
        // decrypted plaintext.
        //
        // Strategy: for EACH segment, look for a valid TS sync byte (0x47)
        // followed by another 0x47 exactly 188 bytes later. The prefix length
        // may vary per segment (different CDN nodes, different padding) so we
        // re-detect on every segment rather than caching from the first.
        //
        // We DO cache the most-recently-seen prefix length as a hint to speed
        // up detection on subsequent segments (we check that offset first; if
        // it doesn't match, we fall back to a full scan).
        var tsPrefixHint: Int = 0

        outFile.outputStream().use { output ->
            segmentUrls.forEachIndexed { index, segmentUrl ->
                job.ensureActive()
                val requestBuilder = Request.Builder().url(segmentUrl)
                headers.forEach { (k, v) -> requestBuilder.header(k, v) }
                val response = downloadHttpClient.newCall(requestBuilder.get().build()).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        error(runBlocking { getString(Res.string.downloads_error_http_failed, resp.code) })
                    }
                    val body = resp.body ?: error(
                        runBlocking { getString(Res.string.downloads_error_empty_body) },
                    )
                    // Read full segment into memory: AES-128-CBC needs the
                    // complete ciphertext before decryption; segments are
                    // typically 2-10 MB so this is acceptable. For very
                    // long segments a streaming approach would be needed.
                    val ciphertext = body.bytes()
                    onChunk(ciphertext.size.toLong())

                    // === Content validation ===
                    // Some CDNs (e.g. StreamingCommunity's edge) return HTTP 200
                    // with an HTML body (token-expired / paywall / error page)
                    // when the request is missing required headers or the signed
                    // URL has expired. Sniffing the first bytes prevents that
                    // HTML from being concatenated into the .ts file, which
                    // would later cause "Failed to instantiate extractor".
                    val sniff = HlsPlaylistParser.sniffSegmentFormat(ciphertext)
                    if (sniff == "html") {
                        val preview = runCatching {
                            String(ciphertext, 0, minOf(256, ciphertext.size), Charsets.UTF_8)
                        }.getOrDefault("")
                        error(
                            "Server returned an HTML page instead of segment data for $segmentUrl " +
                                "(likely expired token or missing headers). Preview: " +
                                preview.replace('\n', ' ').replace('\r', ' ').take(200),
                        )
                    }

                    val plaintext = if (aesKey != null && encryption != null) {
                        val iv = encryption.iv ?: HlsPlaylistParser.deriveIvFromSequence(
                            mediaSequenceStart + index,
                        )
                        decryptSegment(ciphertext, aesKey, iv)
                    } else {
                        ciphertext
                    }

                    // === Per-segment prefix detection ===
                    // 1. Try the cached hint first (fast path).
                    // 2. If the byte at hint offset isn't 0x47, fall back to a
                    //    full scan up to 1024 bytes (slow path).
                    // 3. If still no sync found, write the segment as-is and let
                    //    the remux pre-flight produce a clear error.
                    val offset = if (tsPrefixHint in 0..(plaintext.size - 189) &&
                        plaintext[tsPrefixHint] == 0x47.toByte() &&
                        plaintext[tsPrefixHint + 188] == 0x47.toByte()
                    ) {
                        tsPrefixHint
                    } else {
                        val detected = HlsPlaylistParser.findTsSyncOffset(plaintext, maxScan = 1024)
                        if (detected >= 0) {
                            tsPrefixHint = detected
                        }
                        detected
                    }

                    val bytesToWrite = if (offset >= 0) {
                        plaintext.copyOfRange(offset, plaintext.size)
                    } else {
                        plaintext
                    }
                    output.write(bytesToWrite)
                }
            }
            output.flush()
        }
    }

    private fun downloadAndConcatVttSegments(
        segments: List<HlsSegment>,
        headers: Map<String, String>,
        outFile: File,
        onChunk: (bytesDelta: Long) -> Unit,
        job: Job,
    ) {
        val segmentContents = mutableListOf<String>()
        val segmentDurations = mutableListOf<Double>()

        for (segment in segments) {
            job.ensureActive()
            val requestBuilder = Request.Builder().url(segment.url)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }
            val response = downloadHttpClient.newCall(requestBuilder.get().build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    error(runBlocking { getString(Res.string.downloads_error_http_failed, resp.code) })
                }
                val body = resp.body ?: error(
                    runBlocking { getString(Res.string.downloads_error_empty_body) },
                )
                val text = body.string()
                segmentContents.add(text)
                segmentDurations.add(segment.duration)
                onChunk(text.length.toLong())
            }
        }

        val merged = HlsPlaylistParser.concatWebVttSegments(segmentContents, segmentDurations)
        outFile.writeText(merged)
    }

    @OptIn(UnstableApi::class)
    private fun remuxToMp4(
        videoTs: File,
        audioTsFiles: List<File>,
        audioTracks: List<HlsRemuxTrack>,
        outputMp4: File,
        videoInitSegmentPresent: Boolean = false,
    ) {
        // === Pre-flight validation ===
        // Catch the most common root causes of "Failed to instantiate extractor"
        // BEFORE calling MediaExtractor.setDataSource, so the error message
        // tells the user (and us) what actually went wrong instead of the
        // generic platform exception.
        if (!videoTs.exists() || videoTs.length() < 1024) {
            error(
                "Video segment file is missing or too small: " +
                    "${videoTs.absolutePath} (${videoTs.length()} bytes). " +
                    "The download likely failed mid-stream (network reset, CDN 200-OK-with-HTML, ...).",
            )
        }

        val videoSniff = sniffFileFormat(videoTs)
        // Read first 4KB for deep scan (covers more signatures than 256 bytes)
        val videoHeader4k = readFileHead(videoTs, 4096)
        when (videoSniff) {
            "html" -> error(
                "Video segment file appears to be HTML, not media data. " +
                    "The CDN returned an error page (token expired / paywall) with HTTP 200. " +
                    "First 200 bytes: " + peekFileText(videoTs, 200),
            )
            "fmp4" -> {
                if (!videoInitSegmentPresent) {
                    error(
                        "Video segments are fragmented MP4 (CMAF) but no #EXT-X-MAP init segment " +
                            "was found in the playlist. Without the ftyp+moov boxes MediaExtractor " +
                            "cannot parse the segments. This usually means the master playlist " +
                            "you selected is a sub-master that does not directly contain #EXT-X-MAP, " +
                            "or the playlist was served with a non-standard EXT-X-MAP format. " +
                            "Try a different quality variant.",
                    )
                }
                // fMP4 with init segment already prepended at step 5 — should be parseable.
            }
            "ts" -> {
                // MPEG-TS is self-describing; MediaExtractor handles it natively.
            }
            "unknown" -> {
                // === DEEP SCAN: identify the actual format from the first 4KB ===
                // When the simple sniffer returns "unknown", run a comprehensive scan
                // for format signatures at all offsets. This catches:
                //   - MPEG-TS with non-zero sync offset (M2TS, custom packagers)
                //   - H.264 Annex B elementary streams
                //   - fMP4 segments with non-standard first box
                //   - Playlists accidentally downloaded as segments
                //   - Encrypted ciphertext (high entropy)
                //   - Files produced by specific encoders (FFmpeg, x264, ...)
                val deepScan = if (videoHeader4k.isNotEmpty()) {
                    HlsPlaylistParser.deepScanFormat(videoHeader4k)
                } else {
                    "unable to read file header"
                }
                // Decide whether to hard-fail or let MediaExtractor try.
                if (deepScan.contains("PLAYLIST WAS DOWNLOADED AS A SEGMENT")) {
                    error(
                        "Video segment file is actually an HLS playlist, not media data. " +
                            "Deep scan: $deepScan. " +
                            "First 256 bytes (hex): " + peekFileHex(videoTs, 256),
                    )
                }
                if (deepScan.contains("High entropy")) {
                    error(
                        "Video segment file appears to be encrypted ciphertext. " +
                            "Deep scan: $deepScan. " +
                            "First 256 bytes (hex): " + peekFileHex(videoTs, 256) +
                            ". Check whether the playlist contains #EXT-X-KEY — if not, the CDN may be " +
                            "applying its own encryption layer that the downloader does not handle.",
                    )
                }
                // For other "unknown" cases (e.g. FFmpeg-muxed file with non-standard header),
                // include the deep scan in the error message if MediaExtractor fails below.
            }
        }

        val videoExtractor = MediaExtractor()
        try {
            videoExtractor.setDataSource(videoTs.absolutePath)
        } catch (e: Exception) {
            videoExtractor.release()
            val deepScanInfo = if (videoSniff == "unknown" && videoHeader4k.isNotEmpty()) {
                HlsPlaylistParser.deepScanFormat(videoHeader4k)
            } else {
                "sniff=$videoSniff (deep scan skipped)"
            }
            error(
                "MediaExtractor failed to instantiate extractor on ${videoTs.name} " +
                    "(size=${videoTs.length()} bytes, format=$videoSniff, " +
                    "initSegment=$videoInitSegmentPresent). " +
                    "Root cause: ${e.javaClass.simpleName}: ${e.message}. " +
                    "First 256 bytes (hex): " + peekFileHex(videoTs, 256) + ". " +
                    "Deep scan: $deepScanInfo",
            )
        }

        var videoTrackIndex = -1
        // When the master playlist has no separate audio variants, the audio
        // is multiplexed inside the video segments (.ts contains both video
        // and audio tracks). We detect and extract those embedded audio tracks
        // here so the resulting MP4 is not silent.
        val candidateEmbeddedAudioIndices = mutableListOf<Int>()
        val candidateEmbeddedAudioMimes = mutableListOf<String>()
        for (i in 0 until videoExtractor.trackCount) {
            val format = videoExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") && videoTrackIndex == -1 -> {
                    videoTrackIndex = i
                    videoExtractor.selectTrack(i)
                }
                mime.startsWith("audio/") && audioTsFiles.isEmpty() -> {
                    // Only harvest embedded audio when no separate audio files
                    // were downloaded. Otherwise we'd duplicate the audio.
                    candidateEmbeddedAudioIndices.add(i)
                    candidateEmbeddedAudioMimes.add(mime)
                    videoExtractor.selectTrack(i)
                }
            }
        }
        if (videoTrackIndex == -1) {
            videoExtractor.release()
            error(
                "No video track found in ${videoTs.name} " +
                    "(tracks=${videoExtractor.trackCount}, format=$videoSniff). " +
                    "The file may have been corrupted during download.",
            )
        }

        // === Filter embedded audio tracks by codec support ===
        // MediaMuxer on Android can only mux a limited set of audio codecs into
        // MP4. The supported codecs are:
        //   - audio/mp4a-latm (AAC)
        //   - audio/mpeg (MP3)
        //   - audio/3gpp (AMR)
        //   - audio/opus (Opus, on Android 10+)
        //
        // Unsupported codecs that MediaExtractor can READ from TS but
        // MediaMuxer CANNOT write to MP4 include:
        //   - audio/ac3 (Dolby Digital)
        //   - audio/eac3 (Dolby Digital Plus)
        //   - audio/vnd.dts (DTS)
        //   - audio/truehd (Dolby TrueHD)
        //
        // When MediaMuxer is given an unsupported audio codec, addTrack() may
        // succeed but stop() will fail with "Failed to stop the muxer" because
        // the codec's config bytes cannot be written to the MP4 header.
        //
        // Strategy: only add embedded audio tracks whose codec is in the
        // supported set. Skip the others (the resulting MP4 will be silent
        // or have fewer audio tracks, which is better than failing entirely).
        val supportedAudioMimes = setOf(
            "audio/mp4a-latm",
            "audio/mpeg",
            "audio/3gpp",
            "audio/opus",
        )
        val supportedEmbeddedAudioIndices = candidateEmbeddedAudioIndices
            .filterIndexed { idx, _ -> candidateEmbeddedAudioMimes[idx] in supportedAudioMimes }

        // === TWO-PASS for embedded audio ===
        // MediaMuxer fails at stop() with "Failed to stop the muxer" if a track
        // was added via addTrack() but never received any sample via
        // writeSampleData(). To avoid this, we do a first pass to count samples
        // per candidate embedded audio track, and only add to the muxer the
        // tracks that will actually have content.
        //
        // MediaExtractor has no "seek to start" API; we must release and recreate
        // it for the second pass.
        val embeddedAudioTrackIndices: List<Int> = if (supportedEmbeddedAudioIndices.isNotEmpty()) {
            val sampleCounts = mutableMapOf<Int, Long>()
            supportedEmbeddedAudioIndices.forEach { sampleCounts[it] = 0L }
            val countBuffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            while (true) {
                countBuffer.clear()
                val size = videoExtractor.readSampleData(countBuffer, 0)
                if (size < 0) break
                val trackIdx = videoExtractor.sampleTrackIndex
                sampleCounts[trackIdx] = (sampleCounts[trackIdx] ?: 0L) + 1L
                videoExtractor.advance()
            }
            // Only keep embedded audio tracks that have at least 1 sample.
            supportedEmbeddedAudioIndices.filter { (sampleCounts[it] ?: 0L) > 0L }
        } else {
            emptyList()
        }

        // === Recreate the extractor for the actual muxing pass ===
        // The first pass (or even just the initial track enumeration) has
        // advanced the extractor's internal cursor; we need a fresh one
        // positioned at the start of the file.
        videoExtractor.release()
        val vExtractor = MediaExtractor()
        try {
            vExtractor.setDataSource(videoTs.absolutePath)
        } catch (e: Exception) {
            vExtractor.release()
            error(
                "MediaExtractor failed on second pass for ${videoTs.name}: " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
        }
        vExtractor.selectTrack(videoTrackIndex)
        embeddedAudioTrackIndices.forEach { vExtractor.selectTrack(it) }

        val audioExtractors = mutableListOf<MediaExtractor>()
        val audioTrackIndices = mutableListOf<Int>()
        try {
            for (audioTs in audioTsFiles) {
                // Same pre-flight check for audio files.
                if (!audioTs.exists() || audioTs.length() < 1024) {
                    error("Audio segment file is missing or too small: ${audioTs.name} (${audioTs.length()} bytes)")
                }
                val audioSniff = sniffFileFormat(audioTs)
                if (audioSniff == "html") {
                    error(
                        "Audio segment file ${audioTs.name} appears to be HTML, not media data. " +
                            "First 200 bytes: " + peekFileText(audioTs, 200),
                    )
                }

                val ext = MediaExtractor()
                try {
                    ext.setDataSource(audioTs.absolutePath)
                } catch (e: Exception) {
                    ext.release()
                    error(
                        "MediaExtractor failed on ${audioTs.name} " +
                            "(size=${audioTs.length()} bytes, format=$audioSniff). " +
                            "Root cause: ${e.javaClass.simpleName}: ${e.message}. " +
                            "First 32 bytes (hex): " + peekFileHex(audioTs, 32),
                    )
                }
                var foundIdx = -1
                for (i in 0 until ext.trackCount) {
                    val format = ext.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        foundIdx = i
                        ext.selectTrack(i)
                        break
                    }
                }
                if (foundIdx == -1) {
                    ext.release()
                    error("No audio track found in ${audioTs.name} (tracks=${ext.trackCount}, format=$audioSniff)")
                }
                audioExtractors.add(ext)
                audioTrackIndices.add(foundIdx)
            }

            // === Use androidx.media3 Mp4Muxer instead of android.media.MediaMuxer ===
            // MediaMuxer (framework) is fragile on some Android versions and
            // MIUI/HyperOS: it fails at stop() with "Failed to stop the muxer"
            // for several reasons (bogus KEY_DURATION, codec incompatibilities,
            // PTS regressions). media3's Mp4Muxer is more robust, is the same
            // engine used by ExoPlayer for the HLS playback path, is actively
            // maintained by Google, and adds zero APK size because media3 is
            // already a dependency of this project.
            //
            // MediaExtractor (framework) is still used for READING the .ts
            // because media3 does not expose a public drop-in extractor API
            // equivalent to MediaExtractor.selectTrack/readSampleData.
            //
            // === media3 1.8.0 API notes (verified from the actual JAR) ===
            // - Mp4Muxer.addTrack(Format) returns Int (track ID), NOT a
            //   TrackToken object. The Muxer interface declares it as int.
            // - Mp4Muxer.writeSampleData(int trackId, ByteBuffer, BufferInfo)
            //   takes the int track ID returned by addTrack.
            // - BufferInfo lives at androidx.media3.muxer.BufferInfo (NOT
            //   androidx.media3.common.BufferInfo — that doesn't exist).
            // - BufferInfo has a 3-arg constructor (presentationTimeUs, size,
            //   flags). NO offset field (unlike MediaCodec.BufferInfo).
            // - The muxer reads from buffer.position() to buffer.limit(), so
            //   after MediaExtractor.readSampleData we MUST set buffer.limit(size)
            //   to tell the muxer exactly how many bytes are valid.
            // - MediaExtractor.getSampleFlags() returns int values that happen
            //   to match media3's C.BUFFER_FLAG_* constants (1=key_frame,
            //   2=codec_config, 4=eos, 8=partial_frame), so we pass them through.
            //
            // === CSD probing ===
            // MediaExtractor does NOT always populate "csd-0"/"csd-1" in the
            // MediaFormat for TS inputs — on some Android versions (notably
            // Android 10 / MIUI 12) the CSD bytes are emitted only as separate
            // samples flagged with BUFFER_FLAG_CODEC_CONFIG, and NEVER placed
            // in the MediaFormat's csd-0/csd-1 keys.
            //
            // To handle both cases, we use probeCsdBytes() which:
            //   1. First tries the MediaFormat csd-0/csd-1 keys (fast path).
            //   2. Falls back to reading samples and harvesting the bytes of
            //      any sample flagged with BUFFER_FLAG_CODEC_CONFIG for the
            //      target track (slow path).
            // The harvested bytes are passed to toMedia3Format() as an explicit
            // override for the Format's initializationData, bypassing the
            // unreliable MediaFormat csd-* keys.
            val muxer = Mp4Muxer.Builder(FileOutputStream(outputMp4))
                .build()

            val videoCsd = probeCsdBytes(vExtractor, videoTrackIndex)
            if (videoCsd.isEmpty()) {
                error(
                    "Could not extract H.264/HEVC codec-specific data (SPS/PPS) from the video track. " +
                        "MediaExtractor neither populated csd-0/csd-1 in the MediaFormat nor emitted " +
                        "any sample with BUFFER_FLAG_CODEC_CONFIG flag. The video .ts may be corrupted " +
                        "or use an unsupported codec. Try a different quality variant.",
                )
            }
            val videoFormat = vExtractor.getTrackFormat(videoTrackIndex)
            sanitizeMediaFormatDuration(videoFormat)
            val videoOutTrackId: Int = muxer.addTrack(toMedia3Format(videoFormat, videoCsd))

            // External audio tracks (one per separate .ts file).
            val externalAudioTrackIds: List<Int> = audioExtractors.mapIndexed { idx, ext ->
                val audioCsd = probeCsdBytes(ext, audioTrackIndices[idx])
                val format = ext.getTrackFormat(audioTrackIndices[idx])
                sanitizeMediaFormatDuration(format)
                audioTracks.getOrNull(idx)?.language?.takeIf { it.isNotBlank() }?.let { lang ->
                    format.setString(MediaFormat.KEY_LANGUAGE, lang)
                }
                muxer.addTrack(toMedia3Format(format, audioCsd))
            }

            // Embedded audio tracks (only those that have at least 1 sample).
            // Also sanitize duration on each.
            val embeddedAudioTrackIds: List<Int> = embeddedAudioTrackIndices.map { idx ->
                val audioCsd = probeCsdBytes(vExtractor, idx)
                val format = vExtractor.getTrackFormat(idx)
                sanitizeMediaFormatDuration(format)
                muxer.addTrack(toMedia3Format(format, audioCsd))
            }

            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)

            // === Per-track sample counters (for diagnostics at close()) ===
            var videoSampleCount = 0L
            val embeddedAudioSampleCounts = LongArray(embeddedAudioTrackIds.size)
            val externalAudioSampleCounts = LongArray(externalAudioTrackIds.size)

            // === PTS tracking (for monotonic validation) ===
            // Mp4Muxer requires non-decreasing PTS within each track AND
            // across tracks (when interleaved). If we see a regression, we
            // clamp the new PTS to the previous one to avoid write failures.
            var lastVideoPts: Long = Long.MIN_VALUE
            val lastEmbeddedPts = LongArray(embeddedAudioTrackIds.size) { Long.MIN_VALUE }
            val lastExternalPts = LongArray(externalAudioTrackIds.size) { Long.MIN_VALUE }

            // Video + embedded audio samples are interleaved by the extractor
            // (MediaExtractor advances through samples of all selected tracks
            // in presentation order). We route each sample to its destination
            // track based on which track the extractor is currently at.
            //
            // === CSD samples ===
            // MediaExtractor may emit CSD (codec-specific data: SPS/PPS for
            // H.264, VPS+SPS+PPS for HEVC, AudioSpecificConfig for AAC, ...) as
            // separate samples flagged with BUFFER_FLAG_CODEC_CONFIG. We MUST
            // skip those samples here, because the CSD bytes have already been
            // written into the avcC/hvcC/esds box by Mp4Muxer using the
            // initializationData we set on the Format (see toMedia3Format()).
            // Writing them again as samples would either duplicate the CSD or
            // confuse the muxer into thinking they are real media frames.
            while (true) {
                buffer.clear()
                val size = vExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                // Tell the muxer only 'size' bytes are valid in the buffer.
                // Mp4Muxer reads from buffer.position() to buffer.limit().
                buffer.limit(size)
                val flags = vExtractor.sampleFlags
                val pts = vExtractor.sampleTime
                val currentTrack = vExtractor.sampleTrackIndex
                // Skip codec-config samples — CSD is already in the Format.
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    vExtractor.advance()
                    continue
                }
                when (currentTrack) {
                    videoTrackIndex -> {
                        var clampedPts = pts
                        if (clampedPts < lastVideoPts) clampedPts = lastVideoPts
                        lastVideoPts = clampedPts
                        muxer.writeSampleData(
                            videoOutTrackId,
                            buffer,
                            BufferInfo(clampedPts, size, flags),
                        )
                        videoSampleCount++
                    }
                    else -> {
                        val embeddedPos = embeddedAudioTrackIndices.indexOf(currentTrack)
                        if (embeddedPos >= 0) {
                            var clampedPts = pts
                            if (clampedPts < lastEmbeddedPts[embeddedPos]) clampedPts = lastEmbeddedPts[embeddedPos]
                            lastEmbeddedPts[embeddedPos] = clampedPts
                            muxer.writeSampleData(
                                embeddedAudioTrackIds[embeddedPos],
                                buffer,
                                BufferInfo(clampedPts, size, flags),
                            )
                            embeddedAudioSampleCounts[embeddedPos]++
                        }
                        // Samples from any other (unselected) track are dropped.
                    }
                }
                vExtractor.advance()
            }

            // External audio samples (each track sequentially).
            audioExtractors.forEachIndexed { idx, ext ->
                val trackId = externalAudioTrackIds[idx]
                while (true) {
                    buffer.clear()
                    val size = ext.readSampleData(buffer, 0)
                    if (size < 0) break
                    buffer.limit(size)
                    val flags = ext.sampleFlags
                    val pts = ext.sampleTime
                    // Skip codec-config samples (same rationale as above).
                    if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        ext.advance()
                        continue
                    }
                    var clampedPts = pts
                    if (clampedPts < lastExternalPts[idx]) clampedPts = lastExternalPts[idx]
                    lastExternalPts[idx] = clampedPts
                    muxer.writeSampleData(
                        trackId,
                        buffer,
                        BufferInfo(clampedPts, size, flags),
                    )
                    externalAudioSampleCounts[idx]++
                    ext.advance()
                }
            }

            // === Diagnostics-ready close() ===
            // Mp4Muxer.close() finalizes the MP4 (writes moov atom). If it
            // fails, surface a complete diagnostic block.
            try {
                muxer.close()
            } catch (e: Exception) {
                val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "unknown"
                val videoCodec = if (videoFormat.containsKey(MediaFormat.KEY_MIME)) videoMime else "no-mime"
                val videoDuration = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    videoFormat.getLong(MediaFormat.KEY_DURATION).toString() + " us"
                } else "unknown"

                val embeddedInfo = if (embeddedAudioTrackIds.isEmpty()) {
                    "none"
                } else {
                    embeddedAudioTrackIds.indices.joinToString(", ") { i ->
                        val trackIdx = embeddedAudioTrackIndices[i]
                        val mime = runCatching {
                            vExtractor.getTrackFormat(trackIdx).getString(MediaFormat.KEY_MIME)
                        }.getOrDefault("?")
                        "track$i($mime)=${embeddedAudioSampleCounts[i]}samples" +
                            (if (i < lastEmbeddedPts.size) " lastPts=${lastEmbeddedPts[i]}" else "")
                    }
                }
                val externalInfo = if (externalAudioTrackIds.isEmpty()) {
                    "none"
                } else {
                    externalAudioTrackIds.indices.joinToString(", ") { i ->
                        val mime = runCatching {
                            audioExtractors[i].getTrackFormat(audioTrackIndices[i])
                                .getString(MediaFormat.KEY_MIME)
                        }.getOrDefault("?")
                        "track$i($mime)=${externalAudioSampleCounts[i]}samples" +
                            (if (i < lastExternalPts.size) " lastPts=${lastExternalPts[i]}" else "")
                    }
                }

                val skippedAudioInfo = if (candidateEmbeddedAudioIndices.size != embeddedAudioTrackIndices.size) {
                    val skippedMimes = candidateEmbeddedAudioIndices
                        .filterIndexed { idx, _ -> candidateEmbeddedAudioMimes[idx] !in supportedAudioMimes }
                        .mapIndexed { idx, _ -> candidateEmbeddedAudioMimes[idx] }
                    " skippedUnsupported=${skippedMimes.joinToString(",")}"
                } else ""

                error(
                    "Mp4Muxer.close() failed: ${e.javaClass.simpleName}: ${e.message}. " +
                        "Diagnostic: videoCodec=$videoCodec, videoDuration=$videoDuration, " +
                        "videoSamples=$videoSampleCount, lastVideoPts=${if (lastVideoPts == Long.MIN_VALUE) "none" else lastVideoPts}, " +
                        "candidateEmbeddedAudio=${candidateEmbeddedAudioIndices.size}, " +
                        "activeEmbeddedAudio=${embeddedAudioTrackIndices.size} ($embeddedInfo), " +
                        "externalAudio=${audioExtractors.size} ($externalInfo),$skippedAudioInfo " +
                        "Likely causes: (a) codec not writable to MP4 container, " +
                        "(b) PTS regressions beyond what clamping could fix, " +
                        "(c) sample format incompatibility. " +
                        "Try with a different quality variant or check device codec support.",
                )
            }
        } finally {
            vExtractor.release()
            audioExtractors.forEach { runCatching { it.release() } }
        }
    }

    /**
     * Extract codec-specific data (CSD) bytes for [trackIdx] from [extractor].
     *
     * Background: MediaExtractor on Android has TWO ways of exposing CSD
     * (SPS/PPS for H.264, VPS+SPS+PPS for HEVC, AudioSpecificConfig for
     * AAC, ...), and the behavior depends on the Android version and the
     * input format:
     *
     *   1. **MediaFormat keys** (csd-0, csd-1): populated eagerly by some
     *      MediaExtractor implementations, especially for MP4 inputs. On
     *      Android 10 / MIUI 12 reading TS files, these keys are TYPICALLY
     *      EMPTY — MediaExtractor does not bother extracting SPS/PPS from
     *      the TS keyframe into the MediaFormat.
     *   2. **Samples flagged with BUFFER_FLAG_CODEC_CONFIG**: emitted as
     *      separate "samples" by MediaExtractor when reading the file. The
     *      sample bytes ARE the raw CSD (Annex B form for H.264: starts with
     *      00 00 00 01 ...).
     *
     * This function tries both:
     *   1. Fast path: read csd-0/csd-1 from the MediaFormat.
     *   2. Slow path: seek to start, read samples until we find up to 2
     *      samples flagged BUFFER_FLAG_CODEC_CONFIG for the target track,
     *      harvest their bytes as csd-0 / csd-1. Then seek back to start.
     *
     * Returns a List<ByteArray> with 0, 1, or 2 elements (in csd-0, csd-1
     * order). The caller should pass this list to toMedia3Format() as the
     * `csdOverride` parameter so the Format gets correct initializationData
     * regardless of which path succeeded.
     *
     * The extractor's read position is always reset to 0 (well, to the
     * previous sync point before 0) before returning, so the main write
     * loop can proceed normally.
     */
    private fun probeCsdBytes(extractor: MediaExtractor, trackIdx: Int): List<ByteArray> {
        // === Fast path: MediaFormat csd-0 / csd-1 ===
        val format = extractor.getTrackFormat(trackIdx)
        val fromFormat = mutableListOf<ByteArray>()
        for (key in listOf("csd-0", "csd-1")) {
            if (!format.containsKey(key)) continue
            val bb = format.getByteBuffer(key) ?: continue
            if (bb.remaining() <= 0) continue
            val bytes = ByteArray(bb.remaining())
            bb.duplicate().get(bytes)
            if (bytes.isNotEmpty()) fromFormat.add(bytes)
        }
        if (fromFormat.isNotEmpty()) return fromFormat

        // === Slow path: harvest bytes from BUFFER_FLAG_CODEC_CONFIG samples ===
        // Read up to 200 samples looking for codec-config samples on the target
        // track. Collect up to 2 of them (csd-0 + csd-1 for H.264, csd-0 only
        // for HEVC/AAC).
        val probeBuf = ByteBuffer.allocateDirect(2 * 1024 * 1024)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val harvested = mutableListOf<ByteArray>()
        var attempts = 0
        val maxAttempts = 200
        while (attempts < maxAttempts && harvested.size < 2) {
            probeBuf.clear()
            val size = extractor.readSampleData(probeBuf, 0)
            if (size < 0) break
            val flags = extractor.sampleFlags
            val currentTrack = extractor.sampleTrackIndex
            if (currentTrack == trackIdx &&
                (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 &&
                size > 0
            ) {
                val bytes = ByteArray(size)
                // Duplicate the buffer so we don't disturb the original's
                // position; then rewind and read exactly 'size' bytes.
                probeBuf.duplicate().also { it.flip() }.get(bytes, 0, size)
                harvested.add(bytes)
            }
            extractor.advance()
            attempts++
        }
        // Always reset position so the main write loop starts from the beginning.
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        return harvested
    }

    /**
     * Convert an `android.media.MediaFormat` (returned by `MediaExtractor`) into
     * an `androidx.media3.common.Format` (consumed by `Mp4Muxer.addTrack`).
     *
     * We copy the fields that matter for muxing: mime type, language, codec-specific
     * dimensions (width/height/frame-rate for video, channel-count/sample-rate
     * for audio), and crucially the **codec-specific data (CSD)** that MediaExtractor
     * exposes via the "csd-0" / "csd-1" keys.
     *
     * Without CSD in the Format, Mp4Muxer cannot write the `avcC` box (H.264),
     * `hvcC` box (HEVC), `dvcC` box (Dolby Vision), or `OpusConfiguration` box
     * that the MP4 container requires. The muxer would throw:
     *
     *     "csd-0 and/or csd-1 not found in the format for avcC box"
     *
     * CSD layout per codec (as returned by MediaExtractor on TS inputs):
     *   - H.264 (video/avc):       csd-0 = SPS, csd-1 = PPS (Annex B form: 00 00 00 01 ...)
     *   - HEVC (video/hevc):       csd-0 = VPS+SPS+PPS concatenated, csd-1 absent
     *   - AAC (audio/mp4a-latm):   csd-0 = AudioSpecificConfig (2-5 bytes), csd-1 absent
     *   - Opus (audio/opus):       csd-0 = Opus identification header
     *
     * Media3's Mp4Muxer uses an internal `AnnexBToAvccConverter` to convert
     * Annex B SPS/PPS into the AVCC format required by the avcC box, so we can
     * pass the raw Annex B bytes directly.
     *
     * Note: media3's `Format.Builder` does NOT expose `setDurationUs()`; the
     * duration is computed automatically by the muxer from the last written
     * sample's PTS. Any bogus `KEY_DURATION` has already been clamped by
     * [sanitizeMediaFormatDuration] before this function is called.
     */
    @OptIn(UnstableApi::class)
    private fun toMedia3Format(
        mediaFormat: MediaFormat,
        csdOverride: List<ByteArray> = emptyList(),
    ): Format {
        val builder = Format.Builder()
        val mime = mediaFormat.getString(MediaFormat.KEY_MIME) ?: ""
        builder.setSampleMimeType(mime)

        mediaFormat.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotBlank() }?.let {
            builder.setLanguage(it)
        }

        when {
            mime.startsWith("video/") -> {
                if (mediaFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                    builder.setWidth(mediaFormat.getInteger(MediaFormat.KEY_WIDTH))
                }
                if (mediaFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                    builder.setHeight(mediaFormat.getInteger(MediaFormat.KEY_HEIGHT))
                }
                if (mediaFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    val fr = mediaFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                    if (fr > 0) builder.setFrameRate(fr.toFloat())
                }
            }
            mime.startsWith("audio/") -> {
                if (mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    builder.setChannelCount(mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
                if (mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    builder.setSampleRate(mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                }
            }
        }

        // === Codec-Specific Data (CSD) ===
        // Prefer the [csdOverride] (collected by probeCsdBytes via
        // BUFFER_FLAG_CODEC_CONFIG samples — the reliable path for TS inputs
        // on Android 10 / MIUI 12). Fall back to the MediaFormat's csd-0/csd-1
        // keys (works on some other platforms and for MP4 inputs).
        val csdBytes: List<ByteArray> = if (csdOverride.isNotEmpty()) {
            csdOverride
        } else {
            val fromFormat = mutableListOf<ByteArray>()
            for (csdKey in listOf("csd-0", "csd-1")) {
                if (!mediaFormat.containsKey(csdKey)) continue
                val bb = mediaFormat.getByteBuffer(csdKey) ?: continue
                val bytes = ByteArray(bb.remaining())
                bb.duplicate().get(bytes)
                if (bytes.isNotEmpty()) fromFormat.add(bytes)
            }
            fromFormat
        }
        if (csdBytes.isNotEmpty()) {
            builder.setInitializationData(csdBytes)
        }

        return builder.build()
    }

    /**
     * Clamp the KEY_DURATION field of a MediaFormat to a sane range.
     *
     * Some MediaExtractor implementations (notably on MIUI / older Android)
     * report bogus duration values when the source TS stream has discontinuities
     * at HLS segment boundaries. Typical bogus values are near 2^63 (interpreted
     * as huge unsigned when formatted) or negative.
     *
     * Both MediaMuxer and media3's Mp4Muxer can fail at stop()/close() if the
     * duration field is bogus, because the mvhd atom's timescale cannot
     * represent it.
     *
     * Sane range: 0 to 24 hours (86_400_000_000 microseconds). Anything outside
     * is replaced with 0 (which lets the muxer compute duration from the last
     * written sample's PTS).
     */
    private fun sanitizeMediaFormatDuration(format: MediaFormat) {
        if (!format.containsKey(MediaFormat.KEY_DURATION)) return
        val raw = format.getLong(MediaFormat.KEY_DURATION)
        val oneDayUs = 86_400_000_000L
        if (raw < 0L || raw > oneDayUs) {
            format.setLong(MediaFormat.KEY_DURATION, 0L)
        }
    }

    private fun String.sanitizeFileNameForSidecar(): String =
        trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_")

    /**
     * Sniff the format of a downloaded segment file by reading its first 256 bytes
     * and delegating to [HlsPlaylistParser.sniffSegmentFormat].
     */
    private fun sniffFileFormat(file: File): String {
        if (!file.exists() || file.length() == 0L) return "unknown"
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(256)
                val read = input.read(header)
                if (read <= 0) return "unknown"
                HlsPlaylistParser.sniffSegmentFormat(
                    if (read == header.size) header else header.copyOf(read),
                )
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    /**
     * Read the first [maxBytes] of [file] as a raw byte array, for deep scan.
     * Returns an empty array if the file cannot be read.
     */
    private fun readFileHead(file: File, maxBytes: Int): ByteArray {
        if (!file.exists() || file.length() == 0L) return ByteArray(0)
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(maxBytes.coerceAtLeast(1))
                val read = input.read(buf)
                if (read <= 0) ByteArray(0) else if (read == buf.size) buf else buf.copyOf(read)
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    /** Read the first [maxBytes] of [file] as UTF-8 text, for error messages. */
    private fun peekFileText(file: File, maxBytes: Int): String {
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(maxBytes.coerceAtLeast(1))
                val read = input.read(buf)
                if (read <= 0) "" else String(buf, 0, read, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
    }

    /** Read the first [maxBytes] of [file] and return them as a hex string, for error messages. */
    private fun peekFileHex(file: File, maxBytes: Int): String {
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(maxBytes.coerceAtLeast(1))
                val read = input.read(buf)
                if (read <= 0) return@use ""
                StringBuilder(read * 2).also { sb ->
                    for (i in 0 until read) {
                        val v = buf[i].toInt() and 0xFF
                        sb.append(HEX_DIGITS[v ushr 4])
                        sb.append(HEX_DIGITS[v and 0x0F])
                    }
                }.toString()
            }
        } catch (_: Exception) {
            ""
        }
    }
}

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

private class AndroidDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }
}

private interface DownloadTarget {
    fun exists(): Boolean
    fun length(): Long
    fun delete(): Boolean
    fun openOutputStream(append: Boolean): OutputStream
    fun toUriString(): String
    fun renameTo(other: DownloadTarget): Boolean
    fun copyTo(other: DownloadTarget)
}

private class FileDownloadTarget(private val file: File) : DownloadTarget {
    override fun exists(): Boolean = file.exists()
    override fun length(): Long = file.length()
    override fun delete(): Boolean = file.delete()
    override fun openOutputStream(append: Boolean): OutputStream = FileOutputStream(file, append)
    override fun toUriString(): String = file.toURI().toString()
    override fun renameTo(other: DownloadTarget): Boolean {
        if (other is FileDownloadTarget) {
            return file.renameTo(other.file)
        }
        return false
    }
    override fun copyTo(other: DownloadTarget) {
        if (other is FileDownloadTarget) {
            file.copyTo(other.file, overwrite = true)
        } else {
            file.inputStream().use { input ->
                other.openOutputStream(false).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}

private class DocumentDownloadTarget(
    private val context: Context,
    private val tree: DocumentFile,
    private val fileName: String,
) : DownloadTarget {
    private fun getDoc(): DocumentFile? = tree.findFile(fileName)

    override fun exists(): Boolean = getDoc()?.exists() ?: false
    override fun length(): Long = getDoc()?.length() ?: 0L
    override fun delete(): Boolean = getDoc()?.delete() ?: false
    override fun openOutputStream(append: Boolean): OutputStream {
        val doc = getDoc() ?: tree.createFile("application/octet-stream", fileName)
            ?: error("Failed to create file $fileName")
        return context.contentResolver.openOutputStream(doc.uri, if (append) "wa" else "w")
            ?: error("Failed to open output stream for $fileName")
    }
    override fun toUriString(): String = getDoc()?.uri?.toString() ?: ""
    override fun renameTo(other: DownloadTarget): Boolean {
        val doc = getDoc() ?: return false
        if (other is DocumentDownloadTarget && other.tree.uri == tree.uri) {
            return doc.renameTo(other.fileName)
        }
        return false
    }
    override fun copyTo(other: DownloadTarget) {
        val doc = getDoc() ?: return
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            other.openOutputStream(false).use { output ->
                input.copyTo(output)
            }
        }
    }
}

private class DocumentSingleTarget(
    private val context: Context,
    private val doc: DocumentFile,
) : DownloadTarget {
    override fun exists(): Boolean = doc.exists()
    override fun length(): Long = doc.length()
    override fun delete(): Boolean = doc.delete()
    override fun openOutputStream(append: Boolean): OutputStream =
        context.contentResolver.openOutputStream(doc.uri, if (append) "wa" else "w")
            ?: error("Failed to open output stream")
    override fun toUriString(): String = doc.uri.toString()
    override fun renameTo(other: DownloadTarget): Boolean = false
    override fun copyTo(other: DownloadTarget) {
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            other.openOutputStream(false).use { output ->
                input.copyTo(output)
            }
        }
    }
}

private fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

private fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}
