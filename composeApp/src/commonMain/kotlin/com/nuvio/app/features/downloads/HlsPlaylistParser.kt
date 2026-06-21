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
    val encryption: HlsEncryption? = null,
    val mediaSequence: Long = 0L,
    /**
     * The URI of the Initialization Segment (from `#EXT-X-MAP`).
     *
     * Present in fMP4-based HLS streams. The init segment contains the `ftyp`
     * and `moov` boxes that describe the track structure, and MUST be prepended
     * to the concatenated media segments for `MediaExtractor` / `ffmpeg` to
     * parse the result. When null, the stream uses MPEG-TS segments that are
     * self-describing and can be concatenated directly.
     */
    val initSegmentUri: String? = null,
)

/**
 * Encryption metadata extracted from #EXT-X-KEY.
 *
 * - [method]: NONE | AES-128 | SAMPLE-AES | SAMPLE-AES-CTR | ...
 * - [keyUri]: the URI= attribute (may be HTTP/HTTPS for AES-128, or a custom scheme
 *   like skd://, data:, widevine:, playready: for DRM systems).
 * - [iv]: the IV= attribute parsed as 16 raw bytes, or null when not present
 *   (HLS spec mandates the client derive it from the segment's MEDIA-SEQUENCE
 *   number, as a 128-bit big-endian integer).
 * - [keyFormat]: optional KEYFORMAT= attribute (e.g.
 *   "com.apple.streamingkeydelivery" for FairPlay,
 *   "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" for Widevine,
 *   "com.microsoft.playready" for PlayReady).
 * - [keyFormatVersions]: optional KEYFORMATVERSIONS= attribute.
 */
data class HlsEncryption(
    val method: String,
    val keyUri: String?,
    val iv: ByteArray?,
    val keyFormat: String?,
    val keyFormatVersions: String?,
) {
    /**
     * True when this encryption can be decrypted client-side by the app's own
     * AES-128 implementation (METHOD=AES-128 with HTTP/HTTPS or data: URI,
     * no proprietary KEYFORMAT).
     */
    val isAes128Decryptable: Boolean
        get() {
            if (!method.equals("AES-128", ignoreCase = true)) return false
            val uri = keyUri?.trim() ?: return false
            val lower = uri.lowercase()
            // data: URIs embed the key inline, http(s) can be fetched with headers.
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                // Refuse any explicit DRM keyformat even on http(s) URIs.
                return !isProprietaryKeyFormat
            }
            if (lower.startsWith("data:")) return !isProprietaryKeyFormat
            return false
        }

    /**
     * True when this encryption uses a proprietary DRM system that cannot be
     * decrypted without the OS-level DRM framework (FairPlay / Widevine /
     * PlayReady). The app's downloader cannot save these streams.
     */
    val isProprietaryDrm: Boolean
        get() {
            val methodUpper = method.uppercase()
            if (methodUpper.startsWith("SAMPLE-AES")) return true
            return isProprietaryKeyFormat
        }

    private val isProprietaryKeyFormat: Boolean
        get() {
            val kf = keyFormat?.trim()?.lowercase() ?: return false
            // Known DRM keyformats per HLS spec & community conventions.
            return when {
                kf.contains("streamingkeydelivery") -> true // FairPlay
                kf.contains("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed") -> true // Widevine UUID
                kf.contains("widevine") -> true
                kf.contains("playready") -> true // PlayReady
                kf.contains("com.microsoft") -> true
                else -> false
            }
        }
}

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

    /**
     * Sniff the format of an HLS media segment by looking at its first bytes.
     *
     * Returns one of:
     *   - "ts"     -> MPEG-TS (sync byte 0x47 at offset 0, repeated every 188 bytes)
     *   - "fmp4"   -> fragmented MP4 / CMAF (ISO BMFF box at offset 0: "styp", "moof", "mdat", "ftyp", "moov", "emsg", "sidx")
     *   - "html"   -> looks like an HTML page (server returned an error/wall)
     *   - "unknown"-> cannot determine
     *
     * Used by the remux layer to validate that what was downloaded is actually
     * media data (and not, say, an HTML paywall) and to detect fMP4 segments
     * that were downloaded without their #EXT-X-MAP init segment.
     *
     * Public and platform-agnostic so both Android (MediaExtractor) and Desktop
     * (ffmpeg) can share the same sniffing logic.
     */
    fun sniffSegmentFormat(headerBytes: ByteArray): String {
        if (headerBytes.size < 4) return "unknown"

        // === MPEG-TS detection ===
        // Sync byte 0x47 at offset 0, and again at offset 188 (and 376, ...).
        if (headerBytes[0] == 0x47.toByte()) {
            if (headerBytes.size >= 189 && headerBytes[188] == 0x47.toByte()) return "ts"
            // Some init segments or pure-TS files: just one sync byte is a strong hint
            // when followed by well-formed TS payload start.
            return "ts"
        }

        // === HTML detection (server returned an error/wall as 200 OK) ===
        // Typical signatures: "<!DOCTYPE", "<html", "<?xml", "<HTML"
        if (headerBytes.size >= 5) {
            val asText = runCatching {
                String(headerBytes, 0, minOf(64, headerBytes.size), Charsets.US_ASCII)
            }.getOrDefault("")
            val trimmed = asText.trimStart()
            val lower = trimmed.lowercase()
            if (lower.startsWith("<!doctype") ||
                lower.startsWith("<html") ||
                lower.startsWith("<?xml") ||
                lower.startsWith("<head")
            ) {
                return "html"
            }
        }

        // === fMP4 / CMAF detection (ISO BMFF) ===
        // Box layout: [4 bytes size][4 bytes type][payload...]
        // Common first boxes for HLS fMP4 segments: styp, moof, mdat, ftyp, moov, emsg, sidx
        if (headerBytes.size >= 8) {
            val boxType = runCatching {
                String(headerBytes, 4, 4, Charsets.US_ASCII)
            }.getOrDefault("")
            if (boxType in setOf("styp", "moof", "mdat", "ftyp", "moov", "emsg", "sidx", "free", "skip")) {
                return "fmp4"
            }
        }

        return "unknown"
    }

    /**
     * Deep-scan a downloaded segment file's first [headerBytes] looking for
     * known format signatures at ALL offsets, not just offset 0/4. This is
     * the fallback when [sniffSegmentFormat] returns "unknown" — we scan the
     * whole 4KB window for tell-tale patterns that indicate the actual format.
     *
     * Returns a human-readable diagnostic string suitable for inclusion in an
     * error message. Examples:
     *   - "MPEG-TS sync byte 0x47 found at offset 0 (188-byte packet boundary OK)"
     *   - "MPEG-TS sync byte 0x47 found at offset 4 (likely M2TS / 192-byte packets)"
     *   - "H.264 Annex B start code 00 00 00 01 found at offset 0"
     *   - "ISO BMFF box 'moof' found at offset 0 (fMP4)"
     *   - "ISO BMFF box 'ftyp' found at offset 0 (MP4)"
     *   - "String 'FFmpeg' found at offset 24 (file was muxed by FFmpeg)"
     *   - "String '#EXTM3U' found at offset 0 (PLAYLIST DOWNLOADED AS SEGMENT — this is a bug in the variant URL selection)"
     *   - "High entropy (7.92 bits/byte) — file may be encrypted ciphertext"
     *   - "No known format signatures found in first 4KB"
     *
     * Multiple findings are joined with "; ".
     */
    fun deepScanFormat(headerBytes: ByteArray): String {
        if (headerBytes.isEmpty()) return "empty input"

        val findings = mutableListOf<String>()

        // === 1. MPEG-TS sync byte scan ===
        // Look for 0x47 at every position; if found, check whether 0x47 also
        // appears 188 bytes later (confirming 188-byte TS packet framing).
        val tsSyncPositions = mutableListOf<Int>()
        for (i in headerBytes.indices) {
            if (headerBytes[i] == 0x47.toByte()) {
                tsSyncPositions.add(i)
                if (tsSyncPositions.size >= 4) break // first 4 hits are enough
            }
        }
        if (tsSyncPositions.isNotEmpty()) {
            val first = tsSyncPositions.first()
            val periodic = tsSyncPositions.any { it == first + 188 } ||
                tsSyncPositions.any { it == first + 192 } // M2TS
            val packetSize = if (tsSyncPositions.any { it == first + 192 }) 192 else 188
            findings.add(
                if (periodic) {
                    "MPEG-TS sync byte 0x47 at offset $first, periodic at ${packetSize}-byte boundary (valid MPEG-TS)"
                } else {
                    "MPEG-TS sync byte 0x47 at offset $first but NOT periodic (possibly corrupted or partial)"
                },
            )
        }

        // === 2. H.264 / H.265 Annex B start codes ===
        // 3-byte: 00 00 01   /   4-byte: 00 00 00 01
        for (i in 0..headerBytes.size - 4) {
            if (headerBytes[i] == 0x00.toByte() &&
                headerBytes[i + 1] == 0x00.toByte() &&
                headerBytes[i + 2] == 0x00.toByte() &&
                headerBytes[i + 3] == 0x01.toByte()
            ) {
                val nalType = if (i + 4 < headerBytes.size) {
                    (headerBytes[i + 4].toInt() and 0x7E) ushr 1 // H.264 NAL type
                } else -1
                findings.add(
                    "H.264/H.265 Annex B start code 00 00 00 01 at offset $i" +
                        if (nalType >= 0) " (NAL type $nalType)" else "",
                )
                break
            }
            if (headerBytes[i] == 0x00.toByte() &&
                headerBytes[i + 1] == 0x00.toByte() &&
                headerBytes[i + 2] == 0x01.toByte()
            ) {
                findings.add("3-byte start code 00 00 01 at offset $i (MPEG-PS / H.264 Annex B)")
                break
            }
        }

        // === 3. ISO BMFF box types (fMP4 / MP4) ===
        val boxTypes = listOf("ftyp", "moov", "moof", "mdat", "styp", "emsg", "sidx", "free", "skip", "uuid", "udta")
        for (bt in boxTypes) {
            val btBytes = bt.toByteArray(Charsets.US_ASCII)
            for (i in 0..headerBytes.size - btBytes.size) {
                var match = true
                for (j in btBytes.indices) {
                    if (headerBytes[i + j] != btBytes[j]) { match = false; break }
                }
                if (match) {
                    findings.add("ISO BMFF box '$bt' at offset $i" + if (i == 0) "" else " (non-zero offset — unusual)")
                    break
                }
            }
        }

        // === 4. Playlist content (the dreaded "playlist downloaded as segment") ===
        val asText = runCatching {
            String(headerBytes, 0, minOf(256, headerBytes.size), Charsets.US_ASCII)
        }.getOrDefault("")
        if (asText.contains("#EXTM3U", ignoreCase = true)) {
            findings.add(
                "String '#EXTM3U' found in segment data — A PLAYLIST WAS DOWNLOADED AS A SEGMENT. " +
                    "This is a bug: the variant URL points to a master/sub-master playlist, not a media playlist. " +
                    "The plugin should walk the master playlist recursively until it reaches actual segment URLs.",
            )
        }

        // === 5. Encoder/muxer identifier strings ===
        for (needle in listOf("FFmpeg", "Lavf", "Lavc", "Lavfi", "x264", "x265", "handbrake")) {
            val needleBytes = needle.toByteArray(Charsets.US_ASCII)
            for (i in 0..headerBytes.size - needleBytes.size) {
                var match = true
                for (j in needleBytes.indices) {
                    if (headerBytes[i + j] != needleBytes[j]) { match = false; break }
                }
                if (match) {
                    findings.add("String '$needle' at offset $i (file was produced by $needle)")
                    break
                }
            }
        }

        // === 6. Entropy check (high entropy => likely encrypted) ===
        val entropy = computeEntropy(headerBytes)
        if (entropy > 7.5) {
            findings.add(
                "High entropy (%.2f bits/byte over first %d bytes) — file looks like encrypted ciphertext or compressed data. ".format(entropy, headerBytes.size) +
                    "If the playlist has no #EXT-X-KEY, the encryption may be applied at a layer above HLS (e.g. CDN token encryption).",
            )
        } else if (entropy < 4.0 && findings.isEmpty()) {
            findings.add("Low entropy (%.2f bits/byte) — file may be highly repetitive or zero-padded".format(entropy))
        }

        return if (findings.isEmpty()) {
            "No known format signatures found in first ${headerBytes.size} bytes"
        } else {
            findings.joinToString("; ")
        }
    }

    /**
     * Compute Shannon entropy (bits/byte) over [data]. Range: 0.0 (all same
     * byte) to 8.0 (uniformly random). Used to distinguish encrypted/compressed
     * data (typically >7.5) from plaintext media containers (typically <6.5).
     */
    private fun computeEntropy(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0
        val counts = IntArray(256)
        for (b in data) {
            counts[b.toInt() and 0xFF]++
        }
        val n = data.size.toDouble()
        var entropy = 0.0
        for (c in counts) {
            if (c == 0) continue
            val p = c / n
            entropy -= p * (Math.log(p) / Math.log(2.0))
        }
        return entropy
    }

    /**
     * Find the byte offset of the first valid MPEG-TS sync byte (0x47) in
     * [headerBytes] such that another 0x47 appears exactly 188 bytes later
     * (and possibly 376, 564, ...). This is what distinguishes a real MPEG-TS
     * sync byte from a coincidental 0x47 byte inside non-TS data.
     *
     * Returns:
     *   - 0    if the data starts with a valid TS sync (no header prefix)
     *   - N>0  if there is an N-byte non-TS prefix before the first TS packet
     *   - -1   if no valid TS sync position is found in the scanned window
     *
     * The scan window is [maxScan] bytes from the start of [headerBytes].
     *
     * Context: some CDNs (notably the one used by StreamingCommunity via the
     * Nuvio plugin) prepend a custom 188-byte header to every HLS segment.
     * The header contains a per-file hash, the producer identifier ("FFmpeg"),
     * the DVB service name ("Service01"), and 0xFF padding. MediaExtractor
     * (Android) and some ffmpeg builds cannot sync if the first TS packet is
     * not at offset 0, so we must strip this prefix from every segment before
     * concatenating them into the .ts file.
     */
    fun findTsSyncOffset(headerBytes: ByteArray, maxScan: Int = 1024): Int {
        if (headerBytes.size < 189) return -1
        val scanLimit = minOf(maxScan, headerBytes.size - 188)
        for (i in 0 until scanLimit) {
            if (headerBytes[i] != 0x47.toByte()) continue
            // Confirm by checking the next sync position (188 bytes later).
            if (headerBytes[i + 188] == 0x47.toByte()) {
                // Even stronger: check the third sync position if available.
                if (i + 376 < headerBytes.size) {
                    if (headerBytes[i + 376] == 0x47.toByte()) return i
                    // Two syncs is enough evidence; only fail on three if data is long enough.
                } else {
                    return i
                }
            }
        }
        return -1
    }

    /**
     * Convenience overload: sniff a segment format from the first [bytesToRead]
     * bytes of an [inputStream]. Closes nothing; the caller owns the stream.
     *
     * Implemented in commonMain via [sniffSegmentFormat] (ByteArray).
     */
    // (overloads with InputStream live on platform sides, where java.io is available)

    fun isHlsStream(streamType: String?): Boolean =
        streamType?.trim().equals("hls", ignoreCase = true)

    fun isHlsContentType(contentType: String?): Boolean {
        val ct = contentType?.trim().orEmpty().lowercase()
        return ct.contains("vnd.apple.mpegurl") ||
            ct.contains("mpegurl") ||
            ct.contains("x-mpegurl")
    }

    fun parseMasterPlaylist(content: String, baseUrl: String): HlsMasterPlaylist {
        val lines = stripBom(content).lines()
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
        val lines = stripBom(content).lines()

        // === Guard: detect master playlist being fed as media playlist ===
        // Some non-standard HLS sources (notably certain plugin-provided
        // StreamingCommunity URLs) expose a sub-master playlist URL where a
        // media playlist URL is expected. If we naively parse a sub-master
        // with parseMediaPlaylist(), every #EXT-X-STREAM-INF line is skipped
        // (starts with '#') and the variant URL on the next line is treated
        // as a media segment — which means we end up downloading .m3u8 files
        // AS IF they were video segments, producing a garbage .ts file that
        // MediaExtractor/ffmpeg cannot identify.
        //
        // Per HLS spec (RFC 8216 §4.3.4), a master playlist contains
        // #EXT-X-STREAM-INF and/or #EXT-X-MEDIA; a media playlist contains
        // #EXTINF and segment URIs. The two are mutually exclusive.
        val hasStreamInf = lines.any { it.trim().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        val hasExtInf = lines.any { it.trim().startsWith("#EXTINF", ignoreCase = true) }
        if (hasStreamInf && !hasExtInf) {
            error(
                "parseMediaPlaylist() was called on a master playlist (contains #EXT-X-STREAM-INF but no #EXTINF). " +
                    "The variant URL provided is a sub-master, not a media playlist. " +
                    "The HLS selection sheet should expose the actual media playlist URLs, " +
                    "or the parser should be called recursively until a media playlist is reached.",
            )
        }

        val segments = mutableListOf<HlsSegment>()
        var targetDuration = 0.0
        var currentDuration = 0.0
        var pendingDiscontinuity = false
        var currentEncryption: HlsEncryption? = null
        var mediaSequence = 0L
        var initSegmentUri: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            // === Case-insensitive tag matching ===
            // Per RFC 8216 §4.2, HLS tags are case-sensitive (#EXT-X-KEY, not
            // #ext-x-key). However, some non-standard packagers emit lowercase
            // or mixed-case tags. We accept them for robustness, since rejecting
            // a playlist because of case differences would silently fall back
            // to "no encryption detected" and produce a garbled output file.
            when {
                trimmed.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                    targetDuration = trimmed.substringAfter(":").trim().toDoubleOrNull() ?: 0.0
                }
                trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    mediaSequence = trimmed.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
                trimmed.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val attrs = parseAttributes(trimmed.substringAfter(":"))
                    val method = attrs["METHOD"]?.trim()?.uppercase() ?: ""
                    // METHOD=NONE explicitly disables encryption for the segments
                    // that follow, per HLS spec.
                    if (method == "NONE") {
                        currentEncryption = null
                    } else {
                        val rawUri = attrs["URI"]?.trim()?.let { removeQuotes(it) }
                        val resolvedUri = rawUri?.let { resolveUrl(it, baseUrl) }
                        // For data: URIs we keep the original (already inlined),
                        // not resolved relative to baseUrl.
                        val finalUri = rawUri?.takeIf { it.lowercase().startsWith("data:") } ?: resolvedUri
                        currentEncryption = HlsEncryption(
                            method = method,
                            keyUri = finalUri,
                            iv = attrs["IV"]?.trim()?.let { parseIv(it) },
                            keyFormat = attrs["KEYFORMAT"]?.trim()?.let { removeQuotes(it) },
                            keyFormatVersions = attrs["KEYFORMATVERSIONS"]?.trim()?.let { removeQuotes(it) },
                        )
                    }
                }
                trimmed.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    // #EXT-X-MAP:URI="init.mp4" or #EXT-X-MAP:URI="init.mp4",BYTERANGE="..."
                    // The init segment (ftyp+moov boxes) for fMP4-based HLS streams.
                    val mapAttrs = parseAttributes(trimmed.substringAfter(":"))
                    val rawMapUri = mapAttrs["URI"]?.trim()?.let { removeQuotes(it) }
                    if (rawMapUri != null) {
                        initSegmentUri = resolveUrl(rawMapUri, baseUrl)
                    }
                }
                trimmed.startsWith("#EXT-X-DISCONTINUITY", ignoreCase = true) &&
                    !trimmed.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE", ignoreCase = true) -> {
                    pendingDiscontinuity = true
                }
                trimmed.startsWith("#EXTINF:", ignoreCase = true) -> {
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

        val isEncrypted = currentEncryption != null
        return HlsMediaPlaylist(
            segments = segments,
            targetDuration = targetDuration,
            isEncrypted = isEncrypted,
            encryption = currentEncryption,
            mediaSequence = mediaSequence,
            initSegmentUri = initSegmentUri,
        )
    }

    /**
     * Parse an IV= attribute from #EXT-X-KEY.
     * Accepted formats (per HLS spec, section 4.3.2.4):
     *   - 0x followed by 32 hex chars (128-bit big-endian)
     *   - 32 hex chars without 0x prefix
     * Returns 16 raw bytes, or null if the input is malformed.
     */
    private fun parseIv(value: String): ByteArray? {
        val cleaned = value.trim().removePrefix("0x").removePrefix("0X")
        if (cleaned.length != 32) return null
        val lower = cleaned.lowercase()
        if (!lower.all { it.isDigit() || it in 'a'..'f' }) return null
        return lower.chunked(2) { byteStr ->
            byteStr.toString().toInt(16).toByte()
        }.toByteArray()
    }

    /**
     * Build the IV for a segment when #EXT-X-KEY did not specify one.
     * Per HLS spec (section 5.2): when IV is absent, the IV for segment N
     * (with MEDIA-SEQUENCE = N) is the 128-bit big-endian representation of N.
     */
    fun deriveIvFromSequence(sequenceNumber: Long): ByteArray {
        val iv = ByteArray(16)
        var v = sequenceNumber
        // Write as big-endian in the last 8 bytes (long). The first 8 bytes stay 0.
        for (i in 15 downTo 8) {
            iv[i] = (v and 0xFFL).toByte()
            v = v ushr 8
        }
        return iv
    }

    /**
     * Decode a `data:` URI payload (RFC 2397) into raw bytes.
     * Accepts both `data:<mediatype>;base64,<payload>` and `data:<mediatype>,<payload>`.
     * Returns null if the URI is malformed.
     *
     * Implemented in commonMain without java.util.Base64 to stay multiplatform-safe;
     * we use a small base64 decoder.
     */
    fun decodeDataUri(uri: String): ByteArray? {
        val trimmed = uri.trim()
        if (!trimmed.lowercase().startsWith("data:")) return null
        val commaIdx = trimmed.indexOf(',')
        if (commaIdx < 0) return null
        val header = trimmed.substring(5, commaIdx) // between "data:" and ","
        val payload = trimmed.substring(commaIdx + 1)
        val isBase64 = header.lowercase().contains("base64")
        return if (isBase64) {
            base64Decode(payload)
        } else {
            // Percent-decoded UTF-8 bytes; HLS keys rarely use this form but spec allows it.
            percentDecode(payload).toByteArray(Charsets.UTF_8)
        }
    }

    private fun base64Decode(input: String): ByteArray? {
        val cleaned = input.filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return ByteArray(0)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val table = IntArray(128) { -1 }
        alphabet.forEachIndexed { idx, c -> table[c.code] = idx }
        table['='.code] = 0 // padding placeholder
        if (cleaned.length % 4 != 0) return null
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < cleaned.length) {
            val a = table.getOrNull(cleaned[i].code) ?: return null
            val b = table.getOrNull(cleaned[i + 1].code) ?: return null
            val c = table.getOrNull(cleaned[i + 2].code) ?: return null
            val d = table.getOrNull(cleaned[i + 3].code) ?: return null
            val triple = (a shl 18) or (b shl 12) or (c shl 6) or d
            out.add(((triple ushr 16) and 0xFF).toByte())
            if (cleaned[i + 2] != '=') out.add(((triple ushr 8) and 0xFF).toByte())
            if (cleaned[i + 3] != '=') out.add((triple and 0xFF).toByte())
            i += 4
        }
        return out.toByteArray()
    }

    private fun percentDecode(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16) ?: run {
                    sb.append(c); i++; continue
                }
                sb.append(code.toChar())
                i += 3
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
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

    /**
     * Strip a leading UTF-8 BOM (EF BB BF) if present. Some HTTP servers and
     * CDNs emit HLS playlists with a BOM, which would otherwise break the
     * detection of the first directive (#EXTM3U or #EXT-X-VERSION).
     */
    private fun stripBom(content: String): String {
        if (content.isEmpty()) return content
        // BOM as it appears at the start of a Kotlin String read from UTF-8 bytes
        if (content.startsWith("\uFEFF")) return content.removePrefix("\uFEFF")
        return content
    }

    /**
     * Resolve a possibly-relative HLS URI against a base URL.
     *
     * Handles the common cases that real-world HLS deployments use:
     *   - Absolute http(s) URL -> returned as-is
     *   - Protocol-relative URL (//host/path) -> upgraded to https://
     *   - Root-relative URL (/path) -> joined with the base's scheme+host
     *   - Path-relative URL (path or ./path or ../path) -> joined with the
     *     base's directory
     *
     * Crucially, when the base URL carries a query string (typical of signed
     * CDN URLs like https://cdn.example.com/manifest/master.m3u8?token=abc&exp=123)
     * the query string is preserved and merged with the relative URI's own
     * query string (base keys first, then relative keys override).
     *
     * The fragment of the base URL is dropped (HLS does not use it).
     */
    fun resolveUrl(relative: String, baseUrl: String): String {
        val trimmed = relative.trim()
        if (trimmed.isEmpty()) return trimmed

        // Absolute URL: returned as-is, no merging needed.
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed

        // Protocol-relative URL: pick scheme from base.
        if (trimmed.startsWith("//")) {
            val scheme = if (baseUrl.startsWith("https://")) "https:" else "http:"
            return scheme + trimmed
        }

        // Parse the base URL into (scheme, host, path, query).
        val (baseScheme, baseRest) = if (baseUrl.startsWith("https://")) {
            "https://" to baseUrl.removePrefix("https://")
        } else if (baseUrl.startsWith("http://")) {
            "http://" to baseUrl.removePrefix("http://")
        } else {
            return trimmed
        }

        // Split authority+path from query at the first '?'
        val questionIdx = baseRest.indexOf('?')
        val baseAuthorityAndPath: String
        val baseQuery: String?
        if (questionIdx >= 0) {
            baseAuthorityAndPath = baseRest.substring(0, questionIdx)
            baseQuery = baseRest.substring(questionIdx + 1)
        } else {
            baseAuthorityAndPath = baseRest
            baseQuery = null
        }

        // Split authority from path at the first '/'.
        val slashIdx = baseAuthorityAndPath.indexOf('/')
        val authority: String
        val basePath: String
        if (slashIdx >= 0) {
            authority = baseAuthorityAndPath.substring(0, slashIdx)
            basePath = baseAuthorityAndPath.substring(slashIdx)
        } else {
            authority = baseAuthorityAndPath
            basePath = ""
        }

        // Split the relative URI into path + query.
        val (relPath, relQuery) = if ('?' in trimmed) {
            val idx = trimmed.indexOf('?')
            trimmed.substring(0, idx) to trimmed.substring(idx + 1)
        } else {
            trimmed to null
        }

        // Compute the new path.
        val newPath: String = when {
            relPath.startsWith("/") -> relPath // root-relative
            relPath.startsWith("../") -> {
                // Walk up one directory at a time from the base path.
                var work = basePath
                var pending = relPath
                while (pending.startsWith("../")) {
                    work = work.trimEnd('/').substringBeforeLast('/', "")
                    pending = pending.removePrefix("../")
                }
                work + "/" + pending
            }
            relPath.startsWith("./") -> {
                // "./foo" = "foo" in the same directory as the base file.
                val baseDir = basePath.substringBeforeLast('/', "")
                val rest = relPath.removePrefix("./")
                if (baseDir.isEmpty()) "/$rest" else "$baseDir/$rest"
            }
            else -> {
                // Path-relative: take base's directory (everything before last '/').
                val baseDir = if (basePath.contains('/')) basePath.substringBeforeLast('/') else ""
                if (baseDir.isEmpty()) "/$relPath" else "$baseDir/$relPath"
            }
        }

        // Merge query strings: base first, then relative overrides.
        val mergedQuery = mergeQuery(baseQuery, relQuery)
        val queryString = if (mergedQuery.isNotEmpty()) "?$mergedQuery" else ""

        return "$baseScheme$authority$newPath$queryString"
    }

    /**
     * Merge two URL-encoded query strings. Keys from [base] come first; if a
     * key also appears in [override], the override's value wins and is moved
     * to the position of the base entry (so the merge is stable w.r.t. order).
     * Keys present only in [override] are appended at the end.
     */
    private fun mergeQuery(base: String?, override: String?): String {
        if (base.isNullOrBlank() && override.isNullOrBlank()) return ""
        if (base.isNullOrBlank()) return override ?: ""
        if (override.isNullOrBlank()) return base

        val order = mutableListOf<String>()
        val values = mutableMapOf<String, String>()

        for (q in listOf(base, override)) {
            q.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val eq = pair.indexOf('=')
                val key = if (eq >= 0) pair.substring(0, eq) else pair
                val value = if (eq >= 0) pair.substring(eq + 1) else ""
                if (!order.contains(key)) order.add(key)
                values[key] = value
            }
        }

        return order.joinToString("&") { key -> "$key=${values[key]}" }
    }
}
