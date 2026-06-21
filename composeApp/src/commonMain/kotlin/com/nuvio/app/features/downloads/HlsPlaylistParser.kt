package com.nuvio.app.features.downloads

data class HlsVariant(
    val bandwidth: Long,
    val resolution: String? = null,
    val codecs: String? = null,
    val url: String,
)

data class HlsMediaTrack(
    val type: String,
    val groupId: String,
    val name: String,
    val language: String? = null,
    val uri: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
)

data class HlsMasterPlaylist(
    val variants: List<HlsVariant>,
    val audioTracks: List<HlsMediaTrack>,
    val subtitleTracks: List<HlsMediaTrack>,
)

data class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val targetDuration: Double = 0.0,
    val isEncrypted: Boolean = false,
)

data class HlsSegment(
    val duration: Double,
    val url: String,
    val discontinuity: Boolean = false,
)

object HlsPlaylistParser {

    fun isHlsUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.endsWith(".m3u8") ||
            lower.contains(".m3u8?") ||
            lower.contains("/playlist/") ||
            lower.contains("/master/") ||
            lower.contains("/chunklist/")
    }

    fun isHlsStream(streamType: String?): Boolean =
        streamType?.trim().equals("hls", ignoreCase = true)

    fun isHlsContentType(contentType: String?): Boolean {
        val ct = contentType?.trim().orEmpty().lowercase()
        return ct.contains("vnd.apple.mpegurl") ||
            ct.contains("mpegurl") ||
            ct.contains("x-mpegurl")
    }

    fun parseMasterPlaylist(content: String, baseUrl: String): HlsMasterPlaylist {
        val lines = content.lines()
        val variants = mutableListOf<HlsVariant>()
        val audioTracks = mutableListOf<HlsMediaTrack>()
        val subtitleTracks = mutableListOf<HlsMediaTrack>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = parseAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))
                    val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
                    val resolution = attrs["RESOLUTION"]
                    val codecs = attrs["CODECS"]
                    i++
                    val urlLine = resolveUrl(lines.getOrNull(i)?.trim().orEmpty(), baseUrl)
                    if (urlLine.isNotBlank() && !urlLine.startsWith("#")) {
                        variants.add(HlsVariant(bandwidth, resolution, codecs, urlLine))
                    }
                }
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attrs = parseAttributes(line.removePrefix("#EXT-X-MEDIA:"))
                    val type = attrs["TYPE"]?.trim()?.uppercase() ?: ""
                    val groupId = attrs["GROUP-ID"]?.trim() ?: ""
                    val name = removeQuotes(attrs["NAME"]?.trim().orEmpty())
                    val language = attrs["LANGUAGE"]?.trim()?.let { removeQuotes(it) }
                    val uri = attrs["URI"]?.trim()?.let { resolveUrl(removeQuotes(it), baseUrl) }
                    val isDefault = attrs["DEFAULT"]?.trim()?.uppercase() == "YES"
                    val isForced = attrs["FORCED"]?.trim()?.uppercase() == "YES"

                    val track = HlsMediaTrack(
                        type = type,
                        groupId = groupId,
                        name = name,
                        language = language,
                        uri = uri,
                        isDefault = isDefault,
                        isForced = isForced,
                    )
                    when (type) {
                        "AUDIO" -> audioTracks.add(track)
                        "SUBTITLES" -> subtitleTracks.add(track)
                    }
                }
            }
            i++
        }

        return HlsMasterPlaylist(variants, audioTracks, subtitleTracks)
    }

    fun parseMediaPlaylist(content: String, baseUrl: String): HlsMediaPlaylist {
        val lines = content.lines()
        val segments = mutableListOf<HlsSegment>()
        var targetDuration = 0.0
        var currentDuration = 0.0
        var pendingDiscontinuity = false
        var isEncrypted = false

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetDuration = trimmed.substringAfter(":").trim().toDoubleOrNull() ?: 0.0
                }
                trimmed.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(trimmed.removePrefix("#EXT-X-KEY:"))
                    val method = attrs["METHOD"]?.trim()?.uppercase() ?: ""
                    if (method != "NONE") isEncrypted = true
                }
                trimmed.startsWith("#EXT-X-DISCONTINUITY") && !trimmed.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> {
                    pendingDiscontinuity = true
                }
                trimmed.startsWith("#EXTINF:") -> {
                    val durationStr = trimmed.substringAfter(":").substringBefore(",").trim()
                    currentDuration = durationStr.toDoubleOrNull() ?: 0.0
                }
                !trimmed.startsWith("#") && trimmed.isNotBlank() -> {
                    val segmentUrl = resolveUrl(trimmed, baseUrl)
                    segments.add(
                        HlsSegment(
                            duration = currentDuration,
                            url = segmentUrl,
                            discontinuity = pendingDiscontinuity,
                        ),
                    )
                    currentDuration = 0.0
                    pendingDiscontinuity = false
                }
            }
        }

        return HlsMediaPlaylist(segments, targetDuration, isEncrypted)
    }

    /**
     * Concatena segmenti WebVTT (tipicamente da playlist HLS subtitle) in un singolo
     * documento WebVTT coerente, ricalcolando i timestamp cumulative.
     *
     * Restituisce il testo WebVTT completo, pronto da salvare come `.vtt` sidecar.
     */
    fun concatWebVttSegments(
        segmentContents: List<String>,
        segmentDurationsSec: List<Double>,
    ): String {
        val sb = StringBuilder()
        sb.append("WEBVTT\n\n")
        var timeOffsetMs = 0L

        for ((index, content) in segmentContents.withIndex()) {
            val segDurationMs = ((segmentDurationsSec.getOrNull(index) ?: 0.0) * 1000.0).toLong()
            val cueBlocks = content
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim()
                .removePrefix("WEBVTT")
                .trimStart('\n')
                .split("\n\n")
                .filter { it.isNotBlank() }

            for (block in cueBlocks) {
                val lines = block.lines().toMutableList()
                // Trova la riga del timing
                val timingIdx = lines.indexOfFirst { line ->
                    Regex("""^\s*\d{2}:\d{2}:\d{2}\.\d{3}\s*-->""").containsMatchIn(line) ||
                        Regex("""^\s*\d{2}:\d{2}\.\d{3}\s*-->""").containsMatchIn(line)
                }
                if (timingIdx == -1) continue

                val timingLine = lines[timingIdx].trim()
                val (startStr, rest) = timingLine.substringBefore(" --> ").let { it to timingLine.substringAfter(" --> ") }
                val endStr = rest.substringBefore(" ").trim()

                val startMs = parseVttTimestamp(startStr)
                val endMs = parseVttTimestamp(endStr)
                if (startMs == null || endMs == null) continue

                val newStart = startMs + timeOffsetMs
                val newEnd = endMs + timeOffsetMs
                lines[timingIdx] = "${formatVttTimestamp(newStart)} --> ${formatVttTimestamp(newEnd)}"

                sb.append(lines.joinToString("\n"))
                sb.append("\n\n")
            }

            timeOffsetMs += segDurationMs
        }

        return sb.toString()
    }

    private fun parseVttTimestamp(value: String): Long? {
        val trimmed = value.trim()
        // HH:MM:SS.mmm oppure MM:SS.mmm
        val parts = trimmed.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val sParts = parts[2].split(".")
                    val s = sParts[0].toLong()
                    val ms = if (sParts.size > 1) sParts[1].padEnd(3, '0').take(3).toLong() else 0L
                    h * 3_600_000L + m * 60_000L + s * 1_000L + ms
                }
                2 -> {
                    val m = parts[0].toLong()
                    val sParts = parts[1].split(".")
                    val s = sParts[0].toLong()
                    val ms = if (sParts.size > 1) sParts[1].padEnd(3, '0').take(3).toLong() else 0L
                    m * 60_000L + s * 1_000L + ms
                }
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun formatVttTimestamp(ms: Long): String {
        val totalMs = ms.coerceAtLeast(0L)
        val hours = totalMs / 3_600_000L
        val minutes = (totalMs % 3_600_000L) / 60_000L
        val seconds = (totalMs % 60_000L) / 1_000L
        val millis = totalMs % 1_000L
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }

    private fun parseAttributes(input: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        var i = 0
        val len = input.length
        while (i < len) {
            while (i < len && input[i].isWhitespace()) i++
            if (i >= len) break
            val eqIdx = input.indexOf('=', i)
            if (eqIdx == -1) break
            val key = input.substring(i, eqIdx).trim()
            i = eqIdx + 1
            if (i >= len) break
            val value: String
            if (input[i] == '"') {
                i++
                val closeIdx = input.indexOf('"', i)
                if (closeIdx == -1) {
                    value = input.substring(i)
                    i = len
                } else {
                    value = input.substring(i, closeIdx)
                    i = closeIdx + 1
                }
            } else {
                val nextComma = input.indexOf(',', i)
                if (nextComma == -1) {
                    value = input.substring(i).trim()
                    i = len
                } else {
                    value = input.substring(i, nextComma).trim()
                    i = nextComma + 1
                }
            }
            if (key.isNotBlank()) {
                attrs[key] = value
            }
        }
        return attrs
    }

    private fun removeQuotes(value: String): String =
        value.trim().removeSurrounding("\"")

    fun resolveUrl(relative: String, baseUrl: String): String {
        val trimmed = relative.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.isBlank()) return trimmed
        val base = baseUrl.trimEnd('/')
        val basePath = if (base.contains('/')) {
            base.substringBeforeLast('/')
        } else {
            base
        }
        return "$basePath/$trimmed"
    }
}
