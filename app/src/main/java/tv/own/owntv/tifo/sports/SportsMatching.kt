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

    // Points: the more sure a channel shows this very game, the more it gets; the most goes first
    // and is what Watch plays. The rules, for users: docs/MATCHING.md.

    /** One of the user's favourite channels (★) that shows this game ([SHOWS_THE_GAME] or more). */
    const val FAVORITE_BONUS = 3000

    /** A channel named after the game itself ("US| SOCCER PPV 41: Paris SG vs Lyon"). */
    const val EVENT_CHANNEL = 600

    /** The channel the game's official TV list names (FotMob for football). */
    const val LISTED_CHANNEL = 500

    /** The guide shows the game on the channel, both teams (or players) named, at its time. */
    const val GUIDE_GAME = 500

    /** Tennis: the guide names the tournament ("Shanghai Masters") at the match's time. */
    const val GUIDE_TOURNAMENT = 450

    /** From here a match is about this exact game, from any country, and a favourite counts. */
    const val SHOWS_THE_GAME = GUIDE_TOURNAMENT

    /**
     * NBA.com's channel for the game, above event channels so Watch plays the official one:
     * national first, then the favourite team's local channel, the home team's, the away team's
     * ([NBA_RANK] apart).
     */
    const val NBA_LISTED = 700
    const val NBA_RANK = 10

    /**
     * A feed of the streaming service FotMob lists without naming a channel ("Ligue 1+" →
     * "FR| LIGUE 1+ 3", "FR| LIGUE1+ (Samedi Match 1)"): the game is on one of them.
     */
    const val LISTED_SERVICE = 300

    /** A US channel of the network ESPN lists for the game (CBS, NBC, ESPN…). */
    const val NETWORK = 200

    /** Formula 1: the guide names only the series ("F1 : le mag") at the race's time. */
    const val COMPETITION_ONLY = 150

    /** Last resort, when nothing above found anything: a channel that usually shows the league. */
    const val LAST_RESORT = 100

    /** Between two equal channels the US one comes first, then the Canadian one. */
    const val US_BONUS = 50
    const val CA_BONUS = 49

    /**
     * The countries whose channels can be guessed (a service's feeds, ESPN's network, the
     * league's usual channels); elsewhere "Paramount" or "Canal" name unrelated channels (ARG|
     * PARAMOUNT, ARG| CANAL 13). The other countries' channels only come from this game's own
     * guide, name or TV list.
     */
    val GUESS_COUNTRIES = setOf("US", "CA", "FR")

    /** [base] points with the country's and the favourite's extras. */
    fun finalScore(base: Int, channelName: String, favorite: Boolean): Int {
        val country = when (countryOf(channelName)) {
            "US" -> US_BONUS
            "CA" -> CA_BONUS
            else -> 0
        }
        return base + country + if (favorite && base >= SHOWS_THE_GAME) FAVORITE_BONUS else 0
    }

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
        // Premier League
        "afc bournemouth" to listOf("Bournemouth"),
        "brighton & hove albion" to listOf("Brighton"),
        "nottingham forest" to listOf("Nottm Forest", "Forest"),
        "west ham united" to listOf("West Ham"),
        "wolverhampton wanderers" to listOf("Wolves"),
        "newcastle united" to listOf("Newcastle"),
        "leeds united" to listOf("Leeds"),
        "crystal palace" to listOf("Palace"),
        // Ligue 1
        "lille" to listOf("LOSC"),
        "stade rennais" to listOf("Rennes"),
        "aj auxerre" to listOf("Auxerre"),
        "le havre ac" to listOf("Le Havre"),
        "troyes" to listOf("ESTAC"),
        "as monaco" to listOf("Monaco"),
        "brest" to listOf("Stade Brestois"),
        // LaLiga
        "real betis" to listOf("Betis"),
        "celta vigo" to listOf("Celta"),
        "deportivo alaves" to listOf("Alaves"),
        "athletic club" to listOf("Athletic Bilbao", "Athletic"),
        "rayo vallecano" to listOf("Rayo"),
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

    /**
     * Words that identify a race or tournament: its name without the generic words around it
     * ("Rolex Shanghai Masters" → "Shanghai").
     */
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
     * 0 means not at all. A team game needs both teams; a tennis match its two players, or the
     * tournament with the tour or the sport named (never highlights, replays or magazines); a race
     * its name, or the series alone.
     */
    fun score(event: SportsEvent, title: String, extra: String? = null): Int {
        val text = normalize(title)
        val all = if (extra.isNullOrBlank()) text else text + " " + normalize(extra)
        if (mentionsOtherSport(event, all)) return 0
        val competition = event.competition.keywords.any { containsTerm(all, it) }
        return when (event.competition.kind) {
            SportKind.TEAM -> if (namesBothTeams(event, text, competition)) GUIDE_GAME else 0
            SportKind.TOURNAMENT -> when {
                namesBothTeams(event, text, competition) -> GUIDE_GAME
                NOT_LIVE.containsMatchIn(all) -> 0
                eventTerms(event).any { containsTerm(text, it) } && (competition || namesTheTour(event, all)) -> GUIDE_TOURNAMENT
                else -> 0
            }
            SportKind.RACE -> {
                val named = eventTerms(event).any { containsTerm(text, it) }
                when {
                    named -> GUIDE_GAME
                    competition -> COMPETITION_ONLY
                    else -> 0
                }
            }
        }
    }

    /** The tournament's own generic words ("Masters", "Open") in [normalizedText]: "Shanghai Masters". */
    private fun namesTheTour(event: SportsEvent, normalizedText: String): Boolean =
        event.name.split(' ', '-', '\'').map { normalize(it.trim()) }
            .filter { it in TOUR_WORDS }
            .any { containsTerm(normalizedText, it) }

    /** The terms to look for in channel names: no abbreviations, which hide in too many words. */
    fun channelSearchTerms(event: SportsEvent): List<String> =
        searchTerms(event).filter { it.length >= 4 && normalize(it) !in GENERIC_WORDS }

    /**
     * How sure we are that a channel named [channelName] (filed under [category]) shows [event]
     * whatever its guide says: both teams or players in its name, a race with the series ("F1 |
     * Singapore GP"). Never when it names another sport, or the game on another date.
     */
    fun channelScore(event: SportsEvent, channelName: String, category: String? = null): Int {
        val text = normalize(channelName)
        if (isPlaceholder(channelName)) return 0
        val all = if (category.isNullOrBlank()) text else text + " " + normalize(category)
        if (mentionsOtherSport(event, all)) return 0
        if (namesAnotherDate(channelName, event.startMs)) return 0
        val competition = event.competition.keywords.any { containsTerm(text, it) }
        return when (event.competition.kind) {
            SportKind.TEAM, SportKind.TOURNAMENT -> if (namesBothTeams(event, text, competition)) EVENT_CHANNEL else 0
            SportKind.RACE -> {
                val named = eventTerms(event).any { containsTerm(text, it) }
                if (named && competition) EVENT_CHANNEL else 0
            }
        }
    }

    /**
     * Whether [text] names both teams. One team is never enough: "Spurs" alone is also Tottenham,
     * and a club's own channel doesn't show every game. A city ("Chicago at Green Bay") stands for
     * its team, but two cities alone also name other sports' games, so then the competition has to
     * be named too; and a city both teams share (Lakers v Clippers) names neither.
     */
    private fun namesBothTeams(event: SportsEvent, text: String, competitionNamed: Boolean): Boolean {
        val home = event.home ?: return false
        val away = event.away ?: return false
        val teams = listOf(home, away)
        val named = teams.map { team -> teamTerms(team).any { containsTerm(text, it) } }
        val cities = teams.map { city(it) }
        val sameCity = cities[0] != null && cities[1] != null && normalize(cities[0]!!) == normalize(cities[1]!!)
        val byCity = cities.map { c -> c != null && !sameCity && containsTerm(text, c) }
        val found = teams.indices.all { named[it] || byCity[it] }
        return found && (named.any { it } || competitionNamed)
    }

    /**
     * Whether [normalizedText] names a sport other than the event's ("NHL", "Premier League" for an
     * NBA game), or a league of its sport the Sports tab doesn't follow (WNBA, college, the CFL,
     * the WTA): team names repeat across sports (Spurs, Kings, Rangers, Panthers, Patriots…).
     */
    fun mentionsOtherSport(event: SportsEvent, normalizedText: String): Boolean {
        val own = sportOf(event.competition)
        return SPORT_WORDS.any { (sport, words) -> sport != own && words.any { containsTerm(normalizedText, it) } } ||
            OTHER_LEAGUES[event.competition].orEmpty().any { containsTerm(normalizedText, it) }
    }

    /**
     * Whether a provider's [category] is for another sport ("US| NHL", "SOCCER EVENTS" for an NFL
     * game). One that also names the game's own sport ("NFL & NHL") still holds its channels.
     */
    fun isAnotherSportsCategory(event: SportsEvent, category: String): Boolean {
        val text = normalize(category)
        if (!mentionsOtherSport(event, text)) return false
        val own = SPORT_WORDS[sportOf(event.competition)].orEmpty() + event.competition.keywords
        return own.none { containsTerm(text, it) }
    }

    private fun sportOf(competition: Competition): String = when (competition) {
        Competition.LIGUE_1, Competition.PREMIER_LEAGUE, Competition.LALIGA, Competition.CHAMPIONS_LEAGUE -> SOCCER
        Competition.NBA -> BASKETBALL
        Competition.NFL -> AMERICAN_FOOTBALL
        Competition.FORMULA_1 -> RACING
        Competition.ATP -> TENNIS
    }

    /** The leagues of a competition's own sport that are not it. */
    private val OTHER_LEAGUES: Map<Competition, List<String>> = mapOf(
        Competition.NBA to listOf("wnba", "euroleague", "acb", "liga acb"),
        Competition.NFL to listOf("cfl", "ufl", "xfl"),
        Competition.ATP to listOf("wta", "women", "womens", "feminin", "femmes", "dames"),
    )

    /**
     * Whether a channel named [channelName] is the broadcaster ESPN calls [broadcaster]: "ESPN2" is
     * "US | ESPN 2 HD", "beIN Sports" is "beIN Sports 1", but "ESPN" is neither "ESPN2" nor "ESPN+".
     */
    fun broadcasterMatches(channelName: String, broadcaster: String): Boolean {
        val text = normalize(channelName)
        return broadcasterSearchTerms(broadcaster).any { variant ->
            val needle = normalize(variant)
            var from = 0
            while (true) {
                val at = text.indexOf(needle, from)
                if (at < 0) break
                val end = at + needle.length
                val before = at == 0 || !text[at - 1].isLetterOrDigit()
                val after = end >= text.length || (!text[end].isLetterOrDigit() && text[end] != '+')
                if (before && after) return true
                from = at + 1
            }
            false
        }
    }

    /**
     * Whether a channel named [channelName] goes by one of [competition]'s usual channels: the
     * [broadcaster] itself ("ESPN", never "ESPN+"), or for one named after the competition, any
     * channel with that name in it ("Ligue 1" is also "FR| LIGUE 1+ 3").
     */
    fun namesBroadcaster(channelName: String, broadcaster: String, competition: Competition): Boolean =
        broadcasterMatches(channelName, broadcaster) ||
            (competition.keywords.any { normalize(it) == normalize(broadcaster) } && containsTerm(normalize(channelName), broadcaster))

    /**
     * What to look for in the database for [broadcaster]: "ESPN2" also as "ESPN 2", and ESPN's
     * short forms by the names playlists use ("NFL Net" is "NFL Network").
     */
    fun broadcasterSearchTerms(broadcaster: String): List<String> {
        val name = broadcaster.trim()
        return (listOf(name) + BROADCASTER_ALIASES[normalize(name)].orEmpty())
            .flatMap { listOf(it, it.replace(LETTER_DIGIT, " "), it.replace(" ", "")) }
            .filter { it.length >= 2 }
            .distinct()
    }

    /** ESPN's short broadcaster names, keyed normalised, and what channels are called instead. */
    private val BROADCASTER_ALIASES: Map<String, List<String>> = mapOf(
        "nfl net" to listOf("NFL Network"),
        "cbssn" to listOf("CBS Sports Network", "CBS Sports"),
        "fs1" to listOf("Fox Sports 1"),
        "fs2" to listOf("Fox Sports 2"),
        "espnu" to listOf("ESPN U"),
        "prime video" to listOf("Amazon Prime", "Prime Video"),
        "nbatv" to listOf("NBA TV"),
        "usa net" to listOf("USA Network"),
    )

    private val LETTER_DIGIT = Regex("(?<=\\p{L})(?=\\d)|(?<=\\d)(?=\\p{L})")

    private const val SOCCER = "soccer"
    private const val BASKETBALL = "basketball"
    private const val AMERICAN_FOOTBALL = "american football"
    private const val RACING = "racing"
    private const val TENNIS = "tennis"

    /** Words that name a sport or one of its leagues, whole-word and normalised. */
    private val SPORT_WORDS: Map<String, List<String>> = mapOf(
        SOCCER to listOf(
            "soccer", "premier league", "ligue 1", "ligue 2", "laliga", "la liga", "champions league",
            "ligue des champions", "europa league", "serie a", "bundesliga", "eredivisie", "mls", "uefa", "fifa",
            "epl", "efl", "fa cup", "coupe de france",
        ),
        BASKETBALL to listOf("nba", "wnba", "basket", "basketball", "euroleague"),
        AMERICAN_FOOTBALL to listOf("nfl", "redzone", "super bowl"),
        "college" to listOf("ncaa", "ncaaf", "ncaab", "college", "high school", "cfb"),
        "hockey" to listOf("nhl", "hockey", "khl"),
        "baseball" to listOf("mlb", "baseball"),
        RACING to listOf("f1", "formula 1", "formule 1", "nascar", "motogp", "indycar"),
        TENNIS to listOf("atp", "wta", "tennis"),
        "racket" to listOf("padel", "table tennis", "tennis de table", "ping pong"),
        "rugby" to listOf("rugby", "top 14"),
        "handball" to listOf("handball"),
        "volleyball" to listOf("volleyball", "volley"),
        "futsal" to listOf("futsal"),
        "fighting" to listOf("ufc", "mma", "boxing", "boxe"),
        "golf" to listOf("golf", "pga"),
    )

    /**
     * Playlists use fake channels as section headers ("# # # NFL # # #", "##### USA #####", "=====
     * SPORTS =====", "★★★ PPV ★★★"). They play nothing, so they are never offered.
     */
    fun isSeparator(channelName: String): Boolean = DIVIDER.containsMatchIn(channelName)

    private val DIVIDER = Regex("""^\s*(#|[=*~_\-–—•●★▬|]{2,})""")

    /**
     * An event slot with nothing in it ("US| ESPN+ 07: NO EVENT STREAMING", "PPV 12 - No Event"),
     * kept in the playlist until a game is put on it.
     */
    fun isPlaceholder(channelName: String): Boolean = PLACEHOLDER.containsMatchIn(normalize(channelName))

    private val PLACEHOLDER = Regex("""\bno (live )?(event|events|stream|streaming|game|games|match)\b|\bnot (live|streaming)\b|\boff air\b""")

    /** Whether [channelName] reads as one game ("Ravens vs. Falcons", "Lakers @ Celtics"). */
    fun namesAGame(channelName: String): Boolean = GAME_TITLE.containsMatchIn(channelName)

    /**
     * Whether a guide programme titled [title] is a game ("Chiefs vs. Raiders", "Football : Nice -
     * Monaco") but not [event]: the channel shows another one at that time.
     */
    fun isAnotherGame(event: SportsEvent, title: String, extra: String? = null): Boolean {
        val all = normalize(title) + if (extra.isNullOrBlank()) "" else " " + normalize(extra)
        val game = GAME_TITLE.containsMatchIn(title) ||
            (DASHED_GAME.containsMatchIn(title) && SPORT_WORDS.values.flatten().plus(listOf("sport", "sports", "football")).any { containsTerm(all, it) })
        return game && score(event, title, extra) == 0
    }

    private val DASHED_GAME = Regex("""\p{L}{3,}\s[-–/]\s\p{L}{3,}""")

    /**
     * Whether [channelName] is dated, and not on the day of the game starting at [startMs] (in
     * the US, France or the UK, where it may already be the next day): an event channel left over
     * from the same teams' last game ("Arsenal vs Chelsea 04/05").
     */
    fun namesAnotherDate(channelName: String, startMs: Long): Boolean {
        val dates = datesIn(channelName)
        if (dates.isEmpty()) return false
        val instant = java.time.Instant.ofEpochMilli(startMs)
        val days = DATE_ZONES.mapTo(HashSet()) { instant.atZone(java.time.ZoneId.of(it)).toLocalDate() }
        return dates.none { d -> days.any { day -> d.matches(day) } }
    }

    /** A date written in a channel name; [year] is null when it has none. */
    private data class NamedDate(val day: Int, val month: Int, val year: Int?) {
        fun matches(date: java.time.LocalDate): Boolean =
            day == date.dayOfMonth && month == date.monthValue && (year == null || year == date.year)
    }

    /**
     * The dates in a channel name: "2026-10-18", "18-10-2026", "18/10", "10/18", "Oct 18", "18
     * octobre". A day and month in numbers alone could be either way round, so both are kept.
     */
    private fun datesIn(channelName: String): List<NamedDate> = buildList {
        ISO_DATE.findAll(channelName).forEach { m ->
            val (y, mo, d) = m.destructured
            add(NamedDate(d.toInt(), mo.toInt(), y.toInt()))
        }
        val withoutIso = ISO_DATE.replace(channelName, " ")
        NUMERIC_DATE.findAll(withoutIso).forEach { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            // "1-0" is a score and "3.5" a number: without a year, only "18/10".
            if (year == null && m.groupValues[0].none { it == '/' }) return@forEach
            if (a in 1..31 && b in 1..12) add(NamedDate(a, b, year))
            if (b in 1..31 && a in 1..12) add(NamedDate(b, a, year))
        }
        val text = normalize(channelName)
        MONTH_DAY.findAll(text).forEach { m -> monthOf(m.groupValues[1])?.let { add(NamedDate(m.groupValues[2].toInt(), it, null)) } }
        DAY_MONTH.findAll(text).forEach { m -> monthOf(m.groupValues[2])?.let { add(NamedDate(m.groupValues[1].toInt(), it, null)) } }
    }.filter { it.day in 1..31 }

    private fun monthOf(word: String): Int? = MONTHS.entries.firstOrNull { (prefix, _) -> word.startsWith(prefix) }?.value

    private val DATE_ZONES = listOf("UTC", "America/New_York", "America/Los_Angeles", "Europe/Paris")
    private val ISO_DATE = Regex("""(?<!\d)(20\d\d)-(\d{1,2})-(\d{1,2})(?!\d)""")
    private val NUMERIC_DATE = Regex("""(?<![\d.:/-])(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{4}|\d{2}))?(?![\d:/-])""")
    private val MONTH_NAMES = "jan|feb|fev|mar|apr|avr|may|mai|jun|juin|jul|juil|aug|aou|sep|oct|nov|dec"
    private val MONTH_DAY = Regex("""\b($MONTH_NAMES)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b""")
    private val DAY_MONTH = Regex("""\b(\d{1,2})(?:st|nd|rd|th|er)?\s+($MONTH_NAMES)[a-z]*\b""")
    private val MONTHS = linkedMapOf(
        "juil" to 7, "juin" to 6, "jan" to 1, "feb" to 2, "fev" to 2, "mar" to 3, "apr" to 4, "avr" to 4,
        "may" to 5, "mai" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "aou" to 8, "sep" to 9, "oct" to 10,
        "nov" to 11, "dec" to 12,
    )

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

    /**
     * A channel name reduced to what identifies the channel, so a TV guide's spelling and the
     * playlist's meet: no country prefix ("FR| ", "UK-NOWTV| "), no quality or source marks (HD, ᴿᴬᵂ,
     * HEVC), "+" kept as a word, Sports/Events and La Liga/Ligue1 spelled one way.
     * "UK-NOWTV| SKY SPORTS MAIN EVENTS HD(BK)" and "Sky Sports Main Event" → "sky sport main event";
     * "FR| CANAL+LIVE 3 FHD" and "Canal+ Live 3" → "canal plus live 3".
     */
    fun tvKey(name: String): String {
        val folded = Normalizer.normalize(name, Normalizer.Form.NFKC)
            .replace(COUNTRY_PREFIX, "")
            .replace(COUNTRY_IN_BRACKETS, " ")
            .replace("+", " plus ")
        return normalize(folded)
            .replace(NON_ALNUM, " ")
            .replace(LA_LIGA, "laliga")
            .replace(LIGUE1, "ligue 1")
            .split(' ')
            .filter { it.isNotEmpty() && it !in QUALITY_WORDS }
            .joinToString(" ") { if (it == "sports") "sport" else if (it == "events") "event" else it }
    }

    /**
     * A channel's name as a row shows it: without its country prefix and quality marks.
     * "FR| CANAL+ FOOT FHD ᴿᴬᵂ" → "CANAL+ FOOT", "UK-NOWTV| SKY SPORTS MAIN EVENTS HD(BK)" → "SKY
     * SPORTS MAIN EVENTS".
     */
    fun displayName(name: String): String {
        val words = name.replace(COUNTRY_PREFIX, "").trim().split(WHITESPACE).filter { tvKey(it).isNotEmpty() }
        return words.joinToString(" ").ifEmpty { name.trim() }
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * The country a playlist channel's name starts with ("FR| …", "UK-NOWTV| …", "[ES] …"), upper
     * case, with GB written UK as playlists do; null when it has none.
     */
    fun countryOf(channelName: String): String? =
        CHANNEL_COUNTRY.find(channelName)?.groupValues?.get(1)?.uppercase(Locale.ROOT)?.let { if (it == "GB") "UK" else it }

    /** FotMob's country code as playlists write it: "GB" is "UK". */
    fun playlistCountry(fotMobCountry: String): String =
        if (fotMobCountry.equals("GB", ignoreCase = true)) "UK" else fotMobCountry.uppercase(Locale.ROOT)

    /**
     * Whether a playlist channel is the station a TV guide lists: the same [tvKey], or the
     * playlist's "<name> TV" ("M+ LALIGA" is "ES| M+ LALIGA TV FHD"), and in Spain its main feed
     * "<name> 1" ("DAZN LaLiga" is "ES| DAZN LA LIGA 1 HD"). Not "<name> 1" elsewhere: France's
     * "Canal+ Sport" is not "FR| CANAL+ SPORT 1".
     */
    fun isListedChannel(channelName: String, station: String, country: String): Boolean {
        val key = tvKey(station)
        if (key.isEmpty()) return false
        val channel = tvKey(channelName)
        return channel == key || channel == "$key tv" || (country == "ES" && channel == "$key 1")
    }

    /**
     * Whether a playlist channel is one of the feeds of [service] ("Ligue 1+"): the service itself,
     * a numbered feed ("FR| LIGUE 1+ 3 HD", "FR| LIGUE1 + 7") or a match-day one ("FR| LIGUE1+ FHD
     * ( Samedi Match 1)") — never the service's other channels ("Disney+ Films 1"). Generic apps
     * (DAZN, myCANAL) name no feed at all.
     */
    fun isServiceFeed(channelName: String, service: String): Boolean {
        val key = tvKey(service)
        if (key.length < 4 || key in GENERIC_SERVICES) return false
        val channel = tvKey(channelName)
        return channel == key || (channel.startsWith("$key ") && SERVICE_FEED.containsMatchIn(channel.substring(key.length + 1)))
    }

    /**
     * A channel that carries one event at a time — a PPV or event slot ("US| ESPN+ PPV 23", "US
     * (ESPN+ 002) | Football: …") or one titled with a game ("Ravens vs. Falcons"): its name says
     * nothing about which game it shows unless the game itself is in it, so it is never offered as
     * one of a broadcaster's channels.
     */
    fun isEventSlot(channelName: String): Boolean =
        SLOT_WORDS.containsMatchIn(normalize(channelName)) ||
            GAME_TITLE.containsMatchIn(channelName) ||
            NUMBERED_SLOT.containsMatchIn(channelName)

    private val COUNTRY_PREFIX = Regex("""^\s*(\[[^\]]*]|[A-Za-z0-9]{2,6}(-[A-Za-z0-9]+)?\s*[|:])\s*""")
    private val COUNTRY_IN_BRACKETS = Regex("""\((FR|US|UK|GB|ES|BK)\)""", RegexOption.IGNORE_CASE)
    private val CHANNEL_COUNTRY = Regex("""^\s*\[?([A-Za-z]{2,4})(-[A-Za-z0-9]+)?\s*[|:\]]""")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val LA_LIGA = Regex("""\bla liga\b""")
    private val LIGUE1 = Regex("""\bligue1\b""")
    private val QUALITY_WORDS = setOf(
        "8k", "4k", "uhd", "fhd", "hd", "hdr", "sd", "lq", "1080p", "720p", "3840p", "2160p", "hevc", "h265",
        "50fps", "60fps", "raw", "backup", "bk", "vip",
    )
    private val GENERIC_SERVICES = setOf("dazn", "dazn com", "mycanal", "youtube", "now", "apple tv", "apple tv plus")
    private val SERVICE_FEED = Regex("""^(\d+|(vendredi|samedi|dimanche|lundi|mardi|mercredi|jeudi|match|multiplex|canal multiplex)\b)""")
    private val SLOT_WORDS = Regex("""\b(ppv|event|events|evento|eventos)\b""")
    private val NOT_LIVE = Regex("""\b(highlights?|replay|rediffusion|resume|best of|magazine|mag|weekly|review|temps forts|preview)\b""")
    private val GAME_TITLE = Regex("""\s(vs?\.?|x|@)\s""", RegexOption.IGNORE_CASE)
    private val NUMBERED_SLOT = Regex("""\(\s*[^)]*\d{2,3}\s*\)\s*\|""")

    private val DIACRITICS = Regex("\\p{Mn}+")

    private val GENERIC_WORDS = setOf(
        "grand", "prix", "formula", "open", "masters", "championships", "championship", "tournament",
        "presented", "with", "cup", "rolex", "1000", "atp", "tour", "finals",
    )

    /** A tournament's generic words that still say which tour it is ("Shanghai Masters", "Paris Open"). */
    private val TOUR_WORDS = setOf("masters", "open", "championships", "finals", "atp", "tennis", "cup")
}
