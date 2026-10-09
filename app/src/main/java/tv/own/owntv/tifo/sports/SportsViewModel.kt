package tv.own.owntv.tifo.sports

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.settings.SettingsRepository

data class SportsUiState(
    val loading: Boolean = true,
    /** Every competition failed to load (offline, or ESPN changed its API). */
    val failed: Boolean = false,
    /** Null = all competitions. */
    val filter: Competition? = null,
    val events: List<SportsEvent> = emptyList(),
    val selected: SportsEvent? = null,
    val matching: Boolean = false,
    val matches: List<SportsChannelMatch> = emptyList(),
) {
    val visibleEvents: List<SportsEvent> get() = if (filter == null) events else events.filter { it.competition == filter }
}

class SportsViewModel(
    http: OkHttpClient,
    db: OwnTVDatabase,
    channelDao: ChannelDao,
    sourceDao: SourceDao,
    settings: SettingsRepository,
) : ViewModel() {

    private val scoreboard = EspnScoreboard(http)
    private val finder = SportsChannelFinder(db, channelDao, sourceDao, settings)

    private val _state = MutableStateFlow(SportsUiState())
    val state: StateFlow<SportsUiState> = _state.asStateFlow()

    private var matchJob: Job? = null

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
                    runCatching { scoreboard.load(competition, now) }
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
                failed = results.all { it == null },
                events = events,
                // Keep the open game current: its score and status change while it is shown.
                selected = s.selected?.let { sel -> events.firstOrNull { it.id == sel.id } ?: sel },
            )
        }
    }

    fun setFilter(competition: Competition?) = _state.update { it.copy(filter = competition) }

    /** Opens [event] on the right and looks for the channels that show it. */
    fun select(event: SportsEvent) {
        _state.update { it.copy(selected = event, matching = true, matches = emptyList()) }
        matchJob?.cancel()
        matchJob = viewModelScope.launch {
            val matches = runCatching { finder.find(event, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "channel matching failed", it) }
                .getOrDefault(emptyList())
            _state.update { if (it.selected?.id == event.id) it.copy(matching = false, matches = matches) else it }
        }
    }

    private companion object {
        const val TAG = "TifoSports"
        const val LIVE_REFRESH_MS = 30_000L
        const val IDLE_REFRESH_MS = 5 * 60_000L

        /** Finished games stay listed for a day, for the result. */
        const val FINISHED_KEEP_MS = 24 * 60 * 60_000L

        /** Live first, then upcoming soonest first, then results newest first. */
        val eventOrder = Comparator<SportsEvent> { a, b ->
            val rank = { e: SportsEvent -> when (e.state) { EventState.LIVE -> 0; EventState.UPCOMING -> 1; EventState.FINISHED -> 2 } }
            val byState = rank(a).compareTo(rank(b))
            when {
                byState != 0 -> byState
                a.state == EventState.FINISHED -> b.startMs.compareTo(a.startMs)
                else -> a.startMs.compareTo(b.startMs)
            }
        }
    }
}
