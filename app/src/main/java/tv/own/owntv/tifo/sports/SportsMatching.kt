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

    /** Words that identify a race or tournament: its name without the generic words around it. */
    fun eventTerms(event: SportsEvent): List<String> =
        event.name.split(' ', '-', '\'')
            .map { it.trim() }
            .filter { it.length >= 4 && normalize(it) !in GENERIC_WORDS }

    /** The terms a database pre-filter should look for; [score] decides what actually matches. */
    fun searchTerms(event: SportsEvent): List<String> = buildList {
        event.home?.let { addAll(teamTerms(it)) }
        event.away?.let { addAll(teamTerms(it)) }
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
                val hits = listOfNotNull(event.home, event.away).count { team ->
                    teamTerms(team).any { containsTerm(text, it) }
                }
                when {
                    hits >= 2 -> BOTH_TEAMS
                    hits == 1 && competition -> TEAM_AND_COMPETITION
                    hits == 1 -> ONE_TEAM
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
