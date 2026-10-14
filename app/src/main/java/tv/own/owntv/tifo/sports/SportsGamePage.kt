package tv.own.owntv.tifo.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.player.ExoPreviewSurface
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.ui.components.ChannelLogoTile
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageLiveBadge
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.stage.StageSpecChip
import tv.own.owntv.ui.stage.StageTab
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.stage.drawBoxShadow
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * A game's page, opened from its card. Left: the preview, which starts on the best channel (the
 * one Watch would play) and follows the cursor through the channels, then the channels in three
 * tabs (TV, Event, World), one row per channel with its streams. OK plays full screen. Right: the
 * score, who officially shows the game (each a search of all channels), and ESPN's details.
 */
@Composable
internal fun SportsGamePage(
    vm: SportsViewModel,
    event: SportsEvent,
    state: SportsUiState,
    contentStart: Dp,
    previewEnabled: Boolean,
    refocus: String?,
    onRefocused: () -> Unit,
    onPlayChannel: (ChannelEntity) -> Unit,
    onSearchChannels: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    // Live TV's view model: its preview player, so there is never a second decoder, and its
    // favourites.
    val liveVm = org.koin.androidx.compose.koinViewModel<LiveViewModel>()
    val context = LocalContext.current
    val qualities = remember { StreamQualityStore.get(context) }
    val qualityVersion by qualities.version.collectAsStateWithLifecycle()
    val favoriteIds by liveVm.favoriteIds.collectAsStateWithLifecycle()
    val recordingRows by vm.recordingRows.collectAsStateWithLifecycle()
    val qualityOf = { ch: ChannelEntity -> qualityVersion.let { qualities.qualityOf(ch) } }

    // The first channel listed plays as soon as it is known; the cursor on another one plays it
    // once it settles, as in Live TV. What the stream turns out to be is kept for its tags.
    // The channels as the list shows them; the preview starts on its first row.
    val groups = remember(state.matches) { SportsChannelGroups.group(state.matches) }
    var previewed by remember(event.id) { mutableStateOf<SportsChannelMatch?>(null) }
    LaunchedEffect(groups) {
        if (previewed == null) previewed = SportsChannelGroups.first(groups)?.best
    }
    val livePreviewSetting by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    val effectivePreview = previewEnabled && livePreviewSetting
    LaunchedEffect(previewed?.channel?.id, effectivePreview) {
        val channel = previewed?.channel ?: return@LaunchedEffect
        if (!effectivePreview) return@LaunchedEffect
        delay(PREVIEW_SETTLE_MS)
        liveVm.onChannelFocused(channel)
        liveVm.playPreview(channel)
        withTimeoutOrNull(MEASURE_MS) {
            // The chips still showing belong to the channel before; this one's follow a reset.
            liveVm.previewEngine.streamChips
                .dropWhile { it.isNotEmpty() }
                .collect { chips -> if (chips.isNotEmpty()) qualities.record(channel, chips) }
        }
    }

    Row(
        modifier.fillMaxSize().padding(start = contentStart, end = 48.mpx, top = 32.mpx, bottom = 24.mpx),
        horizontalArrangement = Arrangement.spacedBy(28.mpx),
    ) {
        Column(Modifier.weight(0.46f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.mpx)) {
            PreviewCard(
                match = previewed,
                quality = previewed?.channel?.let(qualityOf),
                previewEngine = liveVm.previewEngine,
                showVideo = effectivePreview,
            )
            ChannelsCard(
                event = event,
                state = state,
                groups = groups,
                favoriteIds = favoriteIds,
                qualityOf = qualityOf,
                recordingFor = { match -> vm.recordingFor(match, recordingRows) },
                refocus = refocus,
                onRefocused = onRefocused,
                onFocusChannel = { previewed = it },
                onPlayChannel = onPlayChannel,
                onToggleFavorite = { liveVm.toggleFavorite(it) },
                onToggleRecording = vm::toggleRecording,
                modifier = Modifier.weight(1f),
            )
        }
        GameInfo(
            event = event,
            state = state,
            refocus = refocus,
            onRefocused = onRefocused,
            onSearchChannels = onSearchChannels,
            modifier = Modifier.weight(0.54f).fillMaxHeight(),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------------------------

/** The channel playing muted, as Live TV's preview, with what it is and how good its picture is. */
@Composable
private fun PreviewCard(
    match: SportsChannelMatch?,
    quality: StreamQuality?,
    previewEngine: LivePreviewEngine,
    showVideo: Boolean,
) {
    val channel = match?.channel
    val previewState by previewEngine.state.collectAsStateWithLifecycle()
    val streamChips by previewEngine.streamChips.collectAsStateWithLifecycle()
    val playing = showVideo && channel != null &&
        previewState != LivePreviewEngine.State.ERROR &&
        previewState != LivePreviewEngine.State.IDLE
    val r = 26.mpx
    Column(verticalArrangement = Arrangement.spacedBy(10.mpx)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .drawBehind { drawBoxShadow(Color.Black.copy(alpha = 0.55f), 60.mpx.toPx(), r.toPx(), dy = 20.mpx.toPx()) }
                .clip(RoundedCornerShape(r))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (channel != null) {
                ChannelLogoTile(logoUrl = channel.displayLogoUrl, modifier = Modifier.size(160.mpx), fill = Color.Transparent) {
                    OwnTVIcon(OwnTVIcon.LIVE_TV, tint = StageColors.Dim, modifier = Modifier.size(48.mpx))
                }
            }
            if (playing) ExoPreviewSurface(engine = previewEngine, modifier = Modifier.fillMaxSize())
            if (playing && previewState == LivePreviewEngine.State.LOADING) OwnTVSpinner(sizeDp = 24)
            Box(Modifier.fillMaxSize().drawBehind { drawInnerRing(Color.White.copy(alpha = 0.08f), 1.mpx.toPx(), r.toPx()) })
            if (channel != null) {
                Box(Modifier.align(Alignment.TopStart).padding(start = 18.mpx, top = 16.mpx)) { StageLiveBadge(stringResource(R.string.player_live)) }
                if (playing && streamChips.isNotEmpty()) {
                    Row(Modifier.align(Alignment.TopEnd).padding(end = 18.mpx, top = 16.mpx), horizontalArrangement = Arrangement.spacedBy(6.mpx)) {
                        // Quality, frame rate and sound, as Live TV's preview shows them.
                        streamChips.filterNot { it.contains(':') || it.endsWith("Mbps") }.forEach { StageSpecChip(it) }
                    }
                }
            }
        }
        if (match != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                Text(
                    stringResource(R.string.tifo_sports_plays, match.channel.name),
                    style = stageText(16, 600), color = StageColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                qualityTags(quality).forEach { StageTag(it) }
            }
        }
    }
}

/** "1080p", "50 FPS": a channel's quality as tags. */
@Composable
internal fun qualityTags(quality: StreamQuality?): List<String> {
    if (quality == null) return emptyList()
    return listOfNotNull(
        quality.resolution,
        quality.fps?.let { stringResource(R.string.tifo_sports_fps, it) },
    )
}

// ---------------------------------------------------------------------------------------------
// Channels
// ---------------------------------------------------------------------------------------------

/** The game's channels: TV, Event and World tabs, rows per channel (or event category). */
@Composable
private fun ChannelsCard(
    event: SportsEvent,
    state: SportsUiState,
    groups: Map<ChannelTab, List<ChannelGroup>>,
    favoriteIds: Set<Long>,
    qualityOf: (ChannelEntity) -> StreamQuality?,
    recordingFor: (SportsChannelMatch) -> RecordingEntity?,
    refocus: String?,
    onRefocused: () -> Unit,
    onFocusChannel: (SportsChannelMatch) -> Unit,
    onPlayChannel: (ChannelEntity) -> Unit,
    onToggleFavorite: (ChannelEntity) -> Unit,
    onToggleRecording: (SportsChannelMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The tab holding the channel the cursor comes back to, else the first one with channels.
    val returning = groups.entries.firstOrNull { (_, rows) -> rows.any { g -> g.streams.any { channelKey(it.channel.id) == refocus } } }?.key
    val firstFull = SportsChannelGroups.firstTab(groups)
    var tab by remember(event.id, state.matching) { mutableStateOf(returning ?: firstFull) }
    var expanded by remember(event.id) { mutableStateOf(emptySet<String>()) }
    val firstTab = remember { FocusRequester() }
    // A page just opened (not one coming back from the player or a search) starts on the channels.
    LaunchedEffect(event.id) {
        if (refocus != null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstTab.requestFocus() }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(24.mpx))
            .padding(18.mpx),
        verticalArrangement = Arrangement.spacedBy(12.mpx),
    ) {
        Text(
            stringResource(R.string.tifo_sports_channels).uppercase(currentLocale()),
            style = stageText(14, 800), color = StageColors.Dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.mpx)) {
            ChannelTab.entries.forEachIndexed { i, t ->
                StageTab(
                    label = stringResource(tabLabel(t)),
                    count = groups[t].orEmpty().size.toString(),
                    selected = t == tab,
                    onClick = { tab = t },
                    modifier = if (i == 0) Modifier.focusRequester(firstTab) else Modifier,
                )
            }
        }
        val rows = groups[tab].orEmpty()
        when {
            state.matching -> Message(stringResource(R.string.tifo_sports_matching))
            state.matches.isEmpty() -> Message(stringResource(R.string.tifo_sports_no_channels))
            rows.isEmpty() -> Message(stringResource(R.string.tifo_sports_tab_empty))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.mpx), modifier = Modifier.fillMaxSize()) {
                var lastCountry: String? = "\u0000"
                for (group in rows) {
                    if (tab != ChannelTab.EVENT && group.country != lastCountry) {
                        lastCountry = group.country
                        item(key = "country:" + group.country) { CountryHeader(group.country) }
                    }
                    item(key = group.key) {
                        val returningHere = group.streams.firstOrNull { channelKey(it.channel.id) == refocus }
                        GroupRow(
                            group = group,
                            favorite = group.streams.any { it.channel.id in favoriteIds },
                            expanded = group.key in expanded,
                            onToggle = { expanded = if (group.key in expanded) expanded - group.key else expanded + group.key },
                            onFocused = { onFocusChannel(group.best) },
                            onClick = { onPlayChannel(group.best.channel) },
                            modifier = Modifier.refocusWhen(
                                if (returningHere != null) channelKey(returningHere.channel.id) else group.key,
                                refocus,
                                onRefocused,
                            ),
                        )
                    }
                    if (group.key in expanded) {
                        items(group.streams, key = { group.key + ":" + it.channel.id }) { match ->
                            val recording = recordingFor(match)
                            val canRecord = event.state == EventState.UPCOMING && (match.programmeStartMs ?: 0L) > System.currentTimeMillis()
                            StreamRow(
                                match = match,
                                quality = qualityOf(match.channel),
                                favorite = match.channel.id in favoriteIds,
                                recording = recording,
                                canRecord = canRecord,
                                onFocused = { onFocusChannel(match) },
                                onClick = { onPlayChannel(match.channel) },
                                onToggleFavorite = { onToggleFavorite(match.channel) },
                                onToggleRecording = { onToggleRecording(match) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun tabLabel(tab: ChannelTab): Int = when (tab) {
    ChannelTab.TV -> R.string.tifo_sports_tab_tv
    ChannelTab.EVENT -> R.string.tifo_sports_tab_event
    ChannelTab.WORLD -> R.string.tifo_sports_tab_world
}

/** The UI's language, read so a change of it redraws. */
@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** "FR  FRANCE" above a country's channels. */
@Composable
private fun CountryHeader(country: String?) {
    val locale = currentLocale()
    val name = country?.let { countryName(it, locale) }
    Row(
        Modifier.padding(start = 4.mpx, top = 8.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.mpx),
    ) {
        if (country != null) Text(country, style = stageText(12, 800), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (name != null) Text(name.uppercase(locale), style = stageText(14, 800), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A playlist's country code as a name in the user's language: "UK" is the United Kingdom. */
private fun countryName(code: String, locale: Locale): String? {
    val iso = if (code == "UK") "GB" else code
    if (iso.length != 2) return null
    return Locale("", iso).getDisplayCountry(locale).takeIf { it.isNotBlank() && it != iso }
}

/** One channel (or event category): logo, name, how many streams, why it's there, and ⌄ for its streams. */
@Composable
private fun GroupRow(
    group: ChannelGroup,
    favorite: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = stageAccent
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
        StageRow(
            onClick = onClick,
            height = 78.mpx,
            modifier = modifier.weight(1f).onFocusChanged { if (it.hasFocus) onFocused() },
        ) { focused ->
            ChannelLogoTile(logoUrl = group.best.channel.displayLogoUrl, modifier = Modifier.size(48.mpx)) {
                OwnTVIcon(OwnTVIcon.LIVE_TV, tint = StageColors.Dim, modifier = Modifier.size(24.mpx))
            }
            val text = if (focused) accent.onAccent else StageColors.Text
            val dim = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.mpx)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                    if (favorite) FavoriteStar(focused)
                    Text(group.title, style = stageText(19, 700), color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    pluralStringResource(R.plurals.tifo_sports_streams, group.streams.size, group.streams.size.toString()),
                    style = stageText(15, 500), color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (BuildConfig.DEBUG) StageTag(points(group.best.score), onAccent = if (focused) accent.onAccent else null)
            StageTag(
                stringResource(
                    when (group.kind) {
                        ChannelKind.TV -> R.string.tifo_sports_kind_tv
                        ChannelKind.EVENT -> R.string.tifo_sports_kind_event
                        ChannelKind.GUESS -> R.string.tifo_sports_kind_guess
                    },
                ),
                onAccent = if (focused) accent.onAccent else null,
                tint = if (group.kind == ChannelKind.EVENT) EventGreen else null,
            )
        }
        StageButton(
            text = null,
            onClick = onToggle,
            icon = if (expanded) OwnTVIcon.CHEVRON_UP else OwnTVIcon.CHEVRON_DOWN,
            round = true,
            height = 56.mpx,
            textSize = 20,
        )
    }
}

/** One stream of an open channel row: ▶ its full name, its programme, its quality; ☆ and ● beside it. */
@Composable
private fun StreamRow(
    match: SportsChannelMatch,
    quality: StreamQuality?,
    favorite: Boolean,
    recording: RecordingEntity?,
    canRecord: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    val accent = stageAccent
    val time = rememberSystemTimeFormatter()
    Row(
        Modifier.padding(start = 28.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.mpx),
    ) {
        StageRow(
            onClick = onClick,
            height = 70.mpx,
            modifier = Modifier.weight(1f).onFocusChanged { if (it.hasFocus) onFocused() },
        ) { focused ->
            OwnTVIcon(OwnTVIcon.PLAY, if (focused) accent.onAccent else accent.accent, Modifier.size(20.mpx), filled = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.mpx)) {
                Text(
                    match.channel.name,
                    style = stageText(16, 600), color = if (focused) accent.onAccent else StageColors.Text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val title = match.programmeTitle
                val start = match.programmeStartMs
                val stop = match.programmeStopMs
                if (title != null && start != null && stop != null) {
                    val programme = stringResource(R.string.tifo_sports_programme, time(start), time(stop), title)
                    Text(
                        if (recording != null) stringResource(R.string.tifo_sports_recording_set, programme) else programme,
                        style = stageText(14, 500), color = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            qualityTags(quality).forEach { StageTag(it, onAccent = if (focused) accent.onAccent else null) }
            if (BuildConfig.DEBUG) StageTag(pointsBreakdown(match), onAccent = if (focused) accent.onAccent else null)
        }
        StageButton(text = null, onClick = onToggleFavorite, icon = OwnTVIcon.STAR, iconFilled = favorite, tinted = favorite, round = true, height = 52.mpx, textSize = 20)
        if (recording != null || canRecord) {
            StageButton(text = null, onClick = onToggleRecording, icon = OwnTVIcon.REC, iconFilled = recording != null, tinted = recording != null, round = true, height = 52.mpx, textSize = 20)
        }
    }
}

/** "650 pts": a channel's points, shown in debug builds to see how the list is ranked. */
@Composable
private fun points(value: Int): String =
    stringResource(R.string.tifo_sports_points, java.text.NumberFormat.getIntegerInstance(currentLocale()).format(value))

/** "650 pts = 600 + 50": a stream's points, the rule's and the extras (country, favourite). */
@Composable
private fun pointsBreakdown(match: SportsChannelMatch): String {
    val total = points(match.score)
    val extra = match.score - match.base
    if (extra == 0) return total
    val number = java.text.NumberFormat.getIntegerInstance(currentLocale())
    return total + " = " + number.format(match.base) + " + " + number.format(extra)
}

// ---------------------------------------------------------------------------------------------
// Game info
// ---------------------------------------------------------------------------------------------

private enum class InfoSection { TIMELINE, LINEUPS, BOX_SCORE, STATS, LEADERS, INFO }

/** The right-hand side: score, official channels, then ESPN's details in tabs. */
@Composable
private fun GameInfo(
    event: SportsEvent,
    state: SportsUiState,
    refocus: String?,
    onRefocused: () -> Unit,
    onSearchChannels: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = state.summary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.mpx)) {
        ScoreHeader(event)
        if (state.official.isNotEmpty() || summary?.venue != null) {
            OfficialChannels(state.official, summary, refocus, onRefocused, onSearchChannels)
        }
        val sections = summary?.let { s ->
            buildList {
                if (s.timeline.isNotEmpty()) add(InfoSection.TIMELINE)
                if (s.lineups.isNotEmpty()) add(InfoSection.LINEUPS)
                if (s.players.isNotEmpty()) add(InfoSection.BOX_SCORE)
                if (s.teamStats.isNotEmpty()) add(InfoSection.STATS)
                if (s.leaders.isNotEmpty()) add(InfoSection.LEADERS)
                if (s.form.isNotEmpty() || s.injuries.isNotEmpty() || s.standings.isNotEmpty()) add(InfoSection.INFO)
            }
        }.orEmpty()
        when {
            summary == null && state.summaryLoading -> Message(stringResource(R.string.tifo_sports_details_loading))
            summary == null && event.competition.kind != SportKind.TEAM -> Unit
            sections.isEmpty() -> Message(stringResource(R.string.tifo_sports_details_none))
            else -> {
                // Basketball opens on the box score, the rest on the stats, when ESPN has them;
                // else on whatever it has.
                val wanted = if (event.competition == Competition.NBA) InfoSection.BOX_SCORE else InfoSection.STATS
                val preferred = if (wanted in sections) wanted else sections.first()
                var open by remember(event.id) { mutableStateOf(preferred) }
                if (open !in sections) open = preferred
                Row(horizontalArrangement = Arrangement.spacedBy(6.mpx)) {
                    sections.forEach { s ->
                        StageTab(label = stringResource(sectionLabel(s, event)), count = "", selected = s == open, onClick = { open = s })
                    }
                }
                ScrollReader(Modifier.weight(1f).fillMaxWidth()) {
                    when (open) {
                        InfoSection.TIMELINE -> Timeline(summary!!.timeline)
                        InfoSection.LINEUPS -> Lineups(summary!!.lineups)
                        InfoSection.BOX_SCORE -> BoxScore(summary!!.players)
                        InfoSection.STATS -> TeamStats(summary!!.teamStats)
                        InfoSection.LEADERS -> Leaders(summary!!.leaders, event)
                        InfoSection.INFO -> Info(summary!!)
                    }
                }
            }
        }
    }
}

private fun sectionLabel(section: InfoSection, event: SportsEvent): Int = when (section) {
    InfoSection.TIMELINE -> if (event.competition.isFootball) R.string.tifo_sports_summary else R.string.tifo_sports_scoring
    InfoSection.LINEUPS -> R.string.tifo_sports_lineups
    InfoSection.BOX_SCORE -> R.string.tifo_sports_box_score
    InfoSection.STATS -> R.string.tifo_sports_stats
    InfoSection.LEADERS -> R.string.tifo_sports_leaders
    InfoSection.INFO -> R.string.tifo_sports_info
}

/** Both teams with the score (or kick-off time) and the clock between them; a tennis match's sets. */
@Composable
private fun ScoreHeader(event: SportsEvent) {
    val time = rememberSystemTimeFormatter()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0xFF1C2444), Color(0xFF2B1A44))), RoundedCornerShape(24.mpx))
            .padding(horizontal = 24.mpx, vertical = 18.mpx),
        verticalArrangement = Arrangement.spacedBy(10.mpx),
    ) {
        val home = event.home
        val away = event.away
        if (home == null || away == null) {
            Text(event.name, style = stageText(28, 800), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(statusText(event), style = stageText(17, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
            TeamLogo(home.logoUrl, 56.mpx)
            Text(home.shortName, style = stageText(22, 800), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Column(
                Modifier
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.mpx))
                    .padding(horizontal = 22.mpx, vertical = 10.mpx),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val score = if (event.state == EventState.UPCOMING || home.score == null || away.score == null) {
                    time(event.startMs)
                } else {
                    stringResource(R.string.tifo_sports_score, home.score, away.score)
                }
                Text(score, style = stageText(30, 800), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    statusText(event),
                    style = stageText(14, 700),
                    color = if (event.state == EventState.LIVE) EventGreen else StageColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(away.shortName, style = stageText(22, 800), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            TeamLogo(away.logoUrl, 56.mpx)
        }
        // A tennis match: the games of each set, as a scoreboard.
        if (home.sets.isNotEmpty() || away.sets.isNotEmpty()) {
            listOf(home, away).forEach { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.mpx), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.shortName, style = stageText(16, 700), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    p.sets.forEach { Text(it, style = stageText(18, 800), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(28.mpx), textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

@Composable
private fun statusText(event: SportsEvent): String = when (event.state) {
    EventState.LIVE -> event.statusDetail.ifBlank { stringResource(R.string.tifo_sports_live) }
    EventState.FINISHED -> event.statusDetail.ifBlank { stringResource(R.string.tifo_sports_final) }
    EventState.UPCOMING -> event.statusDetail.ifBlank { stringResource(event.competition.labelRes) }
}

/**
 * Who officially shows the game, a line per country ("US  CBSSN · Paramount+"), each name a search
 * of all channels; the venue under them.
 */
@Composable
private fun OfficialChannels(
    official: Map<String, List<String>>,
    summary: GameSummary?,
    refocus: String?,
    onRefocused: () -> Unit,
    onSearchChannels: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.mpx)) {
        if (official.isNotEmpty()) {
            Text(
                stringResource(R.string.tifo_sports_official).uppercase(currentLocale()),
                style = stageText(14, 800), color = StageColors.Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        official.forEach { (country, names) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                Text(country, style = stageText(13, 800), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(30.mpx))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.mpx)) {
                    items(names, key = { it }) { name ->
                        StageTool(
                            text = name,
                            onClick = { onSearchChannels(name) },
                            icon = OwnTVIcon.SEARCH,
                            modifier = Modifier.refocusWhen(searchKey(name), refocus, onRefocused),
                        )
                    }
                }
            }
        }
        val venue = summary?.venue
        if (venue != null) {
            val attendance = summary.attendance?.let { stringResource(R.string.tifo_sports_attendance, it) }
            Text(
                listOfNotNull(venue, attendance).joinToString(" · "),
                style = stageText(15, 500), color = StageColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Text the remote scrolls: focused, Down and Up move it, and at its ends the cursor moves on (Up
 * to the tabs above). A TV has no touch, and the details are not rows to walk through.
 */
@Composable
private fun ScrollReader(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val accent = stageAccent
    val step = with(LocalDensity.current) { 220.mpx.toPx() }
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(20.mpx))
            .then(if (focused) Modifier.border(2.mpx, accent.accent.copy(alpha = 0.6f), RoundedCornerShape(20.mpx)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> (scroll.value < scroll.maxValue).also { if (it) scope.launch { scroll.animateScrollBy(step) } }
                    Key.DirectionUp -> (scroll.value > 0).also { if (it) scope.launch { scroll.animateScrollBy(-step) } }
                    else -> false
                }
            }
            .focusable()
            .verticalScroll(scroll)
            .padding(18.mpx),
        verticalArrangement = Arrangement.spacedBy(10.mpx),
        content = content,
    )
}

/** Goals, cards and substitutions (football) or scoring plays, each on its team's side. */
@Composable
private fun Timeline(items: List<TimelineItem>) {
    items.forEach { item ->
        when (item.kind) {
            TimelineKind.HALF_TIME -> Text(
                stringResource(R.string.tifo_sports_half_time),
                style = stageText(14, 800), color = StageColors.Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            TimelineKind.SCORE -> Row(horizontalArrangement = Arrangement.spacedBy(14.mpx), verticalAlignment = Alignment.CenterVertically) {
                Text(item.clock, style = stageText(15, 700), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.mpx))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.mpx)) {
                    Text(item.title, style = stageText(17, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.detail?.let { Text(it, style = stageText(15, 500), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (item.homeScore != null && item.awayScore != null) {
                    Text(stringResource(R.string.tifo_sports_score, item.homeScore, item.awayScore), style = stageText(18, 800), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (item.side == Side.HOME) TimelineSide(item, alignEnd = true)
                }
                Text(item.clock, style = stageText(15, 800), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(72.mpx))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (item.side != Side.HOME) TimelineSide(item, alignEnd = false)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.mpx).background(Color.White.copy(alpha = 0.05f)))
    }
}

@Composable
private fun TimelineSide(item: TimelineItem, alignEnd: Boolean) {
    val mark: @Composable () -> Unit = { TimelineMark(item.kind) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
        if (!alignEnd) mark()
        Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
            Text(
                if (item.kind == TimelineKind.OWN_GOAL) stringResource(R.string.tifo_sports_own_goal, item.title) else item.title,
                style = stageText(17, 700), color = StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            item.detail?.let {
                Text(stringResource(R.string.tifo_sports_sub_for, it), style = stageText(14, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (alignEnd) mark()
    }
}

/** ⚽ for a goal, a yellow or red card, ⇄ for a substitution. */
@Composable
private fun TimelineMark(kind: TimelineKind) {
    when (kind) {
        TimelineKind.YELLOW, TimelineKind.RED -> Box(
            Modifier.size(width = 13.mpx, height = 18.mpx)
                .background(if (kind == TimelineKind.YELLOW) Color(0xFFF7C948) else Color(0xFFE5484D), RoundedCornerShape(3.mpx)),
        )
        TimelineKind.SUBSTITUTION -> OwnTVIcon(OwnTVIcon.SWAP, StageColors.Muted, Modifier.size(18.mpx))
        else -> Text(BALL, style = stageText(16, 500), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Both teams' lineups side by side: formation, starters, then the bench. */
@Composable
private fun Lineups(lineups: List<Lineup>) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.mpx)) {
        lineups.forEach { lineup ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
                Text(
                    listOfNotNull(lineup.team, lineup.formation).joinToString(" · "),
                    style = stageText(17, 800), color = StageColors.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                lineup.starters.forEach { LineupLine(it) }
                if (lineup.bench.isNotEmpty()) {
                    Text(
                        stringResource(R.string.tifo_sports_substitutes),
                        style = stageText(14, 800), color = StageColors.Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.mpx),
                    )
                    lineup.bench.forEach { LineupLine(it) }
                }
            }
        }
    }
}

@Composable
private fun LineupLine(player: LineupPlayer) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.mpx), verticalAlignment = Alignment.CenterVertically) {
        Text(player.jersey.orEmpty(), style = stageText(14, 700), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(28.mpx))
        Text(player.name, style = stageText(16, 600), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (player.subbedIn || player.subbedOut) OwnTVIcon(OwnTVIcon.SWAP, if (player.subbedIn) EventGreen else StageColors.Dim, Modifier.size(14.mpx))
        player.position?.let { Text(it, style = stageText(13, 700), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Team stats: home value, the stat, away value, and a bar of their share when they are numbers. */
@Composable
private fun TeamStats(lines: List<StatLine>) {
    val accent = stageAccent
    lines.forEach { line ->
        Column(verticalArrangement = Arrangement.spacedBy(4.mpx)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.mpx)) {
                    Text(line.home, style = stageText(18, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    line.homeNote?.let { Text(it, style = stageText(13, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text(
                    line.labelRes?.let { stringResource(it) } ?: line.label,
                    style = stageText(15, 700), color = StageColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(2f),
                )
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.mpx, Alignment.End)) {
                    line.awayNote?.let { Text(it, style = stageText(13, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(line.away, style = stageText(18, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val h = line.homeValue?.toFloat() ?: line.home.toStatNumber()
            val a = line.awayValue?.toFloat() ?: line.away.toStatNumber()
            if (h != null && a != null && h + a > 0f) {
                Row(Modifier.fillMaxWidth().height(5.mpx).clip(RoundedCornerShape(3.mpx)).background(Color.White.copy(alpha = 0.08f))) {
                    if (h > 0f) Box(Modifier.weight(h).fillMaxHeight().background(accent.accent))
                    if (a > 0f) Box(Modifier.weight(a).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
                }
            }
        }
    }
}

/** "58%", "13", "5.1" as a number; "6-15" and "24:58" aren't. */
private fun String.toStatNumber(): Float? = trim().removeSuffix("%").toFloatOrNull()?.takeIf { it >= 0f }

/** Each team's player tables ("passing", "rushing"… or the NBA's one), starters first. */
@Composable
private fun BoxScore(tables: List<PlayerTable>) {
    val locale = currentLocale()
    tables.forEach { table ->
        Text(
            listOfNotNull(table.team, table.title?.replaceFirstChar { it.titlecase(locale) }).joinToString(" · "),
            style = stageText(16, 800), color = StageColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.mpx),
        )
        TableLine(name = "", values = table.labels, header = true)
        table.rows.forEach { TableLine(name = it.name, values = it.stats, header = false) }
    }
}

@Composable
private fun TableLine(name: String, values: List<String>, header: Boolean, highlighted: Boolean = false) {
    val color = when {
        header -> StageColors.Dim
        highlighted -> stageAccent.accent
        else -> StageColors.Text
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = stageText(14, if (header) 800 else 600), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(3f))
        values.forEach {
            Text(it, style = stageText(13, if (header) 800 else 600), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

/** The game's leaders: the stat, then each team's best at it. */
@Composable
private fun Leaders(lines: List<LeaderLine>, event: SportsEvent) {
    Row {
        Text(event.home?.shortName.orEmpty(), style = stageText(15, 800), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(event.away?.shortName.orEmpty(), style = stageText(15, 800), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    lines.forEach { line ->
        Text(line.category, style = stageText(14, 700), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
            Text(line.home.orEmpty(), style = stageText(15, 600), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(line.away.orEmpty(), style = stageText(15, 600), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

/** Before and around the game: each team's last results, injuries, the standings. */
@Composable
private fun Info(summary: GameSummary) {
    if (summary.form.isNotEmpty()) {
        InfoTitle(stringResource(R.string.tifo_sports_form))
        summary.form.forEach { team ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.mpx)) {
                Text(team.team, style = stageText(15, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                team.games.forEach { g ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.mpx)) {
                        Text(
                            g.result,
                            style = stageText(14, 800), color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.width(28.mpx).background(resultColor(g.result), RoundedCornerShape(6.mpx)),
                        )
                        Text(g.score, style = stageText(12, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (g.away) "@ " else "") + g.opponent, style = stageText(12, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (summary.injuries.isNotEmpty()) {
        InfoTitle(stringResource(R.string.tifo_sports_injuries))
        summary.injuries.forEach { team ->
            Text(team.team, style = stageText(15, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            team.players.forEach { (name, status) ->
                Row {
                    Text(name, style = stageText(15, 600), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(status, style = stageText(14, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    summary.standings.forEach { table ->
        InfoTitle(table.title ?: stringResource(R.string.tifo_sports_standings))
        TableLine(name = "", values = table.columns, header = true)
        table.rows.forEach { TableLine(name = it.team, values = it.values, header = false, highlighted = it.ours) }
    }
}

@Composable
private fun InfoTitle(text: String) {
    Text(text, style = stageText(16, 800), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.mpx))
}

private fun resultColor(result: String): Color = when (result.uppercase(Locale.ROOT)) {
    "W" -> Color(0xFF2E9E5B)
    "L" -> Color(0xFFC2414B)
    else -> Color(0xFF6B7280)
}

/** The green of the mock's clock and EVENT tags. */
private val EventGreen = Color(0xFF3DDC84)

private const val BALL = "⚽"

/** How long the cursor rests on a channel before it plays, as in Live TV. */
private const val PREVIEW_SETTLE_MS = 700L

/** How long a preview is watched for the stream's resolution and frame rate. */
private const val MEASURE_MS = 30_000L
