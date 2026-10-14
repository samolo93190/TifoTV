package tv.own.owntv.tifo.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who shows an NBA game, as NBA.com lists it: the [national] channels ("ESPN", "NBC", "Prime
 * Video") and each team's [home] and [away] local ones, by the names playlists go by ("NBC Sports
 * Boston", "MSG") or a local station's call sign ("KARE").
 */
data class NbaBroadcasts(val national: List<String>, val home: List<String>, val away: List<String>) {
    /** Everything, national first, for the game's broadcaster searches. */
    val all: List<String> get() = (national + home + away).distinctBy { it.lowercase() }
}

/** A game in NBA.com's schedule. */
data class NbaGame(
    val startMs: Long,
    /** "Phoenix Suns", "LA Clippers": city and name. */
    val homeTeam: String,
    val awayTeam: String,
    /** "Suns", "Clippers". */
    val homeName: String,
    val awayName: String,
    val broadcasts: NbaBroadcasts,
)

/**
 * NBA.com's season schedule (cdn.nba.com, what nba.com/schedule shows): for every game, the
 * national channel and each team's local one. ESPN lists the national networks only. Free, no key,
 * but unofficial: the CDN answers browsers only, and a failed read simply adds nothing.
 */
class NbaTv(private val http: OkHttpClient) {

    private class Cached(val fetchedAtMs: Long, val games: List<NbaGame>)

    @Volatile private var cached: Cached? = null
    private val lock = Mutex()

    /** The season's games, fetched at most every [TTL_MS]: the file is 5 MB. */
    suspend fun games(nowMs: Long): List<NbaGame> = lock.withLock {
        withContext(Dispatchers.IO) {
            val old = cached
            if (old != null && nowMs - old.fetchedAtMs < TTL_MS) return@withContext old.games
            runCatching {
                val request = Request.Builder().url(URL).apply { HEADERS.forEach { (k, v) -> header(k, v) } }.build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "NBA.com answered ${response.code}" }
                    parseNbaSchedule(response.body.string())
                }
            }.onSuccess { cached = Cached(nowMs, it) }
                .getOrElse { old?.games.orEmpty() }
        }
    }

    /** NBA.com's channels for [event]; null for anything but an NBA game it doesn't list. */
    suspend fun broadcastsFor(event: SportsEvent, nowMs: Long): NbaBroadcasts? {
        if (event.competition != Competition.NBA || event.home == null || event.away == null) return null
        return gameFor(event, games(nowMs))?.broadcasts
    }

    companion object {
        private const val URL = "https://cdn.nba.com/static/json/staticData/scheduleLeagueV2.json"
        private const val TTL_MS = 6 * 60 * 60_000L
        private const val HOUR = 60 * 60_000L

        private val HEADERS = listOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to "https://www.nba.com",
            "Referer" to "https://www.nba.com/",
            "Sec-Fetch-Site" to "same-site",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
        )

        /** [event] among [games]: the same two teams, tipping off within 3 hours of ESPN's time. */
        fun gameFor(event: SportsEvent, games: List<NbaGame>): NbaGame? {
            val home = event.home ?: return null
            val away = event.away ?: return null
            fun same(team: SportsTeam, full: String, name: String): Boolean {
                val terms = SportsMatching.teamTerms(team).map(SportsMatching::normalize)
                return SportsMatching.normalize(full) in terms || SportsMatching.normalize(name) in terms
            }
            return games
                .filter { kotlin.math.abs(it.startMs - event.startMs) <= 3 * HOUR }
                .filter { same(home, it.homeTeam, it.homeName) && same(away, it.awayTeam, it.awayName) }
                .minByOrNull { kotlin.math.abs(it.startMs - event.startMs) }
        }
    }
}

/**
 * NBA.com's schedule: `leagueSchedule.gameDates[].games[]`, each with `gameDateTimeUTC`, its
 * `homeTeam`/`awayTeam` (`teamCity`, `teamName`) and `broadcasters`: national TV, and each team's
 * local TV, whose abbreviation names every channel ("NBCSB/PCK-BOS", "KARE/DAZN-MIN").
 */
internal fun parseNbaSchedule(json: String): List<NbaGame> {
    val dates = JSONObject(json).optJSONObject("leagueSchedule")?.optJSONArray("gameDates") ?: return emptyList()
    return dates.objects().flatMap { it.optJSONArray("games").objects() }.mapNotNull { game ->
        runCatching {
            val start = parseEspnDate(game.optString("gameDateTimeUTC")) ?: return@runCatching null
            val home = game.optJSONObject("homeTeam") ?: return@runCatching null
            val away = game.optJSONObject("awayTeam") ?: return@runCatching null
            val b = game.optJSONObject("broadcasters")
            val national = b?.optJSONArray("nationalBroadcasters").objects()
                .filter { it.optString("broadcasterMedia").ifBlank { "tv" } == "tv" }
                .mapNotNull { it.optString("broadcasterDisplay").trim().takeIf { n -> n.isNotEmpty() && !isNbaApp(n) } }
            fun local(key: String) = b?.optJSONArray(key).objects()
                .flatMap { nbaLocalChannels(it.optString("broadcasterAbbreviation")) }
                .distinct()
            NbaGame(
                startMs = start,
                homeTeam = (home.optString("teamCity") + " " + home.optString("teamName")).trim(),
                awayTeam = (away.optString("teamCity") + " " + away.optString("teamName")).trim(),
                homeName = home.optString("teamName"),
                awayName = away.optString("teamName"),
                broadcasts = NbaBroadcasts(national.distinct(), local("homeTvBroadcasters"), local("awayTvBroadcasters")),
            )
        }.getOrNull()
    }
}

/**
 * The channels in a local broadcaster's abbreviation ("NBCSB/PCK-BOS" → "NBC Sports Boston",
 * "KARE/DAZN-MIN" → "KARE", "DAZN (YES)"'s "YES" → "YES Network"). Team apps ("Suns+", "AZFS App",
 * "PCK-BOS", "DAZN-MIN", "Local10+", "Mavs.com", "BlazerVision") are not channels and are skipped.
 */
internal fun nbaLocalChannels(abbreviation: String): List<String> =
    abbreviation.split('/').map { it.trim().replace(SUBCHANNEL, "") }.filter { it.isNotEmpty() && !isNbaApp(it) }.map { part ->
        NBA_LOCAL_NAMES[part.uppercase()] ?: part
    }.distinct()

private fun isNbaApp(name: String): Boolean = NBA_APP.containsMatchIn(name)

private val NBA_APP = Regex("""(?i)\bapp\b|\+$|^(DTC|PCK)\b|^DAZN\b|\bplatinum\b|league pass|\.com$|vision$""")

/** A station's digital subchannel: "WPCH.2" is shown as WPCH. */
private val SUBCHANNEL = Regex("""\.\d+$""")

/** NBA.com's abbreviations for the regional sports networks, as playlists name them. */
private val NBA_LOCAL_NAMES = mapOf(
    "NBCSB" to "NBC Sports Boston",
    "NBCSBA" to "NBC Sports Bay Area",
    "NBCSCA" to "NBC Sports California",
    "NBCSP" to "NBC Sports Philadelphia",
    "SPECSN" to "Spectrum SportsNet",
    "CHSN" to "Chicago Sports Network",
    "ALT" to "Altitude",
    "ALT2" to "Altitude 2",
    "YES" to "YES Network",
    "MSG" to "MSG",
    "MSGSN" to "MSG Sportsnet",
    "AZFS" to "Arizona's Family Sports",
    "SCHN" to "Space City Home Network",
    "MNMT" to "Monumental Sports Network",
    "FDSN" to "FanDuel Sports",
    "GCSEN" to "Gulf Coast Sports",
    "SN" to "Sportsnet",
)

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
