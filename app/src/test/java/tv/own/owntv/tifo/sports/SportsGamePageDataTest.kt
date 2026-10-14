package tv.own.owntv.tifo.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/** What the game page shows: its channel rows and ESPN's details. */
class SportsGamePageDataTest {

    private var nextId = 1L

    private fun match(name: String, base: Int, category: String? = null, favorite: Boolean = false): SportsChannelMatch {
        val channel = ChannelEntity(id = nextId++, sourceId = 1, categoryId = 1, name = name, streamUrl = "http://x/$name", remoteId = name)
        return SportsChannelMatch(channel, SportsMatching.finalScore(base, name, favorite, category), null, null, null, favorite, base, category)
    }

    @Test
    fun channelsSplitIntoTvEventAndWorld() {
        val groups = SportsChannelGroups.group(
            listOf(
                match("US| SOCCER PPV 12: Arsenal vs Chelsea", SportsMatching.EVENT_CHANNEL, "US| SOCCER PPV"),
                match("US| SOCCER PPV 14: Arsenal vs Chelsea (Backup)", SportsMatching.EVENT_CHANNEL, "US| SOCCER PPV"),
                match("CA| SOCCER PPV 3: Arsenal v Chelsea", SportsMatching.EVENT_CHANNEL, "CA| SOCCER PPV"),
                match("AR| PPV 7: Arsenal vs Chelsea", SportsMatching.EVENT_CHANNEL, "AR| FUTBOL PPV"),
                match("UK| PPV 2: Arsenal vs Chelsea", SportsMatching.EVENT_CHANNEL, "UK| PPV"),
                match("FR| EVENT 4: Arsenal vs Chelsea", SportsMatching.EVENT_CHANNEL, "FR| EVENTS"),
                match("FR| CANAL+ FOOT FHD", SportsMatching.LISTED_CHANNEL),
                match("FR| CANAL+ FOOT HD ᴿᴬᵂ", SportsMatching.LISTED_CHANNEL),
                match("UK| SKY SPORTS MAIN EVENT", SportsMatching.LISTED_CHANNEL),
                match("US| PEACOCK", SportsMatching.NETWORK),
                match("AR| ESPN 2", SportsMatching.GUIDE_GAME),
                match("DE| SKY SPORT 1", SportsMatching.GUIDE_GAME),
            ),
        )
        // The same channel in two qualities is one row; FR, US, UK first.
        val tv = groups.getValue(ChannelTab.TV)
        assertEquals(listOf("CANAL+ FOOT", "PEACOCK", "SKY SPORTS MAIN EVENT"), tv.map { it.title })
        assertEquals(listOf("FR", "US", "UK"), tv.map { it.country })
        assertEquals(2, tv.first().streams.size)
        assertEquals(ChannelKind.GUESS, tv.first { it.title == "PEACOCK" }.kind)
        // Event channels: one row per provider category, FR, US, UK, CA first, then by best match.
        val events = groups.getValue(ChannelTab.EVENT)
        assertEquals(listOf("FR| EVENTS", "US| SOCCER PPV", "UK| PPV", "CA| SOCCER PPV", "AR| FUTBOL PPV"), events.map { it.title })
        assertEquals(2, events[1].streams.size)
        assertTrue(events.all { it.kind == ChannelKind.EVENT })
        // Every other country.
        assertEquals(listOf("AR", "DE"), groups.getValue(ChannelTab.WORLD).map { it.country })
    }

    @Test
    fun anEventSlotNamedAfterTheGameTakesItsCategorysCountry() {
        val events = SportsChannelGroups.group(
            listOf(
                match("AS Roma Real Madrid @ Oct 14 9:40 PM :MTV FI 09", SportsMatching.EVENT_CHANNEL, "FINLAND MTV PPV VIP"),
                match("AR| FOOTBALL 3: Roma vs Real Madrid", SportsMatching.EVENT_CHANNEL, "AR| FOOTBALL PPV"),
                match("AS Roma vs Real Madrid 21:00", SportsMatching.EVENT_CHANNEL, "US| SOCCER PPV"),
                match("Roma v Real Madrid", SportsMatching.EVENT_CHANNEL, "CA| SOCCER PPV"),
                match("Roma - Real Madrid", SportsMatching.EVENT_CHANNEL, "USA| PPV EVENTS"),
            ),
        ).getValue(ChannelTab.EVENT)
        assertEquals(listOf("US", "US", "CA", null, "AR"), events.map { it.country })
        assertEquals(listOf("US| SOCCER PPV", "USA| PPV EVENTS", "CA| SOCCER PPV", "FINLAND MTV PPV VIP", "AR| FOOTBALL PPV"), events.map { it.title })
    }

    @Test
    fun anEventSlotGetsItsCategorysCountryBonusAndTheFirstRowPlays() {
        val finland = match("Aston Villa Fenerbahce @ Oct 14 9:45 PM :MTV FI 11", SportsMatching.EVENT_CHANNEL, "FINLAND MTV PPV VIP")
        val us = match("Aston Villa vs Fenerbahce", SportsMatching.EVENT_CHANNEL, "US| SOCCER PPV")
        // The US slot gets the US extra though its name has no country.
        assertEquals(SportsMatching.EVENT_CHANNEL + SportsMatching.US_BONUS, us.score)
        assertEquals(SportsMatching.EVENT_CHANNEL, finland.score)
        val groups = SportsChannelGroups.group(listOf(finland, us))
        assertEquals(ChannelTab.EVENT, SportsChannelGroups.firstTab(groups))
        assertEquals("US| SOCCER PPV", SportsChannelGroups.first(groups)?.title)
    }

    @Test
    fun aRowShowsTheChannelsName() {
        assertEquals("CANAL+ FOOT", SportsMatching.displayName("FR| CANAL+ FOOT FHD ᴿᴬᵂ"))
        assertEquals("SKY SPORTS MAIN EVENTS", SportsMatching.displayName("UK-NOWTV| SKY SPORTS MAIN EVENTS HD(BK)"))
        assertEquals("beIN Sports 1", SportsMatching.displayName("beIN Sports 1"))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("espn/$name.json")).readText()

    @Test
    fun aFootballSummaryHasItsTimelineLineupsAndStats() {
        val s = parseSummary(fixture("summary_soccer"))
        // Kick-off and delays are left out; goals, subs, cards and half time stay, on their side.
        val goal = s.timeline.first()
        assertEquals(TimelineItem("18'", TimelineKind.GOAL, Side.AWAY, "Assane Diao"), goal)
        assertEquals(Side.HOME, s.timeline[1].side)
        val sub = s.timeline.first { it.kind == TimelineKind.SUBSTITUTION }
        assertEquals("Jesús Rodriguez", sub.title)
        assertEquals("Assane Diao", sub.detail)
        assertTrue(s.timeline.any { it.kind == TimelineKind.HALF_TIME })
        assertTrue(s.timeline.any { it.kind == TimelineKind.YELLOW })
        // Lineups: home first, the formation, eleven starters.
        assertEquals(listOf(Side.HOME, Side.AWAY), s.lineups.map { it.side })
        assertEquals("4-3-3", s.lineups.first().formation)
        assertEquals(11, s.lineups.first().starters.size)
        assertEquals("Tjark Ernst", s.lineups.first().starters.first().name)
        // Team stats paired home / away.
        assertEquals(StatLine("Fouls", "13", "10"), s.teamStats.first())
        assertEquals("De Kuip, Rotterdam, Netherlands", s.venue)
        assertEquals(listOf("W", "L", "W", "W", "W"), s.form.first().games.map { it.result })
        assertTrue(s.standings.first().columns.containsAll(listOf("GP", "W", "D", "L", "GD", "P")))
    }

    @Test
    fun anNflSummaryHasItsScoringPlaysAndBoxScore() {
        val s = parseSummary(fixture("summary_nfl"))
        val first = s.timeline.first()
        assertEquals(TimelineKind.SCORE, first.kind)
        assertEquals("Q1 5:26", first.clock)
        assertEquals("Passing Touchdown", first.title)
        assertEquals("7", first.homeScore)
        assertEquals("0", first.awayScore)
        // ESPN lists the away team first: the stats still pair home / away.
        assertEquals(StatLine("1st Downs", "18", "13"), s.teamStats.first())
        assertEquals(listOf("passing", "rushing", "receiving"), s.players.filter { it.side == Side.AWAY }.map { it.title })
        assertTrue(s.leaders.isNotEmpty())
        assertEquals("61322", s.attendance)
    }

    @Test
    fun anNbaSummaryHasItsBoxScore() {
        val s = parseSummary(fixture("summary_nba"))
        assertTrue(s.timeline.isEmpty())
        val table = s.players.first { it.side == Side.HOME }
        assertNull(table.title)
        assertEquals("MIN", table.labels.first())
        assertEquals(table.labels.size, table.rows.first().stats.size)
        assertEquals("FG", s.teamStats.first().label)
    }
}
