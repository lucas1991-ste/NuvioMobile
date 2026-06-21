package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.nuvio.app.core.ui.rememberNuvioBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_hls_audio
import nuvio.composeapp.generated.resources.downloads_hls_audio_hint
import nuvio.composeapp.generated.resources.downloads_hls_download
import nuvio.composeapp.generated.resources.downloads_hls_embedded
import nuvio.composeapp.generated.resources.downloads_hls_fetching
import nuvio.composeapp.generated.resources.downloads_hls_no_audio
import nuvio.composeapp.generated.resources.downloads_hls_no_subtitles
import nuvio.composeapp.generated.resources.downloads_hls_quality
import nuvio.composeapp.generated.resources.downloads_hls_select_variant
import nuvio.composeapp.generated.resources.downloads_hls_subtitle_hint
import nuvio.composeapp.generated.resources.downloads_hls_subtitles
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsHlsSelectionSheet(
    stream: StreamItem?,
    onDismiss: () -> Unit,
    onDownload: (HlsDownloadSelection) -> Unit,
) {
    if (stream == null) return

    val sheetState = rememberNuvioBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var hlsMetadata by remember { mutableStateOf<HlsStreamMetadata?>(null) }
    var isFetching by remember { mutableStateOf(true) }
    var fetchError by remember { mutableStateOf(false) }

    LaunchedEffect(stream) {
        isFetching = true
        fetchError = false
        val metadata = withContext(Dispatchers.Default) {
            DownloadsRepository.fetchHlsMasterPlaylist(stream)
        }
        hlsMetadata = metadata
        isFetching = false
        if (metadata == null) {
            fetchError = true
        }
    }

    val playlist = hlsMetadata?.masterPlaylist

    val qualityOptions = remember(playlist) {
        playlist?.variants?.mapIndexed { _, variant ->
            val label = buildString {
                variant.resolution?.let { append(it) }
                if (variant.resolution != null) append(" • ")
                append(formatBandwidth(variant.bandwidth))
                variant.codecs?.let { codecs ->
                    val codecLabel = friendlyCodec(codecs)
                    if (codecLabel.isNotBlank()) {
                        append(" • ")
                        append(codecLabel)
                    }
                }
            }
            NuvioDropdownOption(
                key = variant.url,
                label = label,
            )
        }.orEmpty()
    }

    // Audio tracks: multi-select. Default = the track flagged DEFAULT in the master playlist.
    // Selection state is keyed by track index, not by URI, because multiple tracks may share
    // the same (or empty) URI and would otherwise collapse into a single toggle.
    // Tracks without a URI are "embedded" (multiplexed inside the video stream) and cannot
    // be downloaded separately — they are shown but their checkbox is disabled.
    val audioTracks = remember(playlist) { playlist?.audioTracks.orEmpty() }
    val embeddedAudioIndices = remember(audioTracks) {
        audioTracks.mapIndexedNotNull { index, track ->
            if (track.uri.isNullOrBlank()) index else null
        }.toSet()
    }
    var selectedAudioIndices by remember(audioTracks) {
        mutableStateOf(
            audioTracks
                .mapIndexed { index, track -> index to track }
                .filter { (index, track) -> track.isDefault && index !in embeddedAudioIndices }
                .map { it.first }
                .toSet(),
        )
    }

    // Subtitle tracks: multi-select. Default = empty (no subtitles).
    // Tracks without a URI are "embedded" and cannot be downloaded separately.
    val subtitleTracks = remember(playlist) { playlist?.subtitleTracks.orEmpty() }
    val embeddedSubtitleIndices = remember(subtitleTracks) {
        subtitleTracks.mapIndexedNotNull { index, track ->
            if (track.uri.isNullOrBlank()) index else null
        }.toSet()
    }
    var selectedSubtitleIndices by remember(subtitleTracks) {
        mutableStateOf(emptySet<Int>())
    }

    var selectedQualityKey by remember(qualityOptions) {
        mutableStateOf(qualityOptions.firstOrNull()?.key)
    }

    val hasAudio = audioTracks.isNotEmpty()
    val hasSubtitles = subtitleTracks.isNotEmpty()
    val canDownload = qualityOptions.isNotEmpty()

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stream.streamLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.downloads_hls_select_variant),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                fetchError -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.download_failed),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                isFetching || qualityOptions.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(Res.string.downloads_hls_fetching),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // === Quality (single-select) ===
                        Text(
                            text = stringResource(Res.string.downloads_hls_quality),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        NuvioDropdownChip(
                            title = stringResource(Res.string.downloads_hls_quality),
                            label = qualityOptions.firstOrNull { it.key == selectedQualityKey }?.label.orEmpty(),
                            selectedKey = selectedQualityKey,
                            options = qualityOptions,
                            onSelected = { option ->
                                selectedQualityKey = option.key
                            },
                        )

                        // === Audio tracks (multi-select) ===
                        if (hasAudio) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(Res.string.downloads_hls_audio),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(Res.string.downloads_hls_audio_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                audioTracks.forEachIndexed { index, track ->
                                    val isEmbedded = index in embeddedAudioIndices
                                    val isSelected = index in selectedAudioIndices
                                    SelectableTrackRow(
                                        name = track.name,
                                        language = track.language,
                                        isEmbedded = isEmbedded,
                                        isSelected = isSelected,
                                        enabled = !isEmbedded,
                                        onToggle = {
                                            if (!isEmbedded) {
                                                selectedAudioIndices = if (isSelected) {
                                                    selectedAudioIndices - index
                                                } else {
                                                    selectedAudioIndices + index
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(Res.string.downloads_hls_no_audio),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // === Subtitle tracks (multi-select) ===
                        if (hasSubtitles) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(Res.string.downloads_hls_subtitles),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(Res.string.downloads_hls_subtitle_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                subtitleTracks.forEachIndexed { index, track ->
                                    val isEmbedded = index in embeddedSubtitleIndices
                                    val isSelected = index in selectedSubtitleIndices
                                    SelectableTrackRow(
                                        name = track.name,
                                        language = track.language,
                                        isEmbedded = isEmbedded,
                                        isSelected = isSelected,
                                        enabled = !isEmbedded,
                                        onToggle = {
                                            if (!isEmbedded) {
                                                selectedSubtitleIndices = if (isSelected) {
                                                    selectedSubtitleIndices - index
                                                } else {
                                                    selectedSubtitleIndices + index
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(Res.string.downloads_hls_no_subtitles),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        NuvioPrimaryButton(
                            text = stringResource(Res.string.downloads_hls_download),
                            enabled = canDownload,
                            onClick = {
                                val selectedVariant = playlist?.variants?.firstOrNull {
                                    it.url == selectedQualityKey
                                }
                                val audioSelections = audioTracks
                                    .mapIndexed { index, track -> index to track }
                                    .filter { (index, track) ->
                                        index in selectedAudioIndices && track.uri.isNullOrBlank().not()
                                    }
                                    .map { (_, track) ->
                                        HlsTrackSelection(
                                            url = track.uri.orEmpty(),
                                            name = track.name,
                                            language = track.language,
                                        )
                                    }
                                val subtitleSelections = subtitleTracks
                                    .mapIndexed { index, track -> index to track }
                                    .filter { (index, track) ->
                                        index in selectedSubtitleIndices && track.uri.isNullOrBlank().not()
                                    }
                                    .map { (_, track) ->
                                        HlsTrackSelection(
                                            url = track.uri.orEmpty(),
                                            name = track.name,
                                            language = track.language,
                                        )
                                    }
                                val selection = HlsDownloadSelection(
                                    variantUrl = selectedQualityKey.orEmpty(),
                                    audioTracks = audioSelections,
                                    subtitleTracks = subtitleSelections,
                                    displayQuality = selectedVariant?.resolution
                                        ?: formatBandwidth(selectedVariant?.bandwidth ?: 0L),
                                )
                                coroutineScope.launch {
                                    dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = {
                                        onDownload(selection)
                                    })
                                }
                            },
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableTrackRow(
    name: String,
    language: String?,
    isEmbedded: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val embeddedLabel = stringResource(Res.string.downloads_hls_embedded)
    val displayName = if (isEmbedded) "$name ($embeddedLabel)" else name

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.clickable(onClick = onToggle) else Modifier,
            ),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected && enabled) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        } else if (isEmbedded) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = if (enabled) { { onToggle() } } else null,
                enabled = enabled,
                modifier = Modifier.size(24.dp),
            )
            Column {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                language?.takeIf { it.isNotBlank() }?.let { lang ->
                    Text(
                        text = lang,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                    )
                }
            }
        }
    }
}

private fun formatBandwidth(bandwidth: Long): String {
    if (bandwidth <= 0L) return ""
    val mbps = bandwidth.toDouble() / 1_000_000.0
    return "${"%.1f".format(mbps).trimEnd('0').trimEnd('.')} Mbps"
}

private fun friendlyCodec(codecs: String): String {
    val lower = codecs.lowercase()
    return when {
        lower.contains("av01") || lower.contains("av1") -> "AV1"
        lower.contains("hev1") || lower.contains("hvc1") || lower.contains("hevc") -> "HEVC"
        lower.contains("avc1") || lower.contains("h264") -> "H.264"
        lower.contains("vp9") -> "VP9"
        lower.contains("vp8") -> "VP8"
        else -> ""
    }
}
