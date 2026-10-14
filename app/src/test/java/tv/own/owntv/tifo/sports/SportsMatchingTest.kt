package tv.own.owntv.tifo.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsMatchingTest {

    private val psgLyon = """
        {"events":[{"id":"7001","date":"2026-10-11T19:00Z","name":"Lyon at Paris Saint-Germain",
          "status":{"type":{"state":"in","shortDetail":"62'"}},
          "competitions":[{"date":"2026-10-11T19:00Z",
            "competitors":[
              {"homeAway":"home","score":"2","team":{"displayName":"Paris Saint-Germain","shortDisplayName":"PSG","name":"Paris Saint-Germain","abbreviation":"PSG","logo":"https://a.espncdn.com/psg.png"}},
              {"homeAway":"away","score":"1","team":{"displayName":"Lyon","shortDisplayName":"Lyon","name":"Lyon","abbreviation":"LYON"}}],
            "broadcasts":[{"names":["beIN SPORTS USA"]}]}]}]}
    """.trimIndent()

    private val singapore = """
        {"events":[{"id":"600","date":"2026-10-02T09:30Z","name":"Singapore Grand Prix",
          "status":{"type":{"state":"pre","shortDetail":"Sun"}},
          "competitions":[
            {"date":"2026-10-02T09:30Z","type":{"abbreviation":"FP1"},"status":{"type":{"state":"post"}}},
            {"date":"2026-10-04T12:00Z","type":{"abbreviation":"Race"},"status":{"type":{"state":"pre","shortDetail":"Sun 2:00 PM"}}}]}]}
    """.trimIndent()

    @Test
    fun parsesATeamGame() {
        val event = parseScoreboard(Competition.LIGUE_1, psgLyon).single()
        assertEquals(EventState.LIVE, event.state)
        assertEquals("62'", event.statusDetail)
        assertEquals("PSG", event.home?.shortName)
        assertEquals("2", event.home?.score)
        assertEquals("Lyon", event.away?.name)
        assertEquals(listOf("beIN SPORTS USA"), event.broadcasts)
        assertEquals(parseEspnDate("2026-10-11T19:00:00Z"), event.startMs)
    }

    @Test
    fun aRaceIsTimedByItsRaceSession() {
        val event = parseScoreboard(Competition.FORMULA_1, singapore).single()
        assertEquals(parseEspnDate("2026-10-04T12:00Z"), event.startMs)
        assertEquals(EventState.UPCOMING, event.state)
        assertNull(event.home)
    }

    @Test
    fun espnDatesWithoutSecondsParse() {
        assertNotNull(parseEspnDate("2026-10-11T19:00Z"))
        assertNull(parseEspnDate("not a date"))
    }

    @Test
    fun guideTitlesScoreByHowMuchOfTheGameTheyName() {
        val event = parseScoreboard(Competition.LIGUE_1, psgLyon).single()
        assertEquals(SportsMatching.GUIDE_GAME, SportsMatching.score(event, "Football : PSG / Olympique Lyonnais"))
        assertEquals(SportsMatching.GUIDE_GAME, SportsMatching.score(event, "Paris SG - Lyon"))
        // One team, or the competition alone, is never enough.
        assertEquals(0, SportsMatching.score(event, "Ligue 1 : avant-match Paris Saint-Germain"))
        assertEquals(0, SportsMatching.score(event, "Le mag de l'OL : Lyon"))
        assertEquals(0, SportsMatching.score(event, "Multiplex Ligue 1"))
        assertEquals(0, SportsMatching.score(event, "Journal de 20h"))
    }

    @Test
    fun shortNamesOnlyMatchAsWholeWords() {
        val event = parseScoreboard(Competition.LIGUE_1, psgLyon).single()
        // "Lyon" inside "Lyonnaise" is not the club.
        assertEquals(0, SportsMatching.score(event, "Cuisine lyonnaise"))
        assertTrue(SportsMatching.containsTerm(SportsMatching.normalize("Saint-Étienne - PSG"), "Saint-Etienne"))
    }

    @Test
    fun aRaceMatchesOnItsName() {
        val event = parseScoreboard(Competition.FORMULA_1, singapore).single()
        assertEquals(SportsMatching.GUIDE_GAME, SportsMatching.score(event, "Formule 1 : Grand Prix de Singapour - Singapore"))
        assertEquals(SportsMatching.GUIDE_GAME, SportsMatching.score(event, "Singapore: the race"))
        assertEquals(SportsMatching.COMPETITION_ONLY, SportsMatching.score(event, "F1 : le mag"))
    }

    @Test
    fun teamScoreboardsAskForOneDayAtATime() {
        // ESPN rejects "dates=A-B" ranges, so a team league is fetched day by day.
        assertEquals(
            "https://site.api.espn.com/apis/site/v2/sports/soccer/fra.1/scoreboard?dates=20261011&limit=200",
            EspnScoreboard.urlFor(Competition.LIGUE_1, java.time.LocalDate.of(2026, 10, 11)),
        )
        assertEquals(
            "https://site.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard",
            EspnScoreboard.urlFor(Competition.FORMULA_1, null),
        )
    }

    private fun nflTeam(city: String, nickname: String, abbreviation: String) =
        SportsTeam("$city $nickname", nickname, listOf(nickname, abbreviation), null, null)

    private fun nflGame(home: SportsTeam, away: SportsTeam, startMs: Long = 0L) = SportsEvent(
        id = "401", competition = Competition.NFL, name = "${away.name} at ${home.name}",
        startMs = startMs, state = EventState.UPCOMING, statusDetail = "", home = home, away = away,
        broadcasts = emptyList(),
    )

    private val bearsAtPackers = nflGame(nflTeam("Green Bay", "Packers", "GB"), nflTeam("Chicago", "Bears", "CHI"))

    @Test
    fun eventChannelsNamingBothTeamsRankFirst() {
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(bearsAtPackers, "NFL 03: Bears vs Packers 1:00 PM ET"))
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(bearsAtPackers, "USA | NFL: Chicago @ Green Bay"))
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(bearsAtPackers, "NFL | Bears at Green Bay"))
        val psgLyonEvent = parseScoreboard(Competition.LIGUE_1, psgLyon).single()
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(psgLyonEvent, "LIGUE 1 | PSG - Lyon"))
    }

    @Test
    fun anotherSportAnywhereInTheNameOrCategoryIsNotTheGame() {
        val broncosAtChargers = nflGame(
            nflTeam("Los Angeles", "Chargers", "LAC"), nflTeam("Denver", "Broncos", "DEN"),
            startMs = parseEspnDate("2026-10-11T20:25Z")!!,
        )
        // "Lodge Patriots vs Old Road Jets" is soccer, whatever the teams are called.
        assertEquals(0, SportsMatching.channelScore(broncosAtChargers, "Live | Broncos vs. Chargers | all | 8K EXCLUSIVE | US: SOCCER EVENTS"))
        assertEquals(0, SportsMatching.channelScore(broncosAtChargers, "US: MLS | Broncos vs Chargers"))
        assertEquals(0, SportsMatching.channelScore(broncosAtChargers, "Broncos vs Chargers | Rugby"))
        assertEquals(0, SportsMatching.channelScore(broncosAtChargers, "Broncos vs Chargers", category = "US| NCAAF"))
        assertEquals(0, SportsMatching.channelScore(broncosAtChargers, "CFL: Broncos vs Chargers"))
        assertEquals(
            SportsMatching.EVENT_CHANNEL,
            SportsMatching.channelScore(broncosAtChargers, "Next | Broncos vs. Chargers | all | 11-10-2026 | 21:57 (GMT) | 8K EXCLUSIVE"),
        )
        assertTrue(SportsMatching.isAnotherSportsCategory(broncosAtChargers, "US| SOCCER EVENTS"))
        assertTrue(!SportsMatching.isAnotherSportsCategory(broncosAtChargers, "US| NFL & NHL"))
        assertTrue(!SportsMatching.isAnotherSportsCategory(broncosAtChargers, "US| SPORTS"))
    }

    @Test
    fun anEventChannelOnAnotherDateIsTheTeamsOtherGame() {
        val game = nflGame(nflTeam("Los Angeles", "Chargers", "LAC"), nflTeam("Denver", "Broncos", "DEN"), parseEspnDate("2026-10-12T00:20Z")!!)
        // Sunday night in the US is already Monday in UTC: both days are the game's.
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(game, "NFL | Broncos vs Chargers 10/11"))
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(game, "NFL | Broncos vs Chargers 2026-10-12"))
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(game, "NFL | Broncos vs Chargers (Oct 11th)"))
        assertEquals(0, SportsMatching.channelScore(game, "NFL | Broncos vs Chargers 09/07"))
        assertEquals(0, SportsMatching.channelScore(game, "NFL | Broncos vs Chargers - Sep 7"))
        assertEquals(0, SportsMatching.channelScore(game, "NFL | Broncos vs Chargers 07-09-2025"))
        // A time or a score is no date.
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(game, "NFL | Broncos vs Chargers 8:20 PM 1-0"))
    }

    @Test
    fun emptySlotsAndDividersAreNeverChannels() {
        assertTrue(SportsMatching.isPlaceholder("US| ESPN+ 07: NO EVENT STREAMING"))
        assertTrue(SportsMatching.isPlaceholder("PPV 12 - No Event"))
        assertTrue(!SportsMatching.isPlaceholder("NFL 03: Bears vs Packers"))
        assertTrue(SportsMatching.isSeparator("===== USA SPORTS ====="))
        assertTrue(SportsMatching.isSeparator("★★★ PPV ★★★"))
        assertTrue(!SportsMatching.isSeparator("US| ESPN HD"))
    }

    @Test
    fun aCityAloneIsNotATeam() {
        assertEquals(0, SportsMatching.channelScore(bearsAtPackers, "Packers TV"))
        assertEquals(0, SportsMatching.channelScore(bearsAtPackers, "NBC Sports Chicago"))
        assertEquals(0, SportsMatching.channelScore(bearsAtPackers, "NFL RedZone"))
        assertEquals("Green Bay", SportsMatching.city(bearsAtPackers.home!!))
        // Two teams from one city: the city names neither.
        val laDerby = nflGame(nflTeam("Los Angeles", "Rams", "LAR"), nflTeam("Los Angeles", "Chargers", "LAC"))
        assertEquals(0, SportsMatching.channelScore(laDerby, "Los Angeles Sports 4K"))
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(laDerby, "Chargers @ Rams"))
    }

    @Test
    fun guideTitlesWithCitiesNameTheGame() {
        assertEquals(SportsMatching.GUIDE_GAME, SportsMatching.score(bearsAtPackers, "NFL Football: Chicago at Green Bay"))
    }

    @Test
    fun aRaceChannelNeedsTheRaceAndTheSport() {
        val event = parseScoreboard(Competition.FORMULA_1, singapore).single()
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(event, "F1 | Singapore GP Live"))
        assertEquals(0, SportsMatching.channelScore(event, "Singapore News"))
        assertTrue("abbreviations stay out of the channel search", "CHI" !in SportsMatching.channelSearchTerms(bearsAtPackers))
    }

    @Test
    fun sectionHeadersAreNotChannels() {
        assertTrue(SportsMatching.isSeparator("# # # NFL GAMES # # #"))
        assertTrue(SportsMatching.isSeparator("  ##### Bears vs Packers #####"))
        assertTrue(!SportsMatching.isSeparator("NFL 03: Bears vs Packers"))
    }

    private fun nbaTeam(city: String, nickname: String, abbreviation: String) =
        SportsTeam("$city $nickname", nickname, listOf(nickname, abbreviation), null, null)

    private val spursAtSuns = SportsEvent(
        id = "402", competition = Competition.NBA, name = "San Antonio Spurs at Phoenix Suns",
        startMs = 0L, state = EventState.LIVE, statusDetail = "", home = nbaTeam("Phoenix", "Suns", "PHX"),
        away = nbaTeam("San Antonio", "Spurs", "SA"), broadcasts = listOf("ESPN2"),
    )

    @Test
    fun bothTeamsOrNothing() {
        assertEquals(0, SportsMatching.channelScore(spursAtSuns, "EPL 04: Man United vs Spurs"))
        assertEquals(SportsMatching.GUIDE_GAME, SportsMatching.score(spursAtSuns, "Phoenix vs Spurs"))
        assertEquals(0, SportsMatching.score(spursAtSuns, "Phoenix Mercury Highlights"))
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(spursAtSuns, "NBA 02: Spurs @ Suns"))
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(spursAtSuns, "NBA | San Antonio @ Phoenix"))
        // Two cities alone could be any sport: the competition has to be named too.
        assertEquals(0, SportsMatching.channelScore(spursAtSuns, "San Antonio vs Phoenix"))
    }

    @Test
    fun anotherSportIsNeverTheGame() {
        assertEquals(0, SportsMatching.channelScore(spursAtSuns, "NHL 03: Spurs vs Suns"))
        assertEquals(0, SportsMatching.score(spursAtSuns, "Spurs - Suns", "Sports; Hockey"))
        assertEquals(0, SportsMatching.channelScore(spursAtSuns, "Premier League | Spurs v Suns"))
        assertEquals(SportsMatching.GUIDE_GAME, SportsMatching.score(spursAtSuns, "Spurs - Suns", "Sports; Basketball"))
        assertEquals(0, SportsMatching.channelScore(spursAtSuns, "WNBA | Spurs vs Suns"))
        assertEquals(0, SportsMatching.channelScore(spursAtSuns, "NCAA | Spurs vs Suns"))
    }

    @Test
    fun espnsBroadcasterIsFoundByItsChannelName() {
        assertTrue(SportsMatching.broadcasterMatches("US | ESPN2 HD", "ESPN2"))
        assertTrue(SportsMatching.broadcasterMatches("USA: ESPN 2", "ESPN2"))
        assertTrue(!SportsMatching.broadcasterMatches("ESPN2", "ESPN"))
        assertTrue(!SportsMatching.broadcasterMatches("ESPN+ 03", "ESPN"))
        assertTrue(SportsMatching.broadcasterMatches("FR | beIN Sports 1 FHD", "beIn Sports"))
        assertTrue(SportsMatching.broadcasterMatches("CBS New York", "CBS"))
        assertTrue(!SportsMatching.broadcasterMatches("CBSN News", "CBS"))
        assertTrue(SportsMatching.broadcasterMatches("US: NFL Network HD", "NFL Net"))
    }

    @Test
    fun onlyTheUsLeaguesGoByEspnsListing() {
        val espn = Competition.entries.filter { it.usesEspnBroadcasts }.toSet()
        assertEquals(setOf(Competition.NBA, Competition.NFL), espn)
        assertTrue(Competition.LIGUE_1.broadcasters.none { it.contains("beIN", ignoreCase = true) })
        // The last resort: "Ligue 1" is any channel named so, "ESPN" is never "ESPN+".
        assertTrue(SportsMatching.namesBroadcaster("FR| LIGUE 1+ 3", "Ligue 1", Competition.LIGUE_1))
        assertTrue(!SportsMatching.namesBroadcaster("US| ESPN+ 03", "ESPN", Competition.NBA))
        assertTrue(SportsMatching.namesBroadcaster("US| TENNIS CHANNEL", "Tennis Channel", Competition.ATP))
        assertTrue(SportsMatching.broadcasterMatches("FR: Ligue 1+ 2 FHD", "Ligue 1+"))
        assertTrue(SportsMatching.broadcasterMatches("FR| LIGUE1+ 3", "Ligue 1+"))
        assertTrue(!SportsMatching.broadcasterMatches("FR: Ligue 1 Replay", "Ligue 1+"))
        assertTrue(SportsMatching.broadcasterMatches("UK: Sky Sports Premier League", "Sky Sports"))
    }

    private val shanghai = """
        {"events":[{"id":"315-2026","date":"2026-10-05T04:00Z","endDate":"2026-10-19T03:59Z","name":"Rolex Shanghai Masters",
          "groupings":[
            {"grouping":{"displayName":"Men's Singles"},"competitions":[
              {"id":"9001","date":"2026-10-11T06:00Z","status":{"type":{"state":"pre","shortDetail":"Sun 2:00 AM"}},
               "competitors":[
                 {"id":"1","homeAway":"home","athlete":{"displayName":"Tommy Paul","shortName":"T. Paul","flag":{"href":"https://a.espncdn.com/usa.png"}}},
                 {"id":"2","homeAway":"away","athlete":{"displayName":"Zizou Bergs","shortName":"Z. Bergs"}}]},
              {"id":"9002","date":"2026-10-11T08:00Z","status":{"type":{"state":"pre"}},
               "competitors":[{"id":"3","homeAway":"home","athlete":{"displayName":"Jannik Sinner"}},{"id":"4","homeAway":"away"}]}]},
            {"grouping":{"displayName":"Men's Doubles"},"competitions":[
              {"id":"9003","date":"2026-10-11T05:00Z","status":{"type":{"state":"pre"}},
               "competitors":[{"id":"5","homeAway":"home","athlete":{"displayName":"A / B"}},{"id":"6","homeAway":"away","athlete":{"displayName":"C / D"}}]}]}]}]}
    """.trimIndent()

    @Test
    fun aTennisTournamentIsListedMatchByMatch() {
        val match = parseScoreboard(Competition.ATP, shanghai).single()
        assertEquals("9001", match.id)
        assertEquals("Rolex Shanghai Masters", match.name)
        assertEquals("T. Paul", match.home?.shortName)
        assertEquals(listOf("Bergs"), match.away?.altNames)
        assertEquals(parseEspnDate("2026-10-11T06:00Z"), match.startMs)
        assertEquals(EventState.UPCOMING, match.state)
    }

    @Test
    fun aTennisMatchIsFoundByItsPlayersOrItsTournament() {
        val match = parseScoreboard(Competition.ATP, shanghai).single()
        assertEquals(SportsMatching.EVENT_CHANNEL, SportsMatching.channelScore(match, "US| TENNIS PPV: Paul, Tommy vs Bergs, Zizou"))
        assertEquals(0, SportsMatching.channelScore(match, "ATP Shanghai Court 1"))
        assertEquals(SportsMatching.GUIDE_GAME, SportsMatching.score(match, "Tennis : T. Paul - Z. Bergs"))
        assertEquals(SportsMatching.GUIDE_TOURNAMENT, SportsMatching.score(match, "Shanghai Masters"))
        assertEquals(SportsMatching.GUIDE_TOURNAMENT, SportsMatching.score(match, "Tennis : Masters 1000 de Shanghai"))
        // Highlights and magazines aren't the match; neither is the city alone, nor the women's tour.
        assertEquals(0, SportsMatching.score(match, "Shanghai Masters: Highlights"))
        assertEquals(0, SportsMatching.score(match, "Shanghai Express"))
        assertEquals(0, SportsMatching.score(match, "WTA Wuhan Open"))
        assertEquals(0, SportsMatching.channelScore(match, "Padel | Paul vs Bergs"))
        assertEquals(listOf("Shanghai"), SportsMatching.eventTerms(match))
    }

    @Test
    fun pointsFollowTheRules() {
        // Rules from strongest to weakest.
        val order = listOf(
            SportsMatching.EVENT_CHANNEL, SportsMatching.LISTED_CHANNEL, SportsMatching.GUIDE_TOURNAMENT,
            SportsMatching.LISTED_SERVICE, SportsMatching.NETWORK, SportsMatching.LAST_RESORT,
        )
        assertEquals(order.sortedDescending(), order)
        assertEquals(SportsMatching.LISTED_CHANNEL, SportsMatching.GUIDE_GAME)
        // NBA.com's channels go before event channels, even a US one.
        assertTrue(SportsMatching.NBA_LISTED > SportsMatching.EVENT_CHANNEL + SportsMatching.US_BONUS)
        // Arsenal – Chelsea: the US event channel first, unless the French channel is a favourite.
        assertEquals(650, SportsMatching.finalScore(SportsMatching.EVENT_CHANNEL, "US| SOCCER PPV 12: Arsenal vs Chelsea", favorite = false))
        assertEquals(500, SportsMatching.finalScore(SportsMatching.LISTED_CHANNEL, "FR| CANAL+ FOOT", favorite = false))
        assertEquals(3500, SportsMatching.finalScore(SportsMatching.LISTED_CHANNEL, "FR| CANAL+ FOOT", favorite = true))
        assertEquals(549, SportsMatching.finalScore(SportsMatching.GUIDE_GAME, "CA| TSN 1", favorite = false))
        // A favourite only counts when it shows the game itself, not as a guess.
        assertEquals(250, SportsMatching.finalScore(SportsMatching.NETWORK, "US| CBS", favorite = true))
        // Tennis: a favourite whose guide names the tournament beats an event channel.
        assertEquals(3450, SportsMatching.finalScore(SportsMatching.GUIDE_TOURNAMENT, "FR| EUROSPORT 1", favorite = true))
    }

    @Test
    fun aChannelShowingAnotherGameIsNotThisOne() {
        val psgLyonEvent = parseScoreboard(Competition.LIGUE_1, psgLyon).single()
        assertTrue(SportsMatching.isAnotherGame(psgLyonEvent, "Ligue 1 : Nice - Monaco"))
        assertTrue(SportsMatching.isAnotherGame(psgLyonEvent, "Chiefs vs. Raiders"))
        assertTrue(!SportsMatching.isAnotherGame(psgLyonEvent, "Paris SG - Lyon"))
        assertTrue(!SportsMatching.isAnotherGame(psgLyonEvent, "Journal - Météo"))
        assertTrue(SportsMatching.namesAGame("NFL 03: Ravens vs. Falcons"))
    }
}
