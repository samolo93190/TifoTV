package tv.own.owntv.tifo.sports

import androidx.room.useReaderConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

/** A channel that probably shows the event, and why we think so. */
data class SportsChannelMatch(
    val channel: ChannelEntity,
    val score: Int,
    /** The guide programme that matched, or null for a match on the channel's name alone. */
    val programmeTitle: String?,
    val programmeStartMs: Long?,
    val programmeStopMs: Long?,
)

/**
 * Finds the active playlist's channels that show an event: first through the guide (a programme at
 * the event's time whose title names the teams or the event), then through the channel names of
 * the competition's usual broadcasters.
 *
 * Reads core's database and never writes to it. The two queries name core's tables and columns
 * directly, since core has no DAO call that searches programme titles.
 */
class SportsChannelFinder(
    private val db: OwnTVDatabase,
    private val channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
) {

    suspend fun find(event: SportsEvent, nowMs: Long): List<SportsChannelMatch> = withContext(Dispatchers.IO) {
        val sourceIds = activeProfileSources(settings, sourceDao).first().liveSourceIds
        if (sourceIds.isEmpty()) return@withContext emptyList()

        val best = HashMap<Long, SportsChannelMatch>()
        fun offer(match: SportsChannelMatch) {
            val old = best[match.channel.id]
            if (old == null || match.score > old.score) best[match.channel.id] = match
        }

        // 1. The guide.
        val (from, to) = window(event, nowMs)
        val programmes = programmesMatching(SportsMatching.searchTerms(event), from, to)
            .mapNotNull { p ->
                val score = SportsMatching.score(event, p.title, p.categories)
                if (score > 0) p to score else null
            }
        if (programmes.isNotEmpty()) {
            val byKey = programmes.groupBy { it.first.epgKey }
            for ((channelId, epgKey) in channelsForGuideKeys(byKey.keys, sourceIds)) {
                val channel = channelDao.getById(channelId) ?: continue
                val (programme, score) = byKey[epgKey]?.maxByOrNull { it.second } ?: continue
                offer(SportsChannelMatch(channel, score, programme.title, programme.startMs, programme.stopMs))
            }
        }

        // 2. The broadcasters' channel names, below anything the guide found.
        val broadcasterNames = (event.competition.broadcasters + event.broadcasts).distinctBy { it.lowercase() }
        for (name in broadcasterNames) {
            runCatching { channelDao.searchList(name, sourceIds, BROADCASTER_CHANNELS) }.getOrDefault(emptyList())
                .forEach { offer(SportsChannelMatch(it, SportsMatching.BROADCASTER, null, null, null)) }
        }

        best.values
            .sortedWith(compareByDescending<SportsChannelMatch> { it.score }.thenBy { it.channel.name.lowercase() })
            .take(MAX_RESULTS)
    }

    /** The guide window in which a programme can show the event. */
    private fun window(event: SportsEvent, nowMs: Long): Pair<Long, Long> = when {
        // A tournament runs for days: whatever airs in the next twelve hours (or from its first day).
        event.competition.kind == SportKind.TOURNAMENT -> {
            val start = maxOf(nowMs, event.startMs)
            start to start + 12 * HOUR
        }
        // A programme that covers the start of the event, or the event so far if it is under way.
        else -> event.startMs + 10 * MINUTE to event.startMs + HOUR
    }

    private data class Programme(val epgKey: String, val title: String, val categories: String?, val startMs: Long, val stopMs: Long)

    /** Programmes airing over [from]..[to] whose title holds any of [terms]. */
    private suspend fun programmesMatching(terms: List<String>, from: Long, to: Long): List<Programme> {
        if (terms.isEmpty()) return emptyList()
        val likes = terms.joinToString(" OR ") { "title LIKE ?" }
        val sql = "SELECT epgChannelId, title, categories, startMs, stopMs FROM epg_programmes " +
            "WHERE startMs <= ? AND stopMs >= ? AND ($likes) LIMIT $MAX_PROGRAMMES"
        return runCatching {
            db.useReaderConnection { connection ->
                connection.usePrepared(sql) { st ->
                    st.bindLong(1, to)
                    st.bindLong(2, from)
                    terms.forEachIndexed { i, term -> st.bindText(i + 3, "%" + term + "%") }
                    buildList {
                        while (st.step()) {
                            add(
                                Programme(
                                    epgKey = st.getText(0).trim().lowercase(),
                                    title = st.getText(1),
                                    categories = if (st.isNull(2)) null else st.getText(2),
                                    startMs = st.getLong(3),
                                    stopMs = st.getLong(4),
                                ),
                            )
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Channel id to guide key, for the playlist channels whose guide id is one of [keys]. */
    private suspend fun channelsForGuideKeys(keys: Set<String>, sourceIds: List<Long>): List<Pair<Long, String>> {
        if (keys.isEmpty()) return emptyList()
        val keyList = keys.toList()
        val sql = "SELECT id, LOWER(TRIM(epgChannelId)) FROM channels " +
            "WHERE sourceId IN (" + sourceIds.joinToString(",") { "?" } + ") " +
            "AND LOWER(TRIM(epgChannelId)) IN (" + keyList.joinToString(",") { "?" } + ") LIMIT $MAX_RESULTS"
        return runCatching {
            db.useReaderConnection { connection ->
                connection.usePrepared(sql) { st ->
                    sourceIds.forEachIndexed { i, id -> st.bindLong(i + 1, id) }
                    keyList.forEachIndexed { i, key -> st.bindText(sourceIds.size + i + 1, key) }
                    buildList { while (st.step()) add(st.getLong(0) to st.getText(1)) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val MAX_PROGRAMMES = 400
        const val MAX_RESULTS = 40
        const val BROADCASTER_CHANNELS = 8
    }
}
