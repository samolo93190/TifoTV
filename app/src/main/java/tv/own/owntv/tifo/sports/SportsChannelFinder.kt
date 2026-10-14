package tv.own.owntv.tifo.sports

import androidx.room.useReaderConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

/** A channel that probably shows the event, and why we think so. */
data class SportsChannelMatch(
    val channel: ChannelEntity,
    /** Its points: the most goes first, and is what Watch plays. */
    val score: Int,
    /** The guide programme that matched, or null for a match on the channel's name alone. */
    val programmeTitle: String?,
    val programmeStartMs: Long?,
    val programmeStopMs: Long?,
    /** One of the user's favourite channels. */
    val favorite: Boolean = false,
    /** Its points before the country's and the favourite's extras: which rule found it. */
    val base: Int = score,
    /** The provider's category it is filed under ("US| PARAMOUNT+ PPV"). */
    val category: String? = null,
)

/**
 * A game's channels, and who officially shows it, by country code ("US", "FR", "GB"): ESPN's or
 * NBA.com's US channels, FotMob's stations (apps included, as FotMob lists them).
 */
data class SportsMatches(val matches: List<SportsChannelMatch>, val official: Map<String, List<String>>)

/**
 * Finds the active playlist's channels that show an event, and gives each points (see
 * [SportsMatching] and docs/MATCHING.md), strongest first:
 *  - a channel named after the game ("NFL | Bills vs Rams"), 600;
 *  - the game's official TV list: FotMob's channel in its own country for football, 500, and
 *    NBA.com's national then local channels for the NBA, 700 and up (before event channels);
 *  - the guide showing the game (both teams or players) around its time, 500, or for tennis the
 *    tournament, 450;
 *  - guesses, only from US, Canadian and French channels and never an event slot: the feeds of
 *    a streaming service FotMob lists (Ligue 1+), 300; ESPN's network on its US channels, 200;
 *    and when nothing else was found, the league's usual channels, 100.
 * A US channel gets 50 more, a Canadian one 49, and a favourite channel that shows the game
 * itself (450 or more) 3000. Separators, empty event slots, other sports' channels, channels
 * named after another game or date, and channels whose guide shows another game are never offered.
 *
 * Reads core's database and never writes to it. The queries name core's tables and columns
 * directly, since core has no DAO call that searches programme titles.
 */
class SportsChannelFinder(
    private val db: OwnTVDatabase,
    private val channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val settings: SettingsRepository,
    http: OkHttpClient,
) {

    private val fotMob = FotMobTv(http)
    private val nba = NbaTv(http)

    private class Found(val channel: ChannelEntity, val base: Int, val programme: Programme?, val category: String?)

    /** [favoriteTeams] are [SportsFavorites.keys]: a favourite team's local NBA channel goes first. */
    suspend fun find(event: SportsEvent, nowMs: Long, favoriteTeams: Set<String> = emptySet()): SportsMatches = withContext(Dispatchers.IO) {
        val nbaListed = runCatching { nba.broadcastsFor(event, nowMs) }.getOrNull()
        val fotMobListed = runCatching { fotMob.stationsFor(event, event.competition.fotMobCountries, nowMs) }.getOrDefault(emptyMap())
        val official = officialBroadcasters(event, nbaListed, fotMobListed)
        val sourceIds = activeProfileSources(settings, sourceDao).first().liveSourceIds
        if (sourceIds.isEmpty()) return@withContext SportsMatches(emptyList(), official)

        val categories = HashMap<Long, String?>()
        suspend fun categoryOf(channel: ChannelEntity): String? {
            val id = channel.categoryId ?: return null
            if (id !in categories) categories[id] = runCatching { categoryDao.getById(id)?.name }.getOrNull()
            return categories[id]
        }

        val best = HashMap<Long, Found>()

        /**
         * Keeps [channel] at [base] points unless it can't show the game: a separator, an empty
         * slot, a channel or category of another sport, or a channel named after another game.
         * [guess]es (rules 7–9) only come from the countries a name can be trusted in, and never
         * from event slots.
         */
        suspend fun offer(channel: ChannelEntity, base: Int, programme: Programme? = null, guess: Boolean = false) {
            val name = channel.name
            if (SportsMatching.isSeparator(name) || SportsMatching.isPlaceholder(name)) return
            if (SportsMatching.mentionsOtherSport(event, SportsMatching.normalize(name))) return
            val category = categoryOf(channel)
            if (category != null && SportsMatching.isAnotherSportsCategory(event, category)) return
            if (base != SportsMatching.EVENT_CHANNEL && SportsMatching.namesAGame(name) &&
                SportsMatching.channelScore(event, name, category) == 0
            ) return
            if (guess && (SportsMatching.isEventSlot(name) || SportsMatching.countryOf(name) !in SportsMatching.GUESS_COUNTRIES)) return
            val old = best[channel.id]
            if (old == null || base > old.base) {
                best[channel.id] = Found(channel, base, programme ?: old?.programme, category)
            }
        }

        // 1. Channels named after the game, which providers often leave without a guide.
        // Wide: a city ("Los Angeles", "Denver") also names hundreds of local stations, which must
        // not crowd the game's own channels out of the pre-filter.
        for ((channelId, name) in channelsNamed(SportsMatching.channelSearchTerms(event), sourceIds, MAX_EVENT_NAMED_CHANNELS)) {
            if (SportsMatching.channelScore(event, name) <= 0) continue
            val channel = channelDao.getById(channelId) ?: continue
            if (SportsMatching.channelScore(event, name, categoryOf(channel)) <= 0) continue
            offer(channel, SportsMatching.EVENT_CHANNEL)
        }

        // 2. NBA.com: the national channel, then the favourite team's local one, the home team's,
        // the away team's. US channels only, and never an event slot.
        if (nbaListed != null) {
            val homeFavorite = SportsFavorites.isFavorite(favoriteTeams, event.competition, event.home)
            val awayFavorite = SportsFavorites.isFavorite(favoriteTeams, event.competition, event.away)
            val ranked = nbaListed.national.map { it to 3 } +
                nbaListed.home.map { it to if (homeFavorite) 2 else 1 } +
                nbaListed.away.map { it to if (awayFavorite && !homeFavorite) 2 else 0 }
            for ((station, rank) in ranked) {
                for (channel in usChannelsOf(station, sourceIds)) {
                    if (SportsMatching.isEventSlot(channel.name)) continue
                    offer(channel, SportsMatching.NBA_LISTED + rank * SportsMatching.NBA_RANK)
                }
            }
        }

        // 3. The guide: a programme naming both teams (or players) around the game's time; for
        // tennis, one naming the tournament.
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
                val (programme, score) = byKey[epgKey]
                    ?.maxWithOrNull(compareBy<Pair<Programme, Int>> { it.second }.thenByDescending { kotlin.math.abs(it.first.startMs - event.startMs) })
                    ?: continue
                offer(channel, score, programme)
            }
        }

        // 4. FotMob's TV guide: the exact channel the game is on in France (every football
        // competition) and in the competition's own country ("Canal+ Live 3", "Sky Sports Main
        // Event", "M+ LaLiga"), looked for among that country's channels only. A streaming service
        // it lists without a channel ("Ligue 1+") brings that service's numbered and match-day feeds.
        for ((fotMobCountry, stations) in fotMobListed) {
            val country = SportsMatching.playlistCountry(fotMobCountry)
            for (station in stations) {
                val word = searchWord(station.name) ?: continue
                for ((channelId, name) in channelsNamedIn(word, country, sourceIds)) {
                    if (SportsMatching.countryOf(name) != country) continue
                    val listed = station.isTv && SportsMatching.isListedChannel(name, station.name, country)
                    val feed = !station.isTv && SportsMatching.isServiceFeed(name, station.name)
                    if (!listed && !feed) continue
                    val channel = channelDao.getById(channelId) ?: continue
                    if (listed) offer(channel, SportsMatching.LISTED_CHANNEL) else offer(channel, SportsMatching.LISTED_SERVICE, guess = true)
                }
            }
        }

        // 5. ESPN's network for the game (CBS, NBC, Peacock…) on its US channels. A "+" app
        // (Paramount+, ESPN+) is no channel: its games only show as event channels.
        for (network in event.broadcasts.map { it.trim() }.filter { it.isNotEmpty() && !it.endsWith("+") }.distinctBy { it.lowercase() }) {
            for (channel in usChannelsOf(network, sourceIds)) offer(channel, SportsMatching.NETWORK, guess = true)
        }

        // 6. Last resort, only when nothing above found anything (for the US leagues, only when
        // ESPN lists no network at all): the league's usual channels.
        val noNetwork = !event.competition.usesEspnBroadcasts || event.broadcasts.isEmpty()
        if (best.isEmpty() && noNetwork) {
            for (name in event.competition.broadcasters.distinctBy { it.lowercase() }) {
                channelsNamed(SportsMatching.broadcasterSearchTerms(name), sourceIds, BROADCASTER_SEARCH)
                    .filter { (_, n) -> SportsMatching.namesBroadcaster(n, name, event.competition) }
                    .take(BROADCASTER_SEARCH)
                    .mapNotNull { (id, _) -> channelDao.getById(id) }
                    .filter { !SportsMatching.isEventSlot(it.name) && SportsMatching.countryOf(it.name) in SportsMatching.GUESS_COUNTRIES }
                    .sortedBy { it.name.length }
                    .take(BROADCASTER_CHANNELS)
                    .forEach { offer(it, SportsMatching.LAST_RESORT, guess = true) }
            }
        }

        // A channel whose guide shows another game at kick-off doesn't show this one.
        val unconfirmed = best.values.filter { it.programme == null }.map { it.channel }
        for (channelId in channelsShowingAnotherGame(event, unconfirmed)) best.remove(channelId)

        val favoriteIds = favoriteChannels(sourceIds).mapTo(HashSet()) { it.id }
        best.values
            .map { found ->
                val favorite = found.channel.id in favoriteIds
                SportsChannelMatch(
                    channel = found.channel,
                    score = SportsMatching.finalScore(found.base, found.channel.name, favorite),
                    programmeTitle = found.programme?.title,
                    programmeStartMs = found.programme?.startMs,
                    programmeStopMs = found.programme?.stopMs,
                    favorite = favorite,
                    base = found.base,
                    category = found.category,
                )
            }
            .sortedWith(compareByDescending<SportsChannelMatch> { it.score }.thenBy { it.channel.name.lowercase() })
            .take(MAX_RESULTS)
            .let { SportsMatches(it, official) }
    }

    /** Who officially shows [event], by country: the US first, then FotMob's countries. */
    private fun officialBroadcasters(event: SportsEvent, nba: NbaBroadcasts?, fotMob: Map<String, List<FotMobStation>>): Map<String, List<String>> {
        val us = nba?.all?.takeIf { it.isNotEmpty() } ?: event.broadcasts.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        return buildMap {
            if (us.isNotEmpty()) put("US", us)
            for (country in event.competition.fotMobCountries) {
                fotMob[country]?.map { it.name }?.takeIf { it.isNotEmpty() }?.let { put(country, it) }
            }
        }
    }

    /**
     * The US channels of [station] (a network, a regional sports network, a station's call sign),
     * the network's own feed first ("US| CBS HD" before "US| CBS 2 NEW YORK (WCBS)").
     */
    private suspend fun usChannelsOf(station: String, sourceIds: List<Long>): List<ChannelEntity> {
        val callSign = CALL_SIGN.matches(station)
        val terms = if (callSign) listOf(station) else SportsMatching.broadcasterSearchTerms(station)
        val key = SportsMatching.tvKey(station)
        return channelsNamed(terms, sourceIds, MAX_EVENT_NAMED_CHANNELS)
            .filter { (_, name) ->
                SportsMatching.countryOf(name) == "US" && if (callSign) {
                    SportsMatching.containsTerm(SportsMatching.normalize(name), station)
                } else {
                    SportsMatching.broadcasterMatches(name, station)
                }
            }
            .sortedWith(compareByDescending<Pair<Long, String>> { SportsMatching.tvKey(it.second) == key }.thenBy { it.second.length })
            .take(NETWORK_CHANNELS)
            .mapNotNull { (id, _) -> channelDao.getById(id) }
    }

    /** The active profile's favourite channels in [sourceIds]. */
    private suspend fun favoriteChannels(sourceIds: List<Long>): List<ChannelEntity> {
        val profileId = settings.activeProfileId.first()
        if (profileId < 0) return emptyList()
        val sources = sourceIds.toHashSet()
        return runCatching { channelDao.favoritesListAlpha(profileId).first() }
            .getOrDefault(emptyList())
            .filter { it.sourceId in sources }
    }

    /**
     * The guide window in which a programme can show the event: from 30 minutes before kick-off
     * to 2h30 after; for a tennis match, whose time is only an estimate, 1h30 before to 5 hours
     * after.
     */
    private fun window(event: SportsEvent, nowMs: Long): Pair<Long, Long> = when {
        event.competition.kind == SportKind.TOURNAMENT && event.home != null ->
            event.startMs - 90 * MINUTE to event.startMs + 5 * HOUR
        // A tournament listed whole: whatever airs in the next twelve hours (or from its first day).
        event.competition.kind == SportKind.TOURNAMENT -> {
            val start = maxOf(nowMs, event.startMs)
            start to start + 12 * HOUR
        }
        else -> event.startMs - 30 * MINUTE to event.startMs + 150 * MINUTE
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

    /** The ids of [channels] whose guide shows another game at [event]'s kick-off. */
    private suspend fun channelsShowingAnotherGame(event: SportsEvent, channels: List<ChannelEntity>): Set<Long> {
        val byKey = channels.mapNotNull { ch -> ch.epgChannelId?.trim()?.takeIf { it.isNotEmpty() }?.let { it to ch.id } }
            .groupBy({ it.first.lowercase() }, { it.second })
        if (byKey.isEmpty()) return emptySet()
        val keys = byKey.keys.toList()
        val at = event.startMs + 10 * MINUTE
        val sql = "SELECT LOWER(TRIM(epgChannelId)), title, categories FROM epg_programmes " +
            "WHERE startMs <= ? AND stopMs > ? AND LOWER(TRIM(epgChannelId)) IN (" + keys.joinToString(",") { "?" } + ")"
        val showing = runCatching {
            db.useReaderConnection { connection ->
                connection.usePrepared(sql) { st ->
                    st.bindLong(1, at)
                    st.bindLong(2, at)
                    keys.forEachIndexed { i, key -> st.bindText(i + 3, key) }
                    buildList { while (st.step()) add(Triple(st.getText(0), st.getText(1), if (st.isNull(2)) null else st.getText(2))) }
                }
            }
        }.getOrDefault(emptyList())
        return showing
            .filter { (_, title, categories) -> SportsMatching.isAnotherGame(event, title, categories) }
            .flatMapTo(HashSet()) { (key, _, _) -> byKey[key].orEmpty() }
    }

    /** Id and name of the playlist channels whose name holds any of [terms]. */
    private suspend fun channelsNamed(terms: List<String>, sourceIds: List<Long>, limit: Int = MAX_NAMED_CHANNELS): List<Pair<Long, String>> {
        if (terms.isEmpty()) return emptyList()
        val sql = "SELECT id, name FROM channels " +
            "WHERE sourceId IN (" + sourceIds.joinToString(",") { "?" } + ") " +
            "AND (" + terms.joinToString(" OR ") { "name LIKE ?" } + ") LIMIT $limit"
        return runCatching {
            db.useReaderConnection { connection ->
                connection.usePrepared(sql) { st ->
                    sourceIds.forEachIndexed { i, id -> st.bindLong(i + 1, id) }
                    terms.forEachIndexed { i, term -> st.bindText(sourceIds.size + i + 1, "%" + term + "%") }
                    buildList { while (st.step()) add(st.getLong(0) to st.getText(1)) }
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * The word a station is looked up by in the database: its first word of three letters or more
     * ("Canal" for "Canal+ Live 3", "LALIGA" for "M+ LALIGA", "Ligue" for "Ligue 1+"), which every
     * playlist spelling of it keeps ("CANAL+LIVE 3", "M+ LALIGA TV", "LIGUE1 + 3").
     */
    private fun searchWord(station: String): String? =
        station.split(NOT_ALNUM).firstOrNull { it.length >= 3 }

    /** Id and name of the playlist channels named with [word] whose name starts with [country]. */
    private suspend fun channelsNamedIn(word: String, country: String, sourceIds: List<Long>): List<Pair<Long, String>> {
        val sql = "SELECT id, name FROM channels " +
            "WHERE sourceId IN (" + sourceIds.joinToString(",") { "?" } + ") " +
            "AND name LIKE ? AND (name LIKE ? OR name LIKE ?) LIMIT $MAX_EVENT_NAMED_CHANNELS"
        return runCatching {
            db.useReaderConnection { connection ->
                connection.usePrepared(sql) { st ->
                    sourceIds.forEachIndexed { i, id -> st.bindLong(i + 1, id) }
                    st.bindText(sourceIds.size + 1, "%$word%")
                    st.bindText(sourceIds.size + 2, "$country%")
                    st.bindText(sourceIds.size + 3, "[$country]%")
                    buildList { while (st.step()) add(st.getLong(0) to st.getText(1)) }
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
        const val MAX_RESULTS = 80
        const val MAX_NAMED_CHANNELS = 400

        const val MAX_EVENT_NAMED_CHANNELS = 3000
        const val BROADCASTER_CHANNELS = 8

        /** A network's US channels offered, its own feed first: "CBS" alone is hundreds of affiliates. */
        const val NETWORK_CHANNELS = 6

        /** Read for a broadcaster before event slots and other countries' channels are left out. */
        const val BROADCASTER_SEARCH = 60

        val NOT_ALNUM = Regex("[^\\p{L}\\p{N}]+")

        /** A US station's call sign: "KARE", "WPLG". */
        val CALL_SIGN = Regex("[KW][A-Z]{2,3}")
    }
}
