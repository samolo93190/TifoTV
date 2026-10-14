package tv.own.owntv.tifo.sports

import androidx.annotation.StringRes
import tv.own.owntv.R

/** How a competition's events are shaped, which decides how a game is matched to a channel. */
enum class SportKind {
    /** Two sides: home and away. */
    TEAM,

    /** One event per weekend (a Grand Prix): matched on the event's name and the series. */
    RACE,

    /**
     * A tournament running for days, listed match by match (tennis): matched on the two players,
     * or on the tournament's name and the tour.
     */
    TOURNAMENT,
}

/**
 * The competitions the Sports tab follows. [espnPath] is the league's path on ESPN's public
 * scoreboard API. [keywords] are how a guide names the competition in a programme title.
 *
 * [broadcasters] are the channels that usually carry the competition, the last resort when nothing
 * at all shows the game: only ones a US, Canadian or French channel goes by. For the US leagues
 * ([usesEspnBroadcasts]) ESPN's network for the game is looked for first.
 *
 * Football's exact channel per game comes from FotMob's TV guide: France's for every competition,
 * plus [tvCountries] (FotMob's codes) for the competition's own country.
 */
enum class Competition(
    @param:StringRes val labelRes: Int,
    val espnPath: String,
    val kind: SportKind,
    val keywords: List<String>,
    val broadcasters: List<String>,
    val usesEspnBroadcasts: Boolean = false,
    val tvCountries: List<String> = emptyList(),
) {
    LIGUE_1(
        R.string.tifo_sports_ligue_1, "soccer/fra.1", SportKind.TEAM,
        listOf("Ligue 1"),
        // Ligue 1+ shows every game from 2026-27: its feeds are named "Ligue 1+ 3".
        broadcasters = listOf("Ligue 1"),
    ),
    PREMIER_LEAGUE(
        R.string.tifo_sports_premier_league, "soccer/eng.1", SportKind.TEAM,
        listOf("Premier League"),
        broadcasters = listOf("Peacock", "USA Network", "NBC Sports", "Canal+ Foot", "Canal+ Sport"),
        tvCountries = listOf("GB"),
    ),
    LALIGA(
        R.string.tifo_sports_laliga, "soccer/esp.1", SportKind.TEAM,
        listOf("LaLiga", "La Liga", "Liga"),
        // beIN Sports no longer has LaLiga in France; ESPN+ (ESPN's listing) carries it in the US.
        broadcasters = listOf("ESPN Deportes", "DAZN LaLiga"),
        tvCountries = listOf("ES"),
    ),
    CHAMPIONS_LEAGUE(
        R.string.tifo_sports_champions_league, "soccer/uefa.champions", SportKind.TEAM,
        listOf("Champions League", "Ligue des champions"),
        // Paramount+ is an app: its games only show as event channels ("US| PARAMOUNT+ PPV").
        broadcasters = listOf("CBS Sports", "Golazo", "Canal+ Sport", "Canal+ Foot", "Canal+ Live"),
        tvCountries = listOf("GB"),
    ),
    NBA(
        R.string.tifo_sports_nba, "basketball/nba", SportKind.TEAM,
        listOf("NBA"),
        broadcasters = listOf(
            "NBA TV", "League Pass", "ESPN", "ABC", "NBC", "Peacock", "Prime Video", "TNT", "TSN", "Sportsnet", "beIN Sports",
        ),
        usesEspnBroadcasts = true,
    ),
    NFL(
        R.string.tifo_sports_nfl, "football/nfl", SportKind.TEAM,
        listOf("NFL"),
        broadcasters = listOf("NFL Network", "RedZone", "CBS", "FOX", "NBC", "ESPN", "ABC", "Prime Video", "TSN", "DAZN"),
        usesEspnBroadcasts = true,
    ),
    FORMULA_1(
        R.string.tifo_sports_formula_1, "racing/f1", SportKind.RACE,
        listOf("Formula 1", "Formule 1", "F1", "Grand Prix"),
        broadcasters = listOf("Canal+ Sport", "Canal+ F1", "F1 TV"),
    ),
    ATP(
        R.string.tifo_sports_atp, "tennis/atp", SportKind.TOURNAMENT,
        listOf("ATP", "Tennis"),
        broadcasters = listOf("Tennis Channel", "Tennis TV", "Tennis", "ATP"),
    ),
    ;

    val isFootball: Boolean get() = espnPath.startsWith("soccer/")

    /**
     * The countries whose FotMob guide is read for this competition's games: France, plus England
     * for the Premier League and the Champions League, Spain for LaLiga.
     */
    val fotMobCountries: List<String> get() = if (isFootball) (listOf("FR") + tvCountries).distinct() else emptyList()
}

enum class EventState { UPCOMING, LIVE, FINISHED }

data class SportsTeam(
    val name: String,
    val shortName: String,
    /** ESPN's other spellings of the team (nickname, abbreviation), used only for matching. */
    val altNames: List<String>,
    val logoUrl: String?,
    val score: String?,
    /** ESPN's id for the team, the same in every game and in the league's team list. */
    val id: String = "",
    /** A tennis player's games in each set so far: "6", "7", "5". */
    val sets: List<String> = emptyList(),
)

data class SportsEvent(
    val id: String,
    val competition: Competition,
    /** "Lyon at Paris Saint-Germain", "Singapore Grand Prix"; a tennis match's tournament, "Rolex Shanghai Masters". */
    val name: String,
    val startMs: Long,
    val state: EventState,
    /** ESPN's own short status: "45'", "Q3 5:12", "FT". Blank when it has none. */
    val statusDetail: String,
    val home: SportsTeam?,
    val away: SportsTeam?,
    /** Broadcaster names ESPN lists for the event (mostly US networks). */
    val broadcasts: List<String>,
    /** A tournament's last day when it is listed whole; null for a single game, match or race. */
    val endMs: Long? = null,
)
