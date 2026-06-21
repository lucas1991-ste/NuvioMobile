package com.nuvio.app.features.downloads

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

internal class DesktopDownloadsTaskHandle(private val thread: Thread) : DownloadsTaskHandle {
    override fun cancel() {
        thread.interrupt()
    }
}

internal actual object DownloadsPlatformDownloader {
    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle {
        val t = thread(name = "download-${request.destinationFileName}", isDaemon = true) {
            try {
                val url = URL(request.sourceUrl)
                val conn = url.openConnection() as HttpURLConnection
                request.sourceHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.connect()
                val totalBytes = conn.contentLengthLong.let { if (it < 0) null else it }
                val destDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "nuvio-downloads")
                destDir.mkdirs()
                val destFile = File(destDir, request.destinationFileName)
                conn.inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloaded: Long = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (Thread.currentThread().isInterrupted) {
                                onFailure("Download cancelled")
                                return@thread
                            }
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            onProgress(downloaded, totalBytes)
                        }
                    }
                }
                onSuccess(destFile.toURI().toString(), totalBytes)
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    onFailure(e.message ?: "Unknown error")
                }
            }
        }
        return DesktopDownloadsTaskHandle(t)
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri == null) return false
        return runCatching { File(URI(localFileUri)).delete() }.getOrElse { false }
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val file = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "nuvio-downloads/$destinationFileName")
        return file.delete()
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        return localFileUri ?: let {
            val file = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "nuvio-downloads/$destinationFileName")
            if (file.exists()) file.toURI().toString() else null
        }
    }

    actual fun fetchUrlAsString(url: String, headers: Map<String, String>): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connect()
            conn.inputStream.reader().readText()
        } catch (_: Exception) {
            null
        }
    }

    actual fun probeHlsContentType(url: String, headers: Map<String, String>): Boolean = false

    actual fun downloadAndRemuxHls(
        request: HlsRemuxRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle {
        val t = thread(name = "hls-remux-${request.destinationFileName}", isDaemon = true) {
            try {
                remuxHlsOnDesktop(request, onProgress, onSuccess, onFailure)
            } catch (e: Throwable) {
                if (!Thread.currentThread().isInterrupted) {
                    onFailure(e.message ?: "Unknown error")
                }
            }
        }
        return DesktopDownloadsTaskHandle(t)
    }

    private fun remuxHlsOnDesktop(
        request: HlsRemuxRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ) {
        // 0. Resolve ffmpeg
        val ffmpegPath = resolveFfmpegPath() ?: run {
            onFailure("ffmpeg not found. Install ffmpeg and try again.")
            return
        }

        val destDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "nuvio-downloads").apply { mkdirs() }
        val workDir = File(destDir, "hls-remux-${System.nanoTime()}").apply { mkdirs() }

        try {
            // 1. Fetch + parse video playlist
            val videoPlaylistContent = fetchUrlAsString(request.video.playlistUrl, request.sourceHeaders)
                ?: error("Failed to fetch video playlist")
            val videoPlaylist = HlsPlaylistParser.parseMediaPlaylist(
                videoPlaylistContent, request.video.playlistUrl,
            )
            if (videoPlaylist.segments.isEmpty()) error("Empty video playlist")
            validateEncryptionDesktop(videoPlaylist.encryption)

            // 2. Fetch + parse audio playlists
            // Skip tracks with an empty playlist URL — they are embedded in the
            // video stream (no separate playlist to fetch).
            val audioPlaylists = request.audioTracks
                .filter { it.playlistUrl.isNotBlank() }
                .map { track ->
                    val content = fetchUrlAsString(track.playlistUrl, request.sourceHeaders)
                        ?: error("Failed to fetch audio playlist: ${track.name}")
                    val pl = HlsPlaylistParser.parseMediaPlaylist(content, track.playlistUrl)
                    if (pl.segments.isEmpty()) error("Empty audio playlist: ${track.name}")
                    validateEncryptionDesktop(pl.encryption)
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
                    validateEncryptionDesktop(pl.encryption)
                    TrackPlaylist(track, pl)
                }

            // 4. Download segments
            var totalDownloaded = 0L

            // For fMP4-based HLS, download the init segment (ftyp+moov) and
            // prepend it to the concatenated media segments so ffmpeg can parse
            // the file correctly.
            val videoInitFile = videoPlaylist.initSegmentUri?.let { initUri ->
                val f = File(workDir, "video_init.mp4")
                fetchInitSegmentDesktop(initUri, request.sourceHeaders, f)
                f
            }
            val videoTs = File(workDir, if (videoInitFile != null) "video.mp4" else "video.ts")
            downloadSegmentsToTsFileDesktop(
                segmentUrls = videoPlaylist.segments.map { it.url },
                headers = request.sourceHeaders,
                outFile = videoTs,
                encryption = videoPlaylist.encryption,
                mediaSequenceStart = videoPlaylist.mediaSequence,
                onChunk = { bytes ->
                    totalDownloaded += bytes
                    onProgress(totalDownloaded, null)
                },
            )

            val audioTsFiles = audioPlaylists.mapIndexed { index, tp ->
                val f = File(workDir, "audio_$index.ts")
                downloadSegmentsToTsFileDesktop(
                    segmentUrls = tp.playlist.segments.map { it.url },
                    headers = request.sourceHeaders,
                    outFile = f,
                    encryption = tp.playlist.encryption,
                    mediaSequenceStart = tp.playlist.mediaSequence,
                    onChunk = { bytes ->
                        totalDownloaded += bytes
                        onProgress(totalDownloaded, null)
                    },
                )
                f
            }

            val subtitleVttFiles = subtitlePlaylists.mapIndexed { index, tp ->
                val f = File(workDir, "subtitle_$index.vtt")
                downloadAndConcatVttSegmentsDesktop(
                    segments = tp.playlist.segments,
                    headers = request.sourceHeaders,
                    outFile = f,
                    onChunk = { bytes ->
                        totalDownloaded += bytes
                        onProgress(totalDownloaded, null)
                    },
                )
                f
            }

            // 5. For fMP4 streams, prepend init segment to the media segments.
            if (videoInitFile != null && videoInitFile.exists()) {
                val combinedVideo = File(workDir, "video_combined.mp4")
                videoInitFile.inputStream().use { initInput ->
                    combinedVideo.outputStream().use { combinedOutput ->
                        initInput.copyTo(combinedOutput)
                        videoTs.inputStream().use { mediaInput ->
                            mediaInput.copyTo(combinedOutput)
                        }
                    }
                }
                videoTs.delete()
                combinedVideo.renameTo(videoTs)
            }

            // 6. Build ffmpeg command:
            //    ffmpeg -i video.ts -i audio_0.ts -i audio_1.ts -i sub_0.vtt \
            //           -map 0:v -map 1:a -map 2:a -map 3:s \
            //           -c copy -c:s mov_text \
            //           -metadata:s:s:0 language=ita \
            //           -metadata:s:a:0 language=ita -metadata:s:a:1 language=eng \
            //           output.mp4
            val cmd = mutableListOf(ffmpegPath, "-y")

            // Inputs (video first, then audio, then subtitles)
            cmd += "-i"
            cmd += videoTs.absolutePath
            audioTsFiles.forEach { f ->
                cmd += "-i"
                cmd += f.absolutePath
            }
            subtitleVttFiles.forEach { f ->
                cmd += "-i"
                cmd += f.absolutePath
            }

            // Map video from input 0
            cmd += "-map"
            cmd += "0:v"

            if (audioTsFiles.isEmpty()) {
                // No separate audio tracks: the audio is multiplexed inside
                // the video .ts. Map all audio tracks from input 0 so ffmpeg
                // includes them in the output MP4.
                cmd += "-map"
                cmd += "0:a?"
            } else {
                // Map audio (one per input audio file)
                audioTsFiles.forEachIndexed { idx, _ ->
                    cmd += "-map"
                    cmd += "${idx + 1}:a"
                }
            }

            // Map subtitles (one per subtitle file)
            val subtitleInputOffset = 1 + audioTsFiles.size
            subtitleVttFiles.forEachIndexed { idx, _ ->
                cmd += "-map"
                cmd += "${subtitleInputOffset + idx}:s"
            }

            // Copy video + audio codecs, convert subs to mov_text (MP4-compatible)
            cmd += "-c"
            cmd += "copy"
            cmd += "-c:s"
            cmd += "mov_text"

            // Per-track language metadata
            audioPlaylists.forEachIndexed { idx, tp ->
                tp.track.language?.takeIf { it.isNotBlank() }?.let { lang ->
                    cmd += "-metadata:s:a:$idx"
                    cmd += "language=$lang"
                }
            }
            subtitlePlaylists.forEachIndexed { idx, tp ->
                tp.track.language?.takeIf { it.isNotBlank() }?.let { lang ->
                    cmd += "-metadata:s:s:$idx"
                    cmd += "language=$lang"
                }
            }

            val outputMp4 = File(destDir, request.destinationFileName)
            if (outputMp4.exists()) outputMp4.delete()
            cmd += outputMp4.absolutePath

            // 7. Run ffmpeg
            val processBuilder = ProcessBuilder(cmd).redirectErrorStream(true)
            val process = processBuilder.start()

            val outputLog = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    outputLog.appendLine(line)
                    // Optional: parse ffmpeg progress here
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                error("ffmpeg failed (exit=$exitCode): ${outputLog.takeLast(2000)}")
            }

            // 8. Cleanup
            workDir.deleteRecursively()

            val finalSize = outputMp4.length()
            onSuccess(outputMp4.toURI().toString(), finalSize)
        } catch (error: Throwable) {
            runCatching { workDir.deleteRecursively() }
            onFailure(error.message ?: "HLS remux failed")
        }
    }

    private data class TrackPlaylist(
        val track: HlsRemuxTrack,
        val playlist: HlsMediaPlaylist,
    )

    /**
     * Download the HLS Initialization Segment (#EXT-X-MAP) for fMP4 streams.
     * The init segment contains ftyp+moov boxes and must be prepended to the
     * concatenated media segments for ffmpeg to parse them.
     */
    private fun fetchInitSegmentDesktop(
        initUri: String,
        headers: Map<String, String>,
        outFile: File,
    ) {
        val conn = URL(initUri).openConnection() as HttpURLConnection
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connect()
        try {
            if (conn.responseCode !in 200..299) {
                error("Failed to fetch init segment: HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun validateEncryptionDesktop(encryption: HlsEncryption?) {
        if (encryption == null) return
        if (encryption.isAes128Decryptable) return
        if (encryption.isProprietaryDrm) {
            error("Cannot download: stream uses a proprietary DRM system (FairPlay / Widevine / PlayReady)")
        }
        error("Cannot download: stream is encrypted with an unsupported method")
    }

    private fun fetchKeyDesktop(
        encryption: HlsEncryption,
        headers: Map<String, String>,
    ): ByteArray {
        val uri = encryption.keyUri ?: error("AES-128 encryption with no URI")
        if (uri.lowercase().startsWith("data:")) {
            return HlsPlaylistParser.decodeDataUri(uri)
                ?: error("Failed to decode data: key URI")
        }
        val conn = URL(uri).openConnection() as HttpURLConnection
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connect()
        try {
            if (conn.responseCode !in 200..299) {
                error("HTTP ${conn.responseCode} for AES-128 key $uri")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size != 16) {
                error("AES-128 key must be 16 bytes, got ${bytes.size}")
            }
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun decryptSegmentDesktop(
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

    private fun downloadSegmentsToTsFileDesktop(
        segmentUrls: List<String>,
        headers: Map<String, String>,
        outFile: File,
        encryption: HlsEncryption?,
        mediaSequenceStart: Long,
        onChunk: (bytesDelta: Long) -> Unit,
    ) {
        val aesKey: ByteArray? = if (encryption?.isAes128Decryptable == true) {
            fetchKeyDesktop(encryption, headers)
        } else {
            null
        }
        outFile.outputStream().use { output ->
            segmentUrls.forEachIndexed { index, segmentUrl ->
                if (Thread.currentThread().isInterrupted) error("Download cancelled")
                val url = URL(segmentUrl)
                val conn = url.openConnection() as HttpURLConnection
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.connect()
                try {
                    if (conn.responseCode !in 200..299) {
                        error("HTTP ${conn.responseCode} for segment $segmentUrl")
                    }
                    val ciphertext = conn.inputStream.use { it.readBytes() }
                    onChunk(ciphertext.size.toLong())
                    val plaintext = if (aesKey != null && encryption != null) {
                        val iv = encryption.iv ?: HlsPlaylistParser.deriveIvFromSequence(
                            mediaSequenceStart + index,
                        )
                        decryptSegmentDesktop(ciphertext, aesKey, iv)
                    } else {
                        ciphertext
                    }
                    output.write(plaintext)
                } finally {
                    conn.disconnect()
                }
            }
            output.flush()
        }
    }

    private fun downloadAndConcatVttSegmentsDesktop(
        segments: List<HlsSegment>,
        headers: Map<String, String>,
        outFile: File,
        onChunk: (bytesDelta: Long) -> Unit,
    ) {
        val segmentContents = mutableListOf<String>()
        val segmentDurations = mutableListOf<Double>()

        for (segment in segments) {
            if (Thread.currentThread().isInterrupted) error("Download cancelled")
            val url = URL(segment.url)
            val conn = url.openConnection() as HttpURLConnection
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connect()
            try {
                if (conn.responseCode !in 200..299) {
                    error("HTTP ${conn.responseCode} for subtitle segment ${segment.url}")
                }
                val text = conn.inputStream.bufferedReader().readText()
                segmentContents.add(text)
                segmentDurations.add(segment.duration)
                onChunk(text.length.toLong())
            } finally {
                conn.disconnect()
            }
        }

        val merged = HlsPlaylistParser.concatWebVttSegments(segmentContents, segmentDurations)
        outFile.writeText(merged)
    }

    /**
     * Cerca ffmpeg nel PATH. Su Linux/Mac si affida al sistema; su Windows cerca anche
     * in alcune directory comuni.
     */
    private fun resolveFfmpegPath(): String? {
        val osName = System.getProperty("os.name").lowercase()
        val isWindows = osName.contains("windows")
        val executableName = if (isWindows) "ffmpeg.exe" else "ffmpeg"

        // 1. Cerca nel PATH di sistema
        val pathEnv = System.getenv("PATH") ?: ""
        val pathSeparator = if (isWindows) ";" else ":"
        for (dir in pathEnv.split(pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = File(dir, executableName)
            if (candidate.exists() && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }

        // 2. Su Windows, controlla directory comuni
        if (isWindows) {
            val candidates = listOf(
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files (x86)\\ffmpeg\\bin\\ffmpeg.exe",
            )
            for (candidate in candidates) {
                val f = File(candidate)
                if (f.exists() && f.canExecute()) return f.absolutePath
            }
        }

        return null
    }
}
