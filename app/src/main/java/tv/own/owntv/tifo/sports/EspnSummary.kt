package tv.own.owntv.tifo.sports

import androidx.annotation.StringRes
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.R

/** Which team a line of a game's summary belongs to. */
enum class Side { HOME, AWAY, NONE }

/** What a timeline line is: a goal, a card, a substitution, a period mark, a score (NFL). */
enum class TimelineKind { GOAL, OWN_GOAL, YELLOW, RED, SUBSTITUTION, HALF_TIME, SCORE }

/**
 * One line of a game's timeline: "18' ⚽ Assane Diao" (Como's side), "23' ⇄ Jesús Rodriguez for
 * Assane Diao", "Q1 5:26 Passing Touchdown · Parker Washington 51 Yd pass… 7 – 0".
 */
data class TimelineItem(
    val clock: String,
    val kind: TimelineKind,
    val side: Side,
    /** The player (the one coming on, for a substitution), or the scoring play's type. */
    val title: String,
    /** The player going off, or the scoring play's description. */
    val detail: String? = null,
    /** "7 – 0", home first, after a scoring play. */
    val homeScore: String? = null,
    val awayScore: String? = null,
)

data class LineupPlayer(val jersey: String?, val name: String, val position: String?, val subbedIn: Boolean, val subbedOut: Boolean)

/** A team's lineup: its formation ("4-3-3"), the starters and the bench. */
data class Lineup(val side: Side, val team: String, val formation: String?, val starters: List<LineupPlayer>, val bench: List<LineupPlayer>)

/**
 * "Possession: 58% – 42%", home first. [labelRes] names the stat in the user's language when the
 * app picked it (football's nine); [homeNote] and [awayNote] follow the value ("9 (33%)");
 * [homeValue] and [awayValue] size the bar between them.
 */
data class StatLine(
    val label: String,
    val home: String,
    val away: String,
    @param:StringRes val labelRes: Int? = null,
    val homeNote: String? = null,
    val awayNote: String? = null,
    val homeValue: Double? = null,
    val awayValue: Double? = null,
)

/** One table of a team's box score ("passing": C/ATT, YDS…; the NBA's single table). */
data class PlayerTable(val side: Side, val team: String, val title: String?, val labels: List<String>, val rows: List<PlayerRow>)

data class PlayerRow(val name: String, val starter: Boolean, val stats: List<String>)

/** A stat's leader for each team: "Passing Yards · T. Lawrence 24/39, 331 YDS". */
data class LeaderLine(val category: String, val home: String?, val away: String?)

/** A team's last results: "W 3-1 @ NEC". */
data class TeamForm(val side: Side, val team: String, val games: List<FormGame>)

data class FormGame(val result: String, val score: String, val opponent: String, val away: Boolean)

data class TeamInjuries(val side: Side, val team: String, val players: List<Pair<String, String>>)

/** A standings table: its columns ("GP", "W", "D", "L", "GD", "P") and rows; our teams marked. */
data class Standings(val title: String?, val columns: List<String>, val rows: List<StandingRow>)

data class StandingRow(val team: String, val values: List<String>, val ours: Boolean)

/** Everything ESPN's game summary says about a game; an empty part simply isn't shown. */
data class GameSummary(
    val timeline: List<TimelineItem> = emptyList(),
    val lineups: List<Lineup> = emptyList(),
    val teamStats: List<StatLine> = emptyList(),
    val players: List<PlayerTable> = emptyList(),
    val leaders: List<LeaderLine> = emptyList(),
    val venue: String? = null,
    val attendance: String? = null,
    val form: List<TeamForm> = emptyList(),
    val injuries: List<TeamInjuries> = emptyList(),
    val standings: List<Standings> = emptyList(),
    /** ESPN's ids of the two teams, for its per-team statistics. */
    val homeTeamId: String? = null,
    val awayTeamId: String? = null,
    /** The box score's team stats by ESPN's name ("possessionPct"), for football's own list. */
    val homeStats: Map<String, Double> = emptyMap(),
    val awayStats: Map<String, Double> = emptyMap(),
)

/**
 * ESPN's game summary (what espn.com's game page shows): the timeline, lineups, team and player
 * stats, leaders, form, injuries and standings. Same unofficial API as [EspnScoreboard]: a failed
 * read is null and the game page shows the score only.
 */
class EspnSummary(private val http: OkHttpClient) {

    suspend fun load(event: SportsEvent): GameSummary? = withContext(Dispatchers.IO) {
        if (event.competition.kind != SportKind.TEAM) return@withContext null
        val summary = runCatching { parseSummary(get(urlFor(event))) }.getOrNull() ?: return@withContext null
        if (!event.competition.isFootball) return@withContext summary
        // Football: the nine stats a match page shows, some only in ESPN's per-team statistics
        // (expected goals, big chances, duels); the box score fills in when those don't load.
        val (home, away) = coroutineScope {
            val h = async { summary.homeTeamId?.let { id -> runCatching { parseCoreStats(get(coreStatsUrl(event, id))) }.getOrNull() }.orEmpty() }
            val a = async { summary.awayTeamId?.let { id -> runCatching { parseCoreStats(get(coreStatsUrl(event, id))) }.getOrNull() }.orEmpty() }
            h.await() to a.await()
        }
        val stats = footballStats(summary.homeStats + home, summary.awayStats + away, Locale.getDefault())
        if (stats.isEmpty()) summary else summary.copy(teamStats = stats)
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "ESPN summary answered ${response.code}" }
            response.body.string()
        }
    }

    companion object {
        fun urlFor(event: SportsEvent): String =
            "https://site.api.espn.com/apis/site/v2/sports/" + event.competition.espnPath + "/summary?event=" + event.id

        /** ESPN's statistics of one team in a game: "soccer/uefa.champions" is sport and league. */
        fun coreStatsUrl(event: SportsEvent, teamId: String): String {
            val (sport, league) = event.competition.espnPath.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return "https://sports.core.api.espn.com/v2/sports/" + sport + "/leagues/" + league +
                "/events/" + event.id + "/competitions/" + event.id + "/competitors/" + teamId + "/statistics"
        }
    }
}

internal fun parseSummary(json: String): GameSummary {
    val root = JSONObject(json)
    val sides = HashMap<String, Side>()
    root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)
        ?.optJSONArray("competitors").objects().forEach { c ->
            val id = c.optString("id").ifBlank { c.optJSONObject("team")?.optString("id").orEmpty() }
            sides[id] = if (c.optString("homeAway") == "away") Side.AWAY else Side.HOME
        }
    val ids = sides.entries.associate { (id, side) -> side to id }
    fun sideOf(team: JSONObject?): Side = sides[team?.optString("id").orEmpty()] ?: Side.NONE
    fun part(block: () -> Unit) = runCatching(block)

    var summary = GameSummary()
    part { summary = summary.copy(timeline = parseKeyEvents(root.optJSONArray("keyEvents"), ::sideOf) + parseScoringPlays(root.optJSONArray("scoringPlays"), ::sideOf)) }
    part {
        summary = summary.copy(
            lineups = root.optJSONArray("rosters").objects().map { r ->
                val players = r.optJSONArray("roster").objects().mapNotNull { p ->
                    val a = p.optJSONObject("athlete") ?: return@mapNotNull null
                    val name = a.optString("displayName").ifBlank { return@mapNotNull null }
                    p.optBoolean("starter") to LineupPlayer(
                        jersey = p.optString("jersey").takeIf { it.isNotBlank() },
                        name = name,
                        position = p.optJSONObject("position")?.optString("abbreviation")?.takeIf { it.isNotBlank() && it != "null" },
                        subbedIn = p.optBoolean("subbedIn"),
                        subbedOut = p.optBoolean("subbedOut"),
                    )
                }
                Lineup(
                    side = if (r.optString("homeAway") == "away") Side.AWAY else Side.HOME,
                    team = r.optJSONObject("team")?.optString("displayName").orEmpty(),
                    formation = r.optString("formation").takeIf { it.isNotBlank() && it != "null" },
                    starters = players.filter { it.first }.map { it.second },
                    bench = players.filterNot { it.first }.map { it.second },
                )
            }.filter { it.starters.isNotEmpty() || it.bench.isNotEmpty() }.sortedBy { it.side },
        )
    }
    val box = root.optJSONObject("boxscore")
    part {
        val teams = box?.optJSONArray("teams").objects()
        val home = teams.firstOrNull { sideOf(it.optJSONObject("team")) == Side.HOME } ?: teams.getOrNull(0)
        val away = teams.firstOrNull { sideOf(it.optJSONObject("team")) == Side.AWAY } ?: teams.getOrNull(1)
        if (home != null && away != null) {
            fun numbers(team: JSONObject) = team.optJSONArray("statistics").objects().mapNotNull { st ->
                st.optString("displayValue").trim().removeSuffix("%").toDoubleOrNull()?.let { st.optString("name") to it }
            }.toMap()
            summary = summary.copy(homeStats = numbers(home), awayStats = numbers(away))
            val awayStats = away.optJSONArray("statistics").objects()
            summary = summary.copy(
                teamStats = home.optJSONArray("statistics").objects().mapNotNull { s ->
                    val label = s.optString("label").ifBlank { return@mapNotNull null }
                    val other = awayStats.firstOrNull { it.optString("name") == s.optString("name") && it.optString("label") == label }
                        ?: return@mapNotNull null
                    StatLine(label, s.optString("displayValue"), other.optString("displayValue"))
                },
            )
        }
    }
    part {
        summary = summary.copy(
            players = box?.optJSONArray("players").objects().flatMap { p ->
                val teamJson = p.optJSONObject("team")
                p.optJSONArray("statistics").objects().mapNotNull { g ->
                    val rows = g.optJSONArray("athletes").objects().mapNotNull { a ->
                        if (a.optBoolean("didNotPlay")) return@mapNotNull null
                        val name = a.optJSONObject("athlete")?.optString("displayName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        PlayerRow(name, a.optBoolean("starter"), a.optJSONArray("stats").strings())
                    }
                    if (rows.isEmpty()) return@mapNotNull null
                    PlayerTable(
                        side = sideOf(teamJson),
                        team = teamJson?.optString("displayName").orEmpty(),
                        title = g.optString("name").takeIf { it.isNotBlank() && it != "null" },
                        labels = g.optJSONArray("labels").strings(),
                        rows = rows,
                    )
                }
            },
        )
    }
    part {
        val byTeam = root.optJSONArray("leaders").objects().associateBy { sideOf(it.optJSONObject("team")) }
        val categories = byTeam.values.flatMap { t -> t.optJSONArray("leaders").objects().map { it.optString("displayName") } }.distinct()
        fun leaderOf(side: Side, category: String): String? {
            val c = byTeam[side]?.optJSONArray("leaders").objects().firstOrNull { it.optString("displayName") == category } ?: return null
            val top = c.optJSONArray("leaders").objects().firstOrNull() ?: return null
            val a = top.optJSONObject("athlete")
            val name = a?.optString("shortName")?.ifBlank { null } ?: a?.optString("displayName") ?: return null
            return name + " · " + top.optString("displayValue")
        }
        summary = summary.copy(
            leaders = categories.filter { it.isNotBlank() }.map { LeaderLine(it, leaderOf(Side.HOME, it), leaderOf(Side.AWAY, it)) }
                .filter { it.home != null || it.away != null },
        )
    }
    part {
        val info = root.optJSONObject("gameInfo")
        val venue = info?.optJSONObject("venue")
        val address = venue?.optJSONObject("address")
        val place = listOfNotNull(
            venue?.optString("fullName")?.takeIf { it.isNotBlank() },
            address?.optString("city")?.takeIf { it.isNotBlank() },
            address?.optString("country")?.takeIf { it.isNotBlank() },
        )
        val attendance = info?.optInt("attendance", 0)?.takeIf { it > 0 }
        summary = summary.copy(venue = place.joinToString(", ").ifBlank { null }, attendance = attendance?.toString())
    }
    part {
        summary = summary.copy(
            form = root.optJSONArray("lastFiveGames").objects().mapNotNull { t ->
                val games = t.optJSONArray("events").objects().map { e ->
                    FormGame(
                        result = e.optString("gameResult"),
                        score = e.optString("score"),
                        opponent = e.optJSONObject("opponent")?.optString("abbreviation").orEmpty(),
                        away = e.optString("atVs") == "@",
                    )
                }
                if (games.isEmpty()) null else TeamForm(sideOf(t.optJSONObject("team")), t.optJSONObject("team")?.optString("displayName").orEmpty(), games)
            }.sortedBy { it.side },
        )
    }
    part {
        summary = summary.copy(
            injuries = root.optJSONArray("injuries").objects().mapNotNull { t ->
                val players = t.optJSONArray("injuries").objects().mapNotNull { i ->
                    val name = i.optJSONObject("athlete")?.optString("displayName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    name to i.optString("status")
                }
                if (players.isEmpty()) null else TeamInjuries(sideOf(t.optJSONObject("team")), t.optJSONObject("team")?.optString("displayName").orEmpty(), players)
            }.sortedBy { it.side },
        )
    }
    part {
        val ourIds = sides.keys
        summary = summary.copy(
            standings = root.optJSONObject("standings")?.optJSONArray("groups").objects().mapNotNull { g ->
                val entries = g.optJSONObject("standings")?.optJSONArray("entries").objects()
                if (entries.isEmpty()) return@mapNotNull null
                val available = entries.first().optJSONArray("stats").objects().map { it.optString("abbreviation") }
                val columns = STANDING_COLUMNS.filter { it in available }
                if (columns.isEmpty()) return@mapNotNull null
                Standings(
                    title = g.optString("header").takeIf { it.isNotBlank() && it != "null" },
                    columns = columns,
                    rows = entries.map { e ->
                        val stats = e.optJSONArray("stats").objects().associate { it.optString("abbreviation") to it.optString("displayValue") }
                        StandingRow(e.optString("team"), columns.map { stats[it].orEmpty() }, e.optString("id") in ourIds)
                    },
                )
            },
        )
    }
    return summary.copy(homeTeamId = ids[Side.HOME], awayTeamId = ids[Side.AWAY])
}

/** ESPN's per-team statistics: every stat's value by its name, across its categories. */
internal fun parseCoreStats(json: String): Map<String, Double> =
    JSONObject(json).optJSONObject("splits")?.optJSONArray("categories").objects()
        .flatMap { it.optJSONArray("stats").objects() }
        .mapNotNull { st -> st.optDouble("value").takeIf { !it.isNaN() }?.let { st.optString("name") to it } }
        .toMap()

/**
 * Football's nine stats, as a match page shows them: expected goals, possession, shots on goal
 * (with the share of shots), big chances, corners, accurate passes (with the share of passes),
 * duels won, saves, fouls. A stat neither team has is left out.
 */
internal fun footballStats(home: Map<String, Double>, away: Map<String, Double>, locale: Locale): List<StatLine> {
    val whole = NumberFormat.getIntegerInstance(locale)
    val xg = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
    val percent = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }
    fun share(part: Double?, total: Double?): String? =
        if (part == null || total == null || total <= 0.0) null else "(" + percent.format(part / total) + ")"
    return FOOTBALL_STATS.mapNotNull { stat ->
        val h = home[stat.name]
        val a = away[stat.name]
        if (h == null && a == null) return@mapNotNull null
        fun show(v: Double?): String = when {
            v == null -> "–"
            stat.name == "expectedGoals" -> xg.format(v)
            stat.name == "possessionPct" -> percent.format(v / 100.0)
            else -> whole.format(v)
        }
        StatLine(
            label = stat.name,
            home = show(h),
            away = show(a),
            labelRes = stat.labelRes,
            homeNote = stat.of?.let { share(h, home[it]) },
            awayNote = stat.of?.let { share(a, away[it]) },
            homeValue = h ?: 0.0,
            awayValue = a ?: 0.0,
        )
    }
}

/** A football stat: ESPN's name, its label, and the total it is a share of ("9 (33%)"). */
private class FootballStat(val name: String, @param:StringRes val labelRes: Int, val of: String? = null)

private val FOOTBALL_STATS = listOf(
    FootballStat("expectedGoals", R.string.tifo_sports_stat_xg),
    FootballStat("possessionPct", R.string.tifo_sports_stat_possession),
    FootballStat("shotsOnTarget", R.string.tifo_sports_stat_shots_on_goal, of = "totalShots"),
    FootballStat("bigChanceCreated", R.string.tifo_sports_stat_big_chances),
    FootballStat("wonCorners", R.string.tifo_sports_stat_corners),
    FootballStat("accuratePasses", R.string.tifo_sports_stat_accurate_passes, of = "totalPasses"),
    FootballStat("duelsWon", R.string.tifo_sports_stat_duels),
    FootballStat("saves", R.string.tifo_sports_stat_saves),
    FootballStat("foulsCommitted", R.string.tifo_sports_stat_fouls),
)

/** Soccer's key events: goals, cards, substitutions and half time, oldest first. */
private fun parseKeyEvents(events: JSONArray?, sideOf: (JSONObject?) -> Side): List<TimelineItem> =
    events.objects().mapNotNull { e ->
        val type = e.optJSONObject("type")?.optString("type").orEmpty()
        val kind = when {
            type == "halftime" -> TimelineKind.HALF_TIME
            type.contains("own-goal") -> TimelineKind.OWN_GOAL
            type.contains("goal") || (e.optBoolean("scoringPlay") && type.contains("penalty")) -> TimelineKind.GOAL
            type.contains("yellow") -> TimelineKind.YELLOW
            type.contains("red") -> TimelineKind.RED
            type == "substitution" -> TimelineKind.SUBSTITUTION
            else -> return@mapNotNull null
        }
        val people = e.optJSONArray("participants").objects().mapNotNull { p ->
            p.optJSONObject("athlete")?.optString("displayName")?.takeIf { it.isNotBlank() }
        }
        val clock = e.optJSONObject("clock")?.optString("displayValue").orEmpty()
        if (kind == TimelineKind.HALF_TIME) return@mapNotNull TimelineItem(clock, kind, Side.NONE, "")
        val title = people.firstOrNull() ?: e.optString("shortText").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        TimelineItem(
            clock = clock,
            kind = kind,
            side = sideOf(e.optJSONObject("team")),
            title = title,
            detail = if (kind == TimelineKind.SUBSTITUTION) people.getOrNull(1) else null,
        )
    }

/** American football's scoring plays: "Q1 5:26 · Passing Touchdown", with the score after it. */
private fun parseScoringPlays(plays: JSONArray?, sideOf: (JSONObject?) -> Side): List<TimelineItem> =
    plays.objects().map { p ->
        val period = p.optJSONObject("period")?.optInt("number") ?: 0
        val clock = p.optJSONObject("clock")?.optString("displayValue").orEmpty()
        TimelineItem(
            clock = (if (period > 0) "Q$period " else "") + clock,
            kind = TimelineKind.SCORE,
            side = sideOf(p.optJSONObject("team")),
            title = p.optJSONObject("type")?.optString("text").orEmpty(),
            detail = p.optString("text").takeIf { it.isNotBlank() },
            homeScore = p.opt("homeScore")?.toString(),
            awayScore = p.opt("awayScore")?.toString(),
        )
    }

/** The standings columns worth a TV's width, in the order they read. */
private val STANDING_COLUMNS = listOf("GP", "W", "D", "T", "L", "PCT", "GB", "GD", "PF", "PA", "P", "PTS")

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }
