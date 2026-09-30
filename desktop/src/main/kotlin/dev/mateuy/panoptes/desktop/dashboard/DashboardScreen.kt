package dev.mateuy.panoptes.desktop.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mateuy.panoptes.desktop.SETTINGS_SAVED_KEY
import dev.mateuy.panoptes.desktop.theme.PanoptesColors
import dev.mateuy.panoptes.domain.model.ReleaseStatus
import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.model.TrackVersion
import org.koin.compose.viewmodel.koinViewModel
import java.time.format.DateTimeFormatter

private const val STORE_COLUMN_WEIGHT = 0.9f
private val BOARD_PADDING = 30.dp

@Composable
fun DashboardScreen(
    savedStateHandle: SavedStateHandle,
    onOpenSettings: () -> Unit,
    /** Shown above the board, e.g. when the credentials file isn't git-ignored. */
    warning: String?,
    viewModel: DashboardViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val settingsSaved by savedStateHandle.getStateFlow(SETTINGS_SAVED_KEY, false).collectAsStateWithLifecycle()
    LaunchedEffect(settingsSaved) {
        if (settingsSaved) {
            savedStateHandle[SETTINGS_SAVED_KEY] = false
            viewModel.refresh()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    DashboardContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        onOpenSettings = onOpenSettings,
        onPromote = viewModel::requestPromotion,
        warning = warning,
    )

    state.pendingPromotion?.let { promotion ->
        PromotionDialog(
            promotion = promotion,
            isPromoting = state.isPromoting,
            onConfirm = viewModel::confirmPromotion,
            onDismiss = viewModel::dismissPromotion,
        )
    }
}

@Composable
fun DashboardContent(
    state: DashboardUiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onPromote: (storeName: String, from: Track) -> Unit,
    warning: String? = null,
) {
    Scaffold(
        containerColor = PanoptesColors.Page,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // The board sets its own sizes; drop Material's body letter spacing and line height
        ProvideTextStyle(LocalTextStyle.current.copy(letterSpacing = 0.sp, lineHeight = TextUnit.Unspecified)) {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(25.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(state, onRefresh, onOpenSettings)
                warning?.let { Warning(it) }
                Board(state, onPromote)
                Legend(state)
            }
        }
    }
}

@Composable
private fun Header(state: DashboardUiState, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.clip(RoundedCornerShape(4.dp)).background(PanoptesColors.Ink).padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text("PANOPTES", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.35.em)
        }
        Spacer(Modifier.width(12.dp))
        Text(summary(state), color = PanoptesColors.PageText, fontSize = 12.sp, modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onOpenSettings,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, PanoptesColors.PageBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PanoptesColors.Ink),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Settings", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = onRefresh,
            enabled = !state.isRefreshing,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PanoptesColors.Ink,
                contentColor = Color.White,
                disabledContainerColor = PanoptesColors.Ink.copy(alpha = 0.7f),
                disabledContentColor = Color.White.copy(alpha = 0.8f),
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (state.isRefreshing) {
                CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(14.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text("Refresh", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Warning(text: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(PanoptesColors.WarningContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, color = PanoptesColors.WarningContent, fontSize = 12.sp)
    }
}

/** "target 4.2.63 · 3 of 4 stores in sync" */
private fun summary(state: DashboardUiState) = buildAnnotatedString {
    val target = state.target
    if (target == null) {
        append(if (state.isRefreshing) "Loading stores…" else "No releases found")
        return@buildAnnotatedString
    }
    append("target ")
    withStyle(SpanStyle(color = PanoptesColors.Ink, fontWeight = FontWeight.SemiBold)) {
        append(target.splitBuild().first)
    }
    val (inSync, total) = state.syncCount
    append(" · $inSync of $total stores in sync")
}

@Composable
private fun Board(state: DashboardUiState, onPromote: (storeName: String, from: Track) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PanoptesColors.Board)) {
        Row(Modifier.padding(horizontal = BOARD_PADDING, vertical = 14.dp)) {
            HeaderLabel("Store", Modifier.weight(STORE_COLUMN_WEIGHT))
            Track.entries.forEach { HeaderLabel(it.label, Modifier.weight(1f)) }
        }
        state.stores.forEach { store ->
            HorizontalDivider(color = PanoptesColors.BoardDivider)
            StoreRow(store, state.behindTarget(store), onPromote = { from -> onPromote(store.storeName, from) })
        }
    }
}

@Composable
private fun HeaderLabel(text: String, modifier: Modifier) {
    Text(text, modifier, color = PanoptesColors.BoardHeader, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.03.em)
}

@Composable
private fun StoreRow(store: StoreState, behind: Behind?, onPromote: (Track) -> Unit) {
    val rowModifier = if (behind != null) Modifier.background(PanoptesColors.BehindRow) else Modifier
    Row(rowModifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Red strip marking a store that lags behind the target
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (behind != null) PanoptesColors.Behind else Color.Transparent))
        Column(Modifier.weight(1f)) {
            if (store.isLoading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = PanoptesColors.TextSecondary,
                    trackColor = Color.Transparent,
                )
            }
            Row(Modifier.padding(start = BOARD_PADDING - 3.dp, end = BOARD_PADDING, top = 16.dp, bottom = 22.dp)) {
                StoreLabel(store, Modifier.weight(STORE_COLUMN_WEIGHT))
                when {
                    store.isLoading -> MutedText("Loading…", Modifier.weight(4f))
                    store.error != null -> SelectionContainer(Modifier.weight(4f)) {
                        Text(store.error, color = PanoptesColors.BehindText, fontSize = 12.sp)
                    }
                    else -> Track.entries.forEach { track ->
                        TrackCell(
                            version = store.versions[track],
                            supported = track in store.supportedTracks,
                            behind = behind?.takeIf { it.track == track },
                            promoteTo = store.promotions[track],
                            onPromote = { onPromote(track) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (!store.isLoading && store.error == null && store.notes.isNotEmpty()) {
                Column(
                    Modifier.padding(start = BOARD_PADDING - 3.dp, end = BOARD_PADDING, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    store.notes.forEach { (track, note) -> Note(track, note) }
                }
            }
        }
    }
}

@Composable
private fun StoreLabel(store: StoreState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val (initials, color) = store.storeName.avatar()
        Box(
            Modifier.size(27.dp).clip(RoundedCornerShape(6.dp)).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(store.storeName, color = PanoptesColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            val tracks = store.supportedTracks.size
            Text("$tracks ${if (tracks == 1) "track" else "tracks"}", color = PanoptesColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

private fun String.avatar(): Pair<String, Color> = when (this) {
    "Google Play" -> "GP" to Color(0xFF34A853)
    "App Store" -> "AS" to Color(0xFF0D84FF)
    "Microsoft Store" -> "MS" to Color(0xFF8B5CF6)
    "Snap Store" -> "SN" to Color(0xFFF05A22)
    else -> split(' ').take(2).joinToString("") { it.take(1) }.uppercase() to PanoptesColors.Neutral
}

@Composable
private fun TrackCell(
    version: TrackVersion?,
    supported: Boolean,
    behind: Behind?,
    promoteTo: Track?,
    onPromote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !supported -> MutedText("Not offered")
            version == null -> MutedText("No release")
            else -> {
                val (name, build) = version.splitBuild()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    VersionBox(name, if (behind != null) PanoptesColors.BehindText else PanoptesColors.Text)
                    if (build != null) {
                        Text("build $build", color = PanoptesColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (behind != null) {
                    Pill(behind.label, Icons.Filled.Warning, PanoptesColors.Behind, Color.White)
                } else {
                    val (container, content) = version.status.pillColors
                    Pill(version.status.label, version.status.icon, container, content)
                }
                if (promoteTo != null) PromoteButton(promoteTo, onPromote)
            }
        }
    }
}

private val Behind.label: String
    get() = when (releases) {
        null -> "Behind target"
        1 -> "1 behind target"
        else -> "$releases behind target"
    }

@Composable
private fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 4.dp), color = PanoptesColors.TextMuted, fontSize = 12.sp)
}

@Composable
private fun VersionBox(version: String, color: Color) {
    Text(
        version,
        Modifier.clip(RoundedCornerShape(4.dp)).background(PanoptesColors.VersionBox).padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        fontSize = 16.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.08.em,
        maxLines = 1,
    )
}

@Composable
private fun Pill(text: String, icon: ImageVector, container: Color, content: Color) {
    Row(
        Modifier.clip(CircleShape).background(container).padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(11.dp), tint = content)
        Text(text, color = content, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PromoteButton(to: Track, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, PanoptesColors.ButtonBorder, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(11.dp), tint = PanoptesColors.Text)
        Text("Promote to ${to.label}", color = PanoptesColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Medium, softWrap = false)
    }
}

@Composable
private fun Note(track: Track, note: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, Modifier.size(14.dp).padding(top = 1.dp), tint = PanoptesColors.Review)
        SelectionContainer {
            Text("${track.label}: $note", color = PanoptesColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Legend(state: DashboardUiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem("Live", PanoptesColors.Live)
        LegendItem("In review", PanoptesColors.Review)
        LegendItem("Behind target", PanoptesColors.Behind)
        Spacer(Modifier.weight(1f))
        state.lastSync?.let {
            Text("Last sync ${it.format(TIME_FORMAT)}", color = PanoptesColors.PageText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, color = PanoptesColors.PageText, fontSize = 11.sp)
    }
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** Splits "4.2.63 (452)" into the version and its build number. */
private fun TrackVersion.splitBuild(): Pair<String, String?> {
    val match = BUILD_SUFFIX.matchEntire(versionName) ?: return versionName to null
    return match.groupValues[1] to match.groupValues[2]
}

private val BUILD_SUFFIX = Regex("""(.+?) \((.+)\)""")

@Composable
private fun PromotionDialog(
    promotion: PendingPromotion,
    isPromoting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Promote to ${promotion.to.label}?") },
        text = {
            Text(
                "${promotion.storeName}: ${promotion.versionName ?: "the current build"} will be released " +
                    "from ${promotion.from.label} to ${promotion.to.label}."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isPromoting) {
                if (isPromoting) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Text("Promote")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isPromoting) { Text("Cancel") }
        },
    )
}

private val ReleaseStatus.label: String
    get() = when (this) {
        ReleaseStatus.PUBLISHED -> "Live"
        ReleaseStatus.IN_REVIEW -> "In review"
        ReleaseStatus.DRAFT -> "Draft"
        ReleaseStatus.HALTED -> "Halted"
        ReleaseStatus.UNKNOWN -> "Unknown"
    }

private val ReleaseStatus.icon: ImageVector
    get() = when (this) {
        ReleaseStatus.PUBLISHED -> Icons.Filled.CheckCircle
        ReleaseStatus.IN_REVIEW -> Icons.Filled.Search
        ReleaseStatus.DRAFT -> Icons.Filled.Edit
        ReleaseStatus.HALTED -> Icons.Filled.Warning
        ReleaseStatus.UNKNOWN -> Icons.Filled.Info
    }

private val ReleaseStatus.pillColors: Pair<Color, Color>
    get() = when (this) {
        ReleaseStatus.PUBLISHED -> PanoptesColors.Live to Color.White
        ReleaseStatus.IN_REVIEW -> PanoptesColors.Review to PanoptesColors.Ink
        ReleaseStatus.HALTED -> PanoptesColors.Behind to Color.White
        ReleaseStatus.DRAFT, ReleaseStatus.UNKNOWN -> PanoptesColors.Neutral to PanoptesColors.Text
    }
