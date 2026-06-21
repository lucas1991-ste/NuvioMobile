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
                if (videoPlaylist.isEncrypted) {
                    error(runBlocking { getString(Res.string.downloads_error_hls_encrypted) })
                }

                // 2. Fetch + parse audio playlists
                val audioPlaylists = request.audioTracks.map { track ->
                    val content = fetchUrlAsString(track.playlistUrl, request.sourceHeaders)
                        ?: error("Failed to fetch audio playlist: ${track.name}")
                    val pl = HlsPlaylistParser.parseMediaPlaylist(content, track.playlistUrl)
                    if (pl.segments.isEmpty()) error("Empty audio playlist: ${track.name}")
                    if (pl.isEncrypted) error(runBlocking { getString(Res.string.downloads_error_hls_encrypted) })
                    TrackPlaylist(track, pl)
                }

                // 3. Fetch + parse subtitle playlists
                val subtitlePlaylists = request.subtitleTracks.map { track ->
                    val content = fetchUrlAsString(track.playlistUrl, request.sourceHeaders)
                        ?: error("Failed to fetch subtitle playlist: ${track.name}")
                    val pl = HlsPlaylistParser.parseMediaPlaylist(content, track.playlistUrl)
                    if (pl.segments.isEmpty()) error("Empty subtitle playlist: ${track.name}")
                    TrackPlaylist(track, pl)
                }

                // 4. Download all segments into temp .ts files (and .vtt for subtitles)
                var totalDownloaded = 0L
                val videoTsFile = File(workDir, "video.ts")
                downloadSegmentsToTsFile(
                    segmentUrls = videoPlaylist.segments.map { it.url },
                    headers = request.sourceHeaders,
                    outFile = videoTsFile,
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

                // 5. Remux video + audio into MP4 via MediaMuxer
                val tempMp4 = File(workDir, "output.mp4")
                remuxToMp4(
                    videoTs = videoTsFile,
                    audioTsFiles = audioTsFiles,
                    audioTracks = audioPlaylists.map { it.track },
                    outputMp4 = tempMp4,
                )

                // 6. Copy MP4 to destination
                if (destination.exists()) destination.delete()
                tempMp4.inputStream().use { input ->
                    destination.openOutputStream(false).use { output ->
                        input.copyTo(output)
                    }
                }

                // 7. Copy subtitle sidecar files (.vtt) next to the MP4
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

                // 8. Cleanup temp dir
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

    private fun downloadSegmentsToTsFile(
        segmentUrls: List<String>,
        headers: Map<String, String>,
        outFile: File,
        onChunk: (bytesDelta: Long) -> Unit,
        job: Job,
    ) {
        outFile.outputStream().use { output ->
            for (segmentUrl in segmentUrls) {
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
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            job.ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            onChunk(read.toLong())
                        }
                    }
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
    ) {
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoTs.absolutePath)

        var videoTrackIndex = -1
        for (i in 0 until videoExtractor.trackCount) {
            val format = videoExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                videoExtractor.selectTrack(i)
                break
            }
        }
        if (videoTrackIndex == -1) {
            videoExtractor.release()
            error("No video track found in video playlist")
        }

        val audioExtractors = mutableListOf<MediaExtractor>()
        val audioTrackIndices = mutableListOf<Int>()
        try {
            for (audioTs in audioTsFiles) {
                val ext = MediaExtractor()
                ext.setDataSource(audioTs.absolutePath)
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
                    error("No audio track found in ${audioTs.name}")
                }
                audioExtractors.add(ext)
                audioTrackIndices.add(foundIdx)
            }

            val muxer = MediaMuxer(
                outputMp4.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )

            val videoOutTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))
            val audioOutTracks = audioExtractors.mapIndexed { idx, ext ->
                val format = ext.getTrackFormat(audioTrackIndices[idx])
                audioTracks.getOrNull(idx)?.language?.takeIf { it.isNotBlank() }?.let { lang ->
                    format.setString(MediaFormat.KEY_LANGUAGE, lang)
                }
                muxer.addTrack(format)
            }

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            // Video samples
            while (true) {
                buffer.clear()
                val size = videoExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.flags = videoExtractor.sampleFlags
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                muxer.writeSampleData(videoOutTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // Audio samples (each track sequentially)
            audioExtractors.forEachIndexed { idx, ext ->
                val outTrack = audioOutTracks[idx]
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
}

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
