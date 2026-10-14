package tv.own.owntv.tifo.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.rememberBestDateFormatter
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.stage.StageStill
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.StageRadii
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

internal val LiveRed = Color(0xFFFF5A5A)

/**
 * The Sports tab: rows of game cards, as a streaming service's home: the favourite teams' games
 * first, then one row per league with the picked day's games. OK on a card opens the game's page
 * ([SportsGamePage]): its preview, its channels and ESPN's details. Back on the page comes back
 * to the card.
 */
@Composable
fun SportsScreen(
    onPlayChannel: (ChannelEntity) -> Unit,
    contentStart: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    /** False while the full-screen or mini player owns the video, as for Live TV's preview. */
    previewEnabled: Boolean = true,
    /** Opens the Search tab on [query] ("CBS"); Back there comes back here. */
    onSearchChannels: (query: String) -> Unit = {},
) {
    val vm = org.koin.androidx.compose.koinViewModel<SportsViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.refreshWhileVisible() }

    val context = LocalContext.current
    val favorites = remember { SportsFavorites.get(context) }
    val favoriteKeys by favorites.keys.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var dayPickerOpen by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val shownDay = state.shownDay(today)
    // Where the cursor goes next: the card a page closed on, the channel played full screen, the
    // broadcaster search left for.
    var refocus by remember { mutableStateOf(vm.takeReturnFocus()) }

    if (settingsOpen) {
        SportsTeamsSettings(
            vm, favorites, contentStart,
            onClose = {
                vm.setReturnFocus(TEAMS_BUTTON_KEY)
                settingsOpen = false
            },
            modifier = modifier,
        )
        return
    }

    val selected = state.selected
    if (selected != null) {
        SportsGamePage(
            vm = vm,
            event = selected,
            state = state,
            contentStart = contentStart,
            previewEnabled = previewEnabled,
            refocus = refocus,
            onRefocused = { refocus = null },
            onPlayChannel = { channel ->
                vm.setReturnFocus(channelKey(channel.id))
                onPlayChannel(channel)
            },
            onSearchChannels = { name ->
                vm.setReturnFocus(searchKey(name))
                onSearchChannels(name)
            },
            onClose = {
                vm.close()
                refocus = eventKey(selected)
            },
            modifier = modifier,
        )
        return
    }

    val listState = rememberLazyListState(vm.listIndex, vm.listOffset)
    DisposableEffect(listState) {
        onDispose {
            vm.listIndex = listState.firstVisibleItemIndex
            vm.listOffset = listState.firstVisibleItemScrollOffset
        }
    }
    // Back on a card goes up to the Teams button; Back there leaves Sports.
    var rowsFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = rowsFocused && !dayPickerOpen) { refocus = TEAMS_BUTTON_KEY }

    Column(
        modifier.padding(top = 40.mpx, bottom = 24.mpx),
        verticalArrangement = Arrangement.spacedBy(22.mpx),
    ) {
        Row(
            Modifier.padding(start = contentStart, end = 48.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.mpx),
        ) {
            Text(
                stringResource(R.string.tifo_sports_title),
                style = stageText(40, 800),
                color = StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Sports' own settings: the favourite teams.
            StageButton(
                text = stringResource(R.string.tifo_sports_teams),
                onClick = { settingsOpen = true },
                icon = OwnTVIcon.SETTINGS,
                height = 52.mpx,
                textSize = 18,
                modifier = Modifier.refocusWhen(TEAMS_BUTTON_KEY, refocus) { refocus = null },
            )
            // The day the league rows show.
            StageButton(
                text = dayLabel(shownDay, today),
                onClick = { dayPickerOpen = true },
                icon = OwnTVIcon.EPG,
                trailingIcon = OwnTVIcon.CHEVRON_DOWN,
                tinted = state.day != null,
                height = 52.mpx,
                textSize = 18,
                modifier = Modifier.refocusWhen(DAY_BUTTON_KEY, refocus) { refocus = null },
            )
        }
        val rows = state.rows(favoriteKeys, today, zone)
        val isFavorite = { e: SportsEvent, team: SportsTeam? -> SportsFavorites.isFavorite(favoriteKeys, e.competition, team) }
        Box(
            Modifier
                .fillMaxSize()
                .onFocusChanged { rowsFocused = it.hasFocus },
        ) {
            val pad = Modifier.padding(start = contentStart, end = 48.mpx)
            when {
                dayPickerOpen -> Box(pad) {
                    DayPicker(
                        shown = shownDay,
                        today = today,
                        gamesOn = { d -> state.events.count { SportsSchedule.isOn(it, d, today, zone) } },
                        onPick = { d ->
                            vm.setDay(d)
                            dayPickerOpen = false
                            refocus = DAY_BUTTON_KEY
                        },
                        onClose = {
                            dayPickerOpen = false
                            refocus = DAY_BUTTON_KEY
                        },
                    )
                }
                state.loading || (state.dayLoading && rows.isEmpty()) -> Box(pad) { Message(stringResource(R.string.tifo_sports_loading)) }
                rows.isEmpty() && state.failed -> Box(pad) { Message(stringResource(R.string.tifo_sports_failed)) }
                rows.isEmpty() -> Column(pad, verticalArrangement = Arrangement.spacedBy(12.mpx)) {
                    Message(
                        if (shownDay == today) stringResource(R.string.tifo_sports_no_games)
                        else stringResource(R.string.tifo_sports_no_games_on, dayDate(shownDay)),
                    )
                    // Straight to the next day with a game, when the schedule knows one.
                    val next = SportsSchedule.nextDay(state.events, shownDay, zone)
                    if (next != null) {
                        SportsActionRow(
                            text = stringResource(R.string.tifo_sports_next_games, dayLabel(next, today)),
                            icon = OwnTVIcon.EPG,
                            onClick = { vm.setDay(next) },
                        )
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(30.mpx),
                    contentPadding = PaddingValues(bottom = 40.mpx),
                ) {
                    items(rows, key = { it.key }) { row ->
                        GameRow(
                            row = row,
                            start = contentStart,
                            refocus = refocus,
                            onRefocused = { refocus = null },
                            isFavorite = isFavorite,
                            onOpen = { event -> vm.open(event, favoriteKeys) },
                        )
                    }
                }
            }
        }
    }
}

/** A row of game cards under its league's name ("Champions League 4"). */
@Composable
private fun GameRow(
    row: SportsRow,
    start: androidx.compose.ui.unit.Dp,
    refocus: String?,
    onRefocused: () -> Unit,
    isFavorite: (SportsEvent, SportsTeam?) -> Boolean,
    onOpen: (SportsEvent) -> Unit,
) {
    // Coming back to a card further along the row: the row starts there, so the card is on screen.
    val target = row.events.indexOfFirst { eventKey(it) == refocus }
    val rowState = rememberLazyListState((target - 1).coerceAtLeast(0))
    Column(verticalArrangement = Arrangement.spacedBy(14.mpx)) {
        Row(
            Modifier.padding(start = start),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.mpx),
        ) {
            Text(
                row.competition?.let { stringResource(it.labelRes) } ?: stringResource(R.string.tifo_sports_my_teams),
                style = stageText(24, 800), color = StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.events.size.toString(),
                style = stageText(18, 700), color = StageColors.Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(20.mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx),
            modifier = Modifier.focusRestorer().focusGroup(),
        ) {
            items(row.events, key = { eventKey(it) }) { event ->
                GameCard(
                    event = event,
                    showLeague = row.competition == null,
                    isFavorite = { team -> isFavorite(event, team) },
                    onClick = { onOpen(event) },
                    modifier = Modifier.refocusWhen(eventKey(event), refocus, onRefocused),
                )
            }
        }
    }
}

/**
 * A game as a 16:9 card: both teams' logos with the score (or kick-off time) between them, a LIVE
 * mark with the clock while it is on; under it "PSG vs Lyon" and its league or its state.
 */
@Composable
private fun GameCard(
    event: SportsEvent,
    showLeague: Boolean,
    isFavorite: (SportsTeam?) -> Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val time = rememberSystemTimeFormatter()
    val day = rememberBestDateFormatter(DAY_SKELETON)
    val league = stringResource(event.competition.labelRes)
    val status = when (event.state) {
        EventState.LIVE -> event.statusDetail.ifBlank { stringResource(R.string.tifo_sports_live) }
        EventState.FINISHED -> event.statusDetail.ifBlank { stringResource(R.string.tifo_sports_final) }
        EventState.UPCOMING -> (if (isToday(event.startMs)) stringResource(R.string.tifo_sports_today) else day(event.startMs)) + " · " + time(event.startMs)
    }
    StageStill(
        title = eventTitle(event),
        line = if (showLeague) stringResource(R.string.tifo_sports_card_line, league, status) else status,
        onClick = onClick,
        width = CARD_WIDTH,
        height = CARD_HEIGHT,
        titleSize = 19,
        lineSize = 15,
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(CardBackground),
            contentAlignment = Alignment.Center,
        ) {
            // The league's logo, large and faded behind the game, with a shade so the teams read.
            AsyncImage(
                model = event.competition.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = 0.32f,
                modifier = Modifier.fillMaxSize().padding(10.mpx),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.55f)))))
            val home = event.home
            val away = event.away
            if (home != null && away != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.mpx),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    CardTeam(home, isFavorite(home))
                    val middle = if (event.state == EventState.UPCOMING || home.score == null || away.score == null) {
                        time(event.startMs)
                    } else {
                        stringResource(R.string.tifo_sports_score, home.score, away.score)
                    }
                    Text(
                        middle,
                        style = stageText(if (event.state == EventState.UPCOMING) 24 else 32, 800),
                        color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    CardTeam(away, isFavorite(away))
                }
            } else {
                Text(
                    event.name,
                    style = stageText(24, 800), color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.mpx),
                )
            }
            if (event.state == EventState.LIVE) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.mpx)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.mpx))
                        .padding(horizontal = 10.mpx, vertical = 5.mpx),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.mpx),
                ) {
                    Box(Modifier.size(9.mpx).background(LiveRed, CircleShape))
                    Text(stringResource(R.string.tifo_sports_live), style = stageText(13, 800), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A side of a card: the team's logo (a player's flag), starred when it is a favourite. */
@Composable
private fun CardTeam(team: SportsTeam, favorite: Boolean) {
    Box {
        TeamLogo(team.logoUrl, size = 72.mpx)
        if (favorite) Box(Modifier.align(Alignment.TopEnd)) { FavoriteStar(focused = false) }
    }
}

/** "Today", "Tomorrow", or the weekday and date: "Sat 18 Oct". */
@Composable
internal fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> stringResource(R.string.tifo_sports_today)
    today.plusDays(1) -> stringResource(R.string.tifo_sports_tomorrow)
    today.minusDays(1) -> stringResource(R.string.tifo_sports_yesterday)
    else -> dayDate(day)
}

/** The weekday and date: "Sat 18 Oct". */
@Composable
internal fun dayDate(day: LocalDate): String {
    val format = rememberBestDateFormatter(DAY_SKELETON)
    return format(day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
}

/**
 * The schedule's days, yesterday to two weeks ahead, in place of the games. OK shows that day's
 * games, Back keeps the day.
 */
@Composable
private fun DayPicker(
    shown: LocalDate,
    today: LocalDate,
    gamesOn: (LocalDate) -> Int,
    onPick: (LocalDate) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val accent = stageAccent
    val days = remember(today) {
        (-SportsSchedule.DAYS_BACK..SportsSchedule.DAYS_AHEAD).map { today.plusDays(it) }
    }
    val shownRow = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { shownRow.requestFocus() }
    }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxWidth(0.56f), verticalArrangement = Arrangement.spacedBy(10.mpx)) {
        Text(
            stringResource(R.string.tifo_sports_pick_day),
            style = stageText(18, 500), color = StageColors.Muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        LazyColumn(
            state = rememberLazyListState(days.indexOf(shown).coerceAtLeast(0)),
            verticalArrangement = Arrangement.spacedBy(6.mpx),
        ) {
            items(days, key = { it.toEpochDay() }) { day ->
                val selected = day == shown
                StageRow(
                    onClick = { onPick(day) },
                    height = 72.mpx,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (selected) Modifier.focusRequester(shownRow).background(accent.accent.copy(alpha = 0.12f), RoundedCornerShape(StageRadii.Row)) else Modifier),
                ) { focused ->
                    val text = if (focused) accent.onAccent else StageColors.Text
                    Text(dayLabel(day, today), style = stageText(21, 700), color = text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val games = gamesOn(day)
                    if (games > 0) {
                        Text(
                            java.text.NumberFormat.getIntegerInstance(locale).format(games),
                            style = stageText(18, 700),
                            color = if (focused) accent.onAccent else accent.accent,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun Message(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = stageText(20, 500),
        color = StageColors.Muted,
        maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = 12.mpx),
    )
}

/** "PSG vs Lyon" for a game, the event's own name otherwise. */
@Composable
internal fun eventTitle(event: SportsEvent): String {
    val home = event.home
    val away = event.away
    return if (home != null && away != null) {
        stringResource(R.string.tifo_sports_versus, home.shortName, away.shortName)
    } else {
        event.name
    }
}

@Composable
internal fun TeamLogo(url: String?, size: androidx.compose.ui.unit.Dp = 36.mpx) {
    if (url == null) {
        Spacer(Modifier.size(size))
    } else {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size))
    }
}

/** A favourite team's or channel's mark beside its name. */
@Composable
internal fun FavoriteStar(focused: Boolean) {
    val accent = stageAccent
    OwnTVIcon(OwnTVIcon.STAR, if (focused) accent.onAccent else accent.accent, Modifier.size(18.mpx), filled = true)
}

/** "Next games: Tomorrow": an action row where the games would be. */
@Composable
private fun SportsActionRow(text: String, icon: OwnTVIcon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = stageAccent
    StageRow(
        onClick = onClick,
        height = 64.mpx,
        modifier = modifier.fillMaxWidth(0.56f),
    ) { focused ->
        val color = if (focused) accent.onAccent else accent.accent
        OwnTVIcon(icon, color, Modifier.size(22.mpx))
        Text(text, style = stageText(19, 700), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Focuses this element when [target] names [key], once it is on screen, then calls [onDone]. */
@Composable
internal fun Modifier.refocusWhen(key: String, target: String?, onDone: () -> Unit): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(target) {
        if (target != key) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        onDone()
    }
    return focusRequester(requester)
}

internal fun eventKey(event: SportsEvent): String = "e:" + event.competition.name + event.id

internal fun searchKey(broadcaster: String): String = "q:$broadcaster"

internal fun channelKey(channelId: Long): String = "c:$channelId"

internal fun isToday(ms: Long): Boolean {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
}

/** Weekday and day: "Sat 11 Oct". */
internal const val DAY_SKELETON = "EEEdMMM"

/** A card's ground under the league's logo: a neutral near-black, the app's own surfaces. */
private val CardBackground = Color(0xFF151A24)

private val CARD_WIDTH = 360.mpx
private val CARD_HEIGHT = 203.mpx

/** The day button, where the cursor returns when the day picker closes. */
private const val DAY_BUTTON_KEY = "day"

/** The Teams button, where the cursor returns when the favourites picker closes. */
private const val TEAMS_BUTTON_KEY = "teams"
