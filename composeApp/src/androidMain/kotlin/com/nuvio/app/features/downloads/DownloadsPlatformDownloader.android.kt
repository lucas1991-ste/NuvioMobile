package com.nuvio.app.features.downloads

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
                val videoPlaylist = HlsPlaylistParser.parseMediaPlaylist(
                    videoPlaylistContent, request.video.playlistUrl,
                )
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
                    // For the very first segment, also log a warning if format
                    // is unknown — it may still be a valid encrypted blob (AES-128
                    // ciphertext is indistinguishable from random bytes), so we
                    // don't hard-fail here; the remux step will catch real issues.
                    if (index == 0 && sniff == "unknown" && aesKey == null) {
                        // Soft signal: append a marker to the file name? No —
                        // we surface the issue at remux time with a clear error.
                    }

                    val plaintext = if (aesKey != null && encryption != null) {
                        val iv = encryption.iv ?: HlsPlaylistParser.deriveIvFromSequence(
                            mediaSequenceStart + index,
                        )
                        decryptSegment(ciphertext, aesKey, iv)
                    } else {
                        ciphertext
                    }
                    output.write(plaintext)
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
                // Could be encrypted blob already decrypted, or genuinely unknown.
                // Don't hard-fail here; let MediaExtractor try and produce its own
                // error, which we'll wrap below with context.
            }
        }

        val videoExtractor = MediaExtractor()
        try {
            videoExtractor.setDataSource(videoTs.absolutePath)
        } catch (e: Exception) {
            videoExtractor.release()
            error(
                "MediaExtractor failed to instantiate extractor on ${videoTs.name} " +
                    "(size=${videoTs.length()} bytes, format=$videoSniff, " +
                    "initSegment=$videoInitSegmentPresent). " +
                    "Root cause: ${e.javaClass.simpleName}: ${e.message}. " +
                    "First 32 bytes (hex): " + peekFileHex(videoTs, 32),
            )
        }

        var videoTrackIndex = -1
        // When the master playlist has no separate audio variants, the audio
        // is multiplexed inside the video segments (.ts contains both video
        // and audio tracks). We detect and extract those embedded audio tracks
        // here so the resulting MP4 is not silent.
        val embeddedAudioTrackIndices = mutableListOf<Int>()
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
                    embeddedAudioTrackIndices.add(i)
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

            val muxer = MediaMuxer(
                outputMp4.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )

            val videoOutTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))

            // External audio tracks (one per separate .ts file).
            val externalAudioOutTracks = audioExtractors.mapIndexed { idx, ext ->
                val format = ext.getTrackFormat(audioTrackIndices[idx])
                audioTracks.getOrNull(idx)?.language?.takeIf { it.isNotBlank() }?.let { lang ->
                    format.setString(MediaFormat.KEY_LANGUAGE, lang)
                }
                muxer.addTrack(format)
            }

            // Embedded audio tracks (harvested from the video.ts when no
            // separate audio files were provided).
            val embeddedAudioOutTracks = embeddedAudioTrackIndices.map { idx ->
                muxer.addTrack(videoExtractor.getTrackFormat(idx))
            }

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            // Video + embedded audio samples are interleaved by the extractor
            // (MediaExtractor advances through samples of all selected tracks
            // in presentation order). We route each sample to its destination
            // track based on which track the extractor is currently at.
            while (true) {
                buffer.clear()
                val size = videoExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.flags = videoExtractor.sampleFlags
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                val currentTrack = videoExtractor.sampleTrackIndex
                when (currentTrack) {
                    videoTrackIndex -> muxer.writeSampleData(videoOutTrack, buffer, bufferInfo)
                    else -> {
                        val embeddedPos = embeddedAudioTrackIndices.indexOf(currentTrack)
                        if (embeddedPos >= 0) {
                            muxer.writeSampleData(embeddedAudioOutTracks[embeddedPos], buffer, bufferInfo)
                        }
                        // Samples from any other (unselected) track are dropped.
                    }
                }
                videoExtractor.advance()
            }

            // External audio samples (each track sequentially).
            audioExtractors.forEachIndexed { idx, ext ->
                val outTrack = externalAudioOutTracks[idx]
                while (true) {
                    buffer.clear()
                    val size = ext.readSampleData(buffer, 0)
                    if (size < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = size
                    bufferInfo.flags = ext.sampleFlags
                    bufferInfo.presentationTimeUs = ext.sampleTime
                    muxer.writeSampleData(outTrack, buffer, bufferInfo)
                    ext.advance()
                }
            }

            muxer.stop()
            muxer.release()
        } finally {
            videoExtractor.release()
            audioExtractors.forEach { runCatching { it.release() } }
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
