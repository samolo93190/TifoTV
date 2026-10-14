package tv.own.owntv.tifo.sports

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.recording.RecordingManager
import tv.own.owntv.core.settings.SettingsRepository

data class SportsUiState(
    val loading: Boolean = true,
    /** Every competition failed to load (offline, or ESPN changed its API). */
    val failed: Boolean = false,
    val events: List<SportsEvent> = emptyList(),
    /** The open game, whose page shows over the rows. */
    val selected: SportsEvent? = null,
    val matching: Boolean = false,
    val matches: List<SportsChannelMatch> = emptyList(),
    /** Who officially shows the open game, by country code ("US", "FR", "GB"). */
    val official: Map<String, List<String>> = emptyMap(),
    /** ESPN's summary of the open game: timeline, lineups, stats… Null until loaded, or if it failed. */
    val summary: GameSummary? = null,
    val summaryLoading: Boolean = false,
    /** The day the league rows show; null = today, following midnight. */
    val day: LocalDate? = null,
    /** A picked day's games are still being fetched. */
    val dayLoading: Boolean = false,
) {
    fun shownDay(today: LocalDate): LocalDate = day ?: today

    /** Every game on the [shownDay], whatever its league. */
    fun dayEvents(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<SportsEvent> =
        events.filter { SportsSchedule.isOn(it, shownDay(today), today, zone) }

    /**
     * The rows of the Sports page: "My teams" first (the [favorites]' games, every day), then one
     * row per league with the [shownDay]'s games, in [Competition] order. Empty rows are left out;
     * each keeps [events]' order (live, results, upcoming).
     */
    fun rows(
        favorites: Set<String>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SportsRow> {
        val mine = events.filter { e ->
            SportsFavorites.isFavorite(favorites, e.competition, e.home) || SportsFavorites.isFavorite(favorites, e.competition, e.away)
        }
        val day = dayEvents(today, zone)
        return buildList {
            if (mine.isNotEmpty()) add(SportsRow(null, mine))
            for (c in Competition.entries) {
                val games = day.filter { it.competition == c }
                if (games.isNotEmpty()) add(SportsRow(c, games))
            }
        }
    }
}

/** A row of game cards: a league's, or with no [competition], the favourite teams'. */
data class SportsRow(val competition: Competition?, val events: List<SportsEvent>) {
    val key: String get() = competition?.name ?: "mine"
}

/** A league's teams in the favourites picker. */
sealed interface TeamList {
    data object Loading : TeamList
    data object Failed : TeamList
    data class Loaded(val teams: List<SportsTeam>) : TeamList
}

class SportsViewModel(
    http: OkHttpClient,
    db: OwnTVDatabase,
    channelDao: ChannelDao,
    sourceDao: SourceDao,
    categoryDao: CategoryDao,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
    private val recordings: RecordingManager,
) : ViewModel() {

    private val scoreboard = EspnScoreboard(http)
    private val finder = SportsChannelFinder(db, channelDao, sourceDao, categoryDao, settings, http)

    private val _state = MutableStateFlow(SportsUiState())
    val state: StateFlow<SportsUiState> = _state.asStateFlow()

    private var matchJob: Job? = null

    /**
     * Where the cursor goes when the tab comes back: the broadcaster search it left for, or the
     * channel it played full screen. The tab is rebuilt when the player closes, so it starts there.
     */
    private var returnFocus: String? = null

    /** The game list's scroll position when the tab was last left, to come back to the same rows. */
    var listIndex = 0
    var listOffset = 0

    fun setReturnFocus(key: String) { returnFocus = key }

    fun takeReturnFocus(): String? = returnFocus.also { returnFocus = null }

    /**
     * Keeps the scores fresh while the Sports tab is on screen: every 30 seconds while a game is
     * live, every 5 minutes otherwise. Call from a LaunchedEffect so it stops when the tab closes.
     */
    suspend fun refreshWhileVisible() {
        while (true) {
            refresh()
            val live = _state.value.events.any { it.state == EventState.LIVE }
            delay(if (live) LIVE_REFRESH_MS else IDLE_REFRESH_MS)
        }
    }

    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        val results = coroutineScope {
            Competition.entries.map { competition ->
                async {
                    runCatching { scoreboard.load(competition, now, _state.value.day) }
                        .onFailure { Log.w(TAG, "scoreboard ${competition.name} failed", it) }
                        .getOrNull()
                }
            }.awaitAll()
        }
        val events = results.filterNotNull().flatten()
            .filter { it.state != EventState.FINISHED || now - it.startMs < FINISHED_KEEP_MS }
            .sortedWith(eventOrder)
        _state.update { s ->
            s.copy(
                loading = false,
                dayLoading = false,
                failed = results.all { it == null },
                events = events,
                // Keep the open game current: its score and status change while it is shown.
                selected = s.selected?.let { sel -> events.firstOrNull { it.id == sel.id } ?: sel },
            )
        }
    }

    /**
     * Shows [day]'s games in the league rows (today when it is today). A day past the week already
     * loaded is fetched now.
     */
    fun setDay(day: LocalDate) {
        val today = LocalDate.now()
        val picked = day.takeIf { it != today }
        if (picked == _state.value.day) return
        matchJob?.cancel()
        val known = !day.isBefore(today.minusDays(1)) && !day.isAfter(today.plusDays(LOADED_DAYS_AHEAD))
        _state.update {
            it.copy(day = picked, dayLoading = !known)
        }
        if (!known) viewModelScope.launch { refresh() }
    }

    private val teamLists = EspnTeams(http)

    private val _teams = MutableStateFlow<Map<Competition, TeamList>>(emptyMap())

    /** Each league's teams for the favourites picker, loaded when the picker opens the league. */
    val teams: StateFlow<Map<Competition, TeamList>> = _teams.asStateFlow()

    fun loadTeams(competition: Competition) {
        val current = _teams.value[competition]
        if (current != null && current !is TeamList.Failed) return
        _teams.update { it + (competition to TeamList.Loading) }
        viewModelScope.launch {
            val list: TeamList = runCatching { teamLists.load(competition) }
                .onFailure { Log.w(TAG, "teams ${competition.name} failed", it) }
                .fold({ TeamList.Loaded(it) }, { TeamList.Failed })
            _teams.update { it + (competition to list) }
        }
    }

    private val summaries = EspnSummary(http)
    private var summaryJob: Job? = null

    /**
     * Opens [event]'s page and looks for the channels that show it, and ESPN's summary of it (kept
     * fresh while the game is live). [favoriteTeams] are [SportsFavorites.keys]: a favourite team's
     * channel goes first.
     */
    fun open(event: SportsEvent, favoriteTeams: Set<String> = emptySet()) {
        matchJob?.cancel()
        summaryJob?.cancel()
        _state.update {
            it.copy(
                selected = event, matching = true, matches = emptyList(), official = emptyMap(),
                summary = null, summaryLoading = event.competition.kind == SportKind.TEAM,
            )
        }
        matchJob = viewModelScope.launch {
            val found = runCatching { finder.find(event, System.currentTimeMillis(), favoriteTeams) }
                .onFailure { Log.w(TAG, "channel matching failed", it) }
                .getOrNull()
            _state.update {
                if (it.selected?.id == event.id) {
                    it.copy(matching = false, matches = found?.matches.orEmpty(), official = found?.official.orEmpty())
                } else {
                    it
                }
            }
        }
        summaryJob = viewModelScope.launch {
            while (true) {
                val summary = summaries.load(event)
                _state.update {
                    if (it.selected?.id == event.id) it.copy(summary = summary ?: it.summary, summaryLoading = false) else it
                }
                // The scoreboard keeps the open game's state current: a finished game is read once.
                val current = _state.value.selected?.takeIf { it.id == event.id } ?: break
                if (current.state == EventState.FINISHED) break
                delay(if (current.state == EventState.LIVE) LIVE_REFRESH_MS else UPCOMING_SUMMARY_MS)
            }
        }
    }

    /** Closes the open game's page. */
    fun close() {
        matchJob?.cancel()
        summaryJob?.cancel()
        _state.update { it.copy(selected = null, matching = false, matches = emptyList(), official = emptyMap(), summary = null, summaryLoading = false) }
    }

    /** This profile's recordings, so a channel knows whether its game is already set to record. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recordingRows: StateFlow<List<RecordingEntity>> =
        settings.activeProfileId
            .flatMapLatest { pid -> if (pid < 0) flowOf(emptyList()) else recordings.observe(pid) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The recording already covering [match]'s guide programme, if there is one. */
    fun recordingFor(match: SportsChannelMatch, rows: List<RecordingEntity>): RecordingEntity? = rows.firstOrNull {
        it.channelId == match.channel.id && it.programmeStartMs == match.programmeStartMs &&
            it.status != RecordingStatus.CANCELLED
    }

    /**
     * Records the guide programme that shows the game on [match]'s channel, the way the guide's
     * Record does (with the user's padding), or cancels it when it is already set. Only for a
     * programme still to come: a game under way is watched, not scheduled.
     */
    fun toggleRecording(match: SportsChannelMatch) {
        val title = match.programmeTitle ?: return
        val start = match.programmeStartMs ?: return
        val stop = match.programmeStopMs ?: return
        viewModelScope.launch {
            recordingFor(match, recordingRows.value)?.let { recordings.cancel(it); return@launch }
            if (start <= System.currentTimeMillis()) return@launch
            val preferred = settings.activeProfileId.first()
            val pid = if (preferred >= 0) profileDao.resolveExistingProfileId(preferred) else null
            if (pid == null) return@launch
            val channel = match.channel
            val window = recordings.windowFor(start, stop)
            recordings.schedule(
                RecordingEntity(
                    profileId = pid,
                    sourceId = channel.sourceId,
                    channelId = channel.id,
                    channelName = channel.name,
                    channelIconUrl = channel.logoUrl,
                    epgChannelId = channel.epgChannelId,
                    streamUrl = channel.streamUrl,
                    httpHeaders = channel.httpHeaders,
                    title = title,
                    description = null,
                    programmeStartMs = start,
                    programmeStopMs = stop,
                    startMs = window.first,
                    stopMs = window.last,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "TifoSports"
        const val LIVE_REFRESH_MS = 30_000L
        const val IDLE_REFRESH_MS = 5 * 60_000L

        /** An open game still to come: its lineups appear about an hour before kick-off. */
        const val UPCOMING_SUMMARY_MS = 2 * 60_000L

        /** The days ahead [EspnScoreboard.load] always fetches, less one for time zones. */
        const val LOADED_DAYS_AHEAD = 6L

        /** Finished games stay listed for a day, for the result. */
        const val FINISHED_KEEP_MS = 24 * 60 * 60_000L

        /** Live first, then results newest first, then upcoming soonest first. */
        val eventOrder = Comparator<SportsEvent> { a, b ->
            val rank = { e: SportsEvent -> when (e.state) { EventState.LIVE -> 0; EventState.FINISHED -> 1; EventState.UPCOMING -> 2 } }
            val byState = rank(a).compareTo(rank(b))
            when {
                byState != 0 -> byState
                a.state == EventState.FINISHED -> b.startMs.compareTo(a.startMs)
                else -> a.startMs.compareTo(b.startMs)
            }
        }
    }
}
