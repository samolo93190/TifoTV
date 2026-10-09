package tv.own.owntv.tifo.sports

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * ESPN's public scoreboard: free, no key, but unofficial, so it can change without notice. Every
 * read is defensive and a competition that fails to load simply shows nothing.
 */
class EspnScoreboard(private val http: OkHttpClient) {

    suspend fun load(competition: Competition, nowMs: Long): List<SportsEvent> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(urlFor(competition, nowMs)).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "ESPN ${competition.espnPath} answered ${response.code}" }
            parseScoreboard(competition, response.body.string())
        }
    }

    companion object {
        private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/"

        /** Team sports: yesterday to a week ahead. Races and tournaments: ESPN's current event. */
        internal fun urlFor(competition: Competition, nowMs: Long): String {
            val url = BASE + competition.espnPath + "/scoreboard"
            if (competition.kind != SportKind.TEAM) return url
            val today = java.time.Instant.ofEpochMilli(nowMs).atOffset(ZoneOffset.UTC).toLocalDate()
            return url + "?dates=" + day(today.minusDays(1)) + "-" + day(today.plusDays(7)) + "&limit=200"
        }

        private fun day(date: LocalDate): String = date.format(DateTimeFormatter.BASIC_ISO_DATE)
    }
}

/** ESPN writes "2026-10-11T19:00Z", without seconds; [OffsetDateTime] takes both forms. */
internal fun parseEspnDate(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
}

internal fun parseScoreboard(competition: Competition, json: String): List<SportsEvent> {
    val events = JSONObject(json).optJSONArray("events") ?: return emptyList()
    return (0 until events.length()).mapNotNull { i ->
        runCatching { parseEvent(competition, events.getJSONObject(i)) }.getOrNull()
    }
}

private fun parseEvent(competition: Competition, event: JSONObject): SportsEvent? {
    val competitions = event.optJSONArray("competitions")
    // A race weekend lists every session; the race itself is the one that matters.
    val main = when (competition.kind) {
        SportKind.RACE -> competitions.objects().lastOrNull { it.optJSONObject("type")?.optString("abbreviation") == "Race" }
            ?: competitions.objects().lastOrNull()
        else -> competitions?.optJSONObject(0)
    }
    val startMs = parseEspnDate(main?.optString("date")?.takeIf { competition.kind == SportKind.RACE })
        ?: parseEspnDate(event.optString("date"))
        ?: return null
    val status = (main?.optJSONObject("status")?.takeIf { competition.kind == SportKind.RACE })
        ?: event.optJSONObject("status")
        ?: main?.optJSONObject("status")
    val type = status?.optJSONObject("type")
    val state = when (type?.optString("state")) {
        "in" -> EventState.LIVE
        "post" -> EventState.FINISHED
        else -> EventState.UPCOMING
    }
    var home: SportsTeam? = null
    var away: SportsTeam? = null
    if (competition.kind == SportKind.TEAM) {
        main?.optJSONArray("competitors").objects().forEach { c ->
            val team = parseTeam(c) ?: return@forEach
            if (c.optString("homeAway") == "home") home = team else away = team
        }
    }
    val broadcasts = buildList {
        main?.optJSONArray("broadcasts").objects().forEach { b ->
            b.optJSONArray("names").strings().forEach(::add)
        }
        main?.optJSONArray("geoBroadcasts").objects().forEach { b ->
            b.optJSONObject("media")?.optString("shortName")?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }.distinct()
    return SportsEvent(
        id = event.optString("id").ifBlank { return null },
        competition = competition,
        name = event.optString("name").ifBlank { event.optString("shortName") },
        startMs = startMs,
        state = state,
        statusDetail = type?.optString("shortDetail").orEmpty(),
        home = home,
        away = away,
        broadcasts = broadcasts,
    )
}

private fun parseTeam(competitor: JSONObject): SportsTeam? {
    val team = competitor.optJSONObject("team") ?: return null
    val name = team.optString("displayName").ifBlank { team.optString("name") }
    if (name.isBlank()) return null
    return SportsTeam(
        name = name,
        shortName = team.optString("shortDisplayName").ifBlank { name },
        altNames = listOf(team.optString("name"), team.optString("abbreviation"))
            .filter { it.isNotBlank() },
        logoUrl = team.optString("logo").takeIf { it.isNotBlank() },
        score = competitor.optString("score").takeIf { it.isNotBlank() },
    )
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
