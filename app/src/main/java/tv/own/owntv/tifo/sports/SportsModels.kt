package tv.own.owntv.tifo.sports

import androidx.annotation.StringRes
import tv.own.owntv.R

/** How a competition's events are shaped, which decides how a game is matched to a channel. */
enum class SportKind {
    /** Two sides: home and away. */
    TEAM,

    /** One event per weekend (a Grand Prix): matched on the event's name and the series. */
    RACE,

    /** A tournament running for days: matched on its name and the tour. */
    TOURNAMENT,
}

/**
 * The competitions the Sports tab follows. [espnPath] is the league's path on ESPN's public
 * scoreboard API. [keywords] are how a guide names the competition in a programme title, and
 * [broadcasters] the channel names that usually carry it, in France first.
 */
enum class Competition(
    @param:StringRes val labelRes: Int,
    val espnPath: String,
    val kind: SportKind,
    val keywords: List<String>,
    val broadcasters: List<String>,
) {
    LIGUE_1(
        R.string.tifo_sports_ligue_1, "soccer/fra.1", SportKind.TEAM,
        listOf("Ligue 1"),
        listOf("Ligue 1+", "beIN Sports", "DAZN"),
    ),
    PREMIER_LEAGUE(
        R.string.tifo_sports_premier_league, "soccer/eng.1", SportKind.TEAM,
        listOf("Premier League"),
        listOf("Canal+ Foot", "Canal+ Sport", "Sky Sports", "TNT Sports"),
    ),
    LALIGA(
        R.string.tifo_sports_laliga, "soccer/esp.1", SportKind.TEAM,
        listOf("LaLiga", "La Liga", "Liga"),
        listOf("beIN Sports", "Movistar", "DAZN"),
    ),
    CHAMPIONS_LEAGUE(
        R.string.tifo_sports_champions_league, "soccer/uefa.champions", SportKind.TEAM,
        listOf("Champions League", "Ligue des champions"),
        listOf("Canal+ Foot", "Canal+ Sport", "Canal+", "TNT Sports"),
    ),
    NBA(
        R.string.tifo_sports_nba, "basketball/nba", SportKind.TEAM,
        listOf("NBA"),
        listOf("beIN Sports", "NBA TV", "ESPN"),
    ),
    NFL(
        R.string.tifo_sports_nfl, "football/nfl", SportKind.TEAM,
        listOf("NFL"),
        listOf("beIN Sports", "NFL Network", "DAZN", "ESPN"),
    ),
    FORMULA_1(
        R.string.tifo_sports_formula_1, "racing/f1", SportKind.RACE,
        listOf("Formula 1", "Formule 1", "F1", "Grand Prix"),
        listOf("Canal+ Sport", "Canal+ F1", "Sky Sports F1", "F1 TV"),
    ),
    ATP(
        R.string.tifo_sports_atp, "tennis/atp", SportKind.TOURNAMENT,
        listOf("ATP", "Tennis"),
        listOf("Tennis Channel", "beIN Sports", "Eurosport"),
    ),
}

enum class EventState { UPCOMING, LIVE, FINISHED }

data class SportsTeam(
    val name: String,
    val shortName: String,
    /** ESPN's other spellings of the team (nickname, abbreviation), used only for matching. */
    val altNames: List<String>,
    val logoUrl: String?,
    val score: String?,
)

data class SportsEvent(
    val id: String,
    val competition: Competition,
    /** "Lyon at Paris Saint-Germain", "Singapore Grand Prix", "Shanghai Masters". */
    val name: String,
    val startMs: Long,
    val state: EventState,
    /** ESPN's own short status: "45'", "Q3 5:12", "FT". Blank when it has none. */
    val statusDetail: String,
    val home: SportsTeam?,
    val away: SportsTeam?,
    /** Broadcaster names ESPN lists for the event (mostly US networks). */
    val broadcasts: List<String>,
)
