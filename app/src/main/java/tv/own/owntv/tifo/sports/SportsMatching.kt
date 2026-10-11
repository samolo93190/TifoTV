package tv.own.owntv.tifo.sports

import java.text.Normalizer
import java.util.Locale

/**
 * Pure scoring of "does this guide programme show this event", kept apart from the database so it
 * can be unit tested. Guides name games in every way there is ("PSG / Lyon", "Football : Ligue 1 -
 * Paris SG - Olympique Lyonnais", "Lakers @ Celtics"), so names are compared accent- and
 * case-blind, and short names only as whole words.
 */
object SportsMatching {

    /** Both teams in the title. */
    const val BOTH_TEAMS = 100

    /** One team and the competition's name. */
    const val TEAM_AND_COMPETITION = 70

    /** A race or tournament by its own name ("Singapore Grand Prix" for the Singapore GP). */
    const val EVENT_NAME = 70

    /** One team only. */
    const val ONE_TEAM = 45

    /** Only the competition ("Multiplex Ligue 1", "NFL Sunday"), airing at the event's time. */
    const val COMPETITION_ONLY = 30

    /** A channel whose name is one of the competition's usual broadcasters. */
    const val BROADCASTER = 20

    /**
     * A playlist channel named after the game itself ("NFL 03: Bears vs Packers", "LIGUE 1 | PSG -
     * Lyon"). Providers make these per game, often without a guide, so they rank first.
     */
    const val EVENT_CHANNEL = 110

    /** A channel naming one of the teams: a club's own channel, or one side of an event channel. */
    const val TEAM_CHANNEL = 35

    /** Spellings guides use that ESPN doesn't, keyed by ESPN's normalised display name. */
    private val aliases: Map<String, List<String>> = mapOf(
        "paris saint-germain" to listOf("PSG", "Paris SG", "Paris-SG"),
        "marseille" to listOf("Olympique de Marseille"),
        "lyon" to listOf("Olympique Lyonnais"),
        "ac milan" to listOf("Milan AC", "AC Milan"),
        "internazionale" to listOf("Inter Milan", "Inter"),
        "bayern munich" to listOf("Bayern", "Bayern Munchen"),
        "atletico madrid" to listOf("Atletico", "Atletico de Madrid"),
        "manchester united" to listOf("Man Utd", "Man United"),
        "manchester city" to listOf("Man City"),
        "tottenham hotspur" to listOf("Tottenham"),
        "borussia dortmund" to listOf("Dortmund"),
    )

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)

    /** Every spelling worth looking for: ESPN's names plus [aliases], at least 3 characters long. */
    fun teamTerms(team: SportsTeam): List<String> =
        (listOf(team.name, team.shortName) + team.altNames + aliases[normalize(team.name)].orEmpty())
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { normalize(it) }

    /**
     * The city part of a US-style team name ("Green Bay" for "Green Bay Packers"), which guides and
     * event channels use on their own ("Chicago at Green Bay"). Null when the name has none.
     */
    fun city(team: SportsTeam): String? {
        val name = team.name.trim()
        val short = team.shortName.trim()
        if (short.isEmpty() || short.length >= name.length || !name.endsWith(short, ignoreCase = true)) return null
        return name.dropLast(short.length).trim().takeIf { it.length >= 3 }
    }

    /** Words that identify a race or tournament: its name without the generic words around it. */
    fun eventTerms(event: SportsEvent): List<String> =
        event.name.split(' ', '-', '\'')
            .map { it.trim() }
            .filter { it.length >= 4 && normalize(it) !in GENERIC_WORDS }

    /** The terms a database pre-filter should look for; [score] decides what actually matches. */
    fun searchTerms(event: SportsEvent): List<String> = buildList {
        event.home?.let { addAll(teamTerms(it)); city(it)?.let(::add) }
        event.away?.let { addAll(teamTerms(it)); city(it)?.let(::add) }
        if (event.competition.kind != SportKind.TEAM) {
            addAll(eventTerms(event))
            addAll(event.competition.keywords)
        } else if (event.home == null || event.away == null) {
            addAll(event.competition.keywords)
        }
    }.distinctBy { normalize(it) }

    /**
     * How sure we are that a programme titled [title] (with [extra]: its categories) shows [event].
     * 0 means not at all.
     */
    fun score(event: SportsEvent, title: String, extra: String? = null): Int {
        val text = normalize(title)
        val all = if (extra.isNullOrBlank()) text else text + " " + normalize(extra)
        val competition = event.competition.keywords.any { containsTerm(all, it) }
        return when (event.competition.kind) {
            SportKind.TEAM -> {
                val hits = teamHits(event, text)
                when {
                    hits == BOTH -> BOTH_TEAMS
                    hits == ONE && competition -> TEAM_AND_COMPETITION
                    hits == ONE -> ONE_TEAM
                    competition && containsTerm(text, event.competition.keywords.first()) -> COMPETITION_ONLY
                    else -> 0
                }
            }
            SportKind.RACE, SportKind.TOURNAMENT -> {
                val named = eventTerms(event).any { containsTerm(text, it) }
                when {
                    named && competition -> EVENT_NAME + 10
                    named -> EVENT_NAME
                    competition && event.competition.keywords.any { containsTerm(text, it) } -> COMPETITION_ONLY + 10
                    else -> 0
                }
            }
        }
    }

    /** The terms to look for in channel names: no abbreviations, which hide in too many words. */
    fun channelSearchTerms(event: SportsEvent): List<String> =
        searchTerms(event).filter { it.length >= 4 && normalize(it) !in GENERIC_WORDS }

    /**
     * How sure we are that a channel named [channelName] shows [event] whatever its guide says.
     * Races and tournaments need their name and the competition ("F1 | Singapore GP").
     */
    fun channelScore(event: SportsEvent, channelName: String): Int {
        val text = normalize(channelName)
        return when (event.competition.kind) {
            SportKind.TEAM -> when (teamHits(event, text)) {
                BOTH -> EVENT_CHANNEL
                ONE -> TEAM_CHANNEL
                else -> 0
            }
            SportKind.RACE, SportKind.TOURNAMENT -> {
                val named = eventTerms(event).any { containsTerm(text, it) }
                val competition = event.competition.keywords.any { containsTerm(text, it) }
                if (named && competition) EVENT_NAME else 0
            }
        }
    }

    /**
     * [BOTH] when [text] names both teams, [ONE] when it names one by its own name, else 0. A city
     * ("Chicago at Green Bay") only counts next to the other team, and not when both teams share it
     * (Lakers v Clippers), since a city alone also names local channels.
     */
    private fun teamHits(event: SportsEvent, text: String): Int {
        val teams = listOfNotNull(event.home, event.away)
        val named = teams.map { team -> teamTerms(team).any { containsTerm(text, it) } }
        val cities = teams.map { city(it) }
        val byCity = teams.indices.map { i ->
            val c = cities[i] ?: return@map false
            cities.count { it != null && normalize(it) == normalize(c) } == 1 && containsTerm(text, c)
        }
        val found = teams.indices.count { named[it] || byCity[it] }
        return when {
            teams.size == 2 && found == 2 -> BOTH
            named.any { it } -> ONE
            else -> 0
        }
    }

    private const val ONE = 1
    private const val BOTH = 2

    /**
     * Playlists use fake channels as section headers ("# # # NFL # # #", "##### USA #####"). They
     * play nothing, so they are never offered.
     */
    fun isSeparator(channelName: String): Boolean = channelName.trimStart().startsWith("#")

    /** [term] in [normalizedText] as a whole word (or words), accent- and case-blind. */
    fun containsTerm(normalizedText: String, term: String): Boolean {
        val needle = normalize(term)
        if (needle.isBlank()) return false
        var from = 0
        while (true) {
            val at = normalizedText.indexOf(needle, from)
            if (at < 0) return false
            val before = at == 0 || !normalizedText[at - 1].isLetterOrDigit()
            val end = at + needle.length
            val after = end >= normalizedText.length || !normalizedText[end].isLetterOrDigit()
            if (before && after) return true
            from = at + 1
        }
    }

    private val DIACRITICS = Regex("\\p{Mn}+")

    private val GENERIC_WORDS = setOf(
        "grand", "prix", "formula", "open", "masters", "championships", "championship", "tournament",
        "presented", "with", "cup",
    )
}
