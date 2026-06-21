package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val sourceUrl: String,
    val sourceHeaders: Map<String, String>,
    val destinationFileName: String,
)

internal interface DownloadsTaskHandle {
    fun cancel()
}

internal data class HlsRemuxTrack(
    val playlistUrl: String,
    val name: String,
    val language: String?,
    val kind: HlsRemuxTrackKind,
)

internal enum class HlsRemuxTrackKind { VIDEO, AUDIO, SUBTITLE }

internal data class HlsRemuxRequest(
    val video: HlsRemuxTrack,
    val audioTracks: List<HlsRemuxTrack>,
    val subtitleTracks: List<HlsRemuxTrack>,
    val sourceHeaders: Map<String, String>,
    val destinationFileName: String,
)

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun fetchUrlAsString(url: String, headers: Map<String, String>): String?

    fun probeHlsContentType(url: String, headers: Map<String, String>): Boolean

    /**
     * Scarica tutte le playlist HLS (video + tracce audio + tracce sottotitolo), esegue il
     * remux in un singolo file MP4 contenente tutte le tracce, e restituisce il path locale.
     *
     * Su Desktop i sottotitoli vengono incorporati nel MP4 (mov_text).
     * Su Android i sottotitoli vengono salvati come file sidecar `.vtt` accanto al MP4
     * (limitazione di MediaMuxer).
     */
    fun downloadAndRemuxHls(
        request: HlsRemuxRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle
}
