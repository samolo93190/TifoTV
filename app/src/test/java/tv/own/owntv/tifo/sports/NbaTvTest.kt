package tv.own.owntv.tifo.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NbaTvTest {

    private fun broadcaster(display: String, abbreviation: String = display, media: String = "tv") =
        """{"broadcasterMedia":"$media","broadcasterDisplay":"$display","broadcasterAbbreviation":"$abbreviation"}"""

    private val schedule = """
        {"leagueSchedule":{"gameDates":[{"gameDate":"10/21/2026 00:00:00","games":[
          {"gameId":"0022600010","gameDateTimeUTC":"2026-10-21T23:30:00Z",
           "homeTeam":{"teamCity":"New York","teamName":"Knicks"},"awayTeam":{"teamCity":"Boston","teamName":"Celtics"},
           "broadcasters":{
             "nationalBroadcasters":[${broadcaster("ESPN")}],
             "nationalRadioBroadcasters":[${broadcaster("SiriusXM", media = "radio")}],
             "homeTvBroadcasters":[${broadcaster("MSG")}],
             "awayTvBroadcasters":[${broadcaster("NBCSB/PCK-BOS", "NBCSB/PCK-BOS")}]}},
          {"gameId":"0022600011","gameDateTimeUTC":"2026-10-22T02:00:00Z",
           "homeTeam":{"teamCity":"Minnesota","teamName":"Timberwolves"},"awayTeam":{"teamCity":"Phoenix","teamName":"Suns"},
           "broadcasters":{
             "nationalBroadcasters":[],
             "homeTvBroadcasters":[${broadcaster("KARE 11/DAZN-MIN", "KARE/DAZN-MIN")}],
             "awayTvBroadcasters":[${broadcaster("3TV/AZFS/Suns+ on AZFS App", "KTVK/KPHE/AZFS App")}]}}]}]}}
    """.trimIndent()

    private fun team(city: String, name: String, abbreviation: String) =
        SportsTeam("$city $name", name, listOf(name, abbreviation), null, null)

    private fun game(home: SportsTeam, away: SportsTeam, startMs: Long) = SportsEvent(
        id = "401", competition = Competition.NBA, name = "${away.name} at ${home.name}", startMs = startMs,
        state = EventState.UPCOMING, statusDetail = "", home = home, away = away, broadcasts = listOf("ESPN"),
    )

    @Test
    fun theScheduleGivesNationalAndLocalChannels() {
        val games = parseNbaSchedule(schedule)
        assertEquals(2, games.size)
        val knicks = games.first().broadcasts
        assertEquals(listOf("ESPN"), knicks.national)
        assertEquals(listOf("MSG"), knicks.home)
        assertEquals(listOf("NBC Sports Boston"), knicks.away)
        assertEquals(listOf("ESPN", "MSG", "NBC Sports Boston"), knicks.all)
        // Team apps and DAZN aren't channels; local stations go by their call sign.
        val wolves = games.last().broadcasts
        assertTrue(wolves.national.isEmpty())
        assertEquals(listOf("KARE"), wolves.home)
        assertEquals(listOf("KTVK", "KPHE"), wolves.away)
    }

    @Test
    fun localAbbreviationsNameChannels() {
        assertEquals(listOf("YES Network"), nbaLocalChannels("YES"))
        assertEquals(listOf("Altitude"), nbaLocalChannels("ALT/ALT+"))
        assertEquals(listOf("WPLG"), nbaLocalChannels("WPLG/Local10+"))
        assertEquals(listOf("Chicago Sports Network"), nbaLocalChannels("CHSN/CHSN App"))
        assertTrue(nbaLocalChannels("DTC-POR").isEmpty())
        assertEquals(listOf("WPCH"), nbaLocalChannels("WPCH.2"))
        assertTrue(nbaLocalChannels("Mavs.com/BlazerVision").isEmpty())
    }

    @Test
    fun espnsGameIsFoundInTheSchedule() {
        val games = parseNbaSchedule(schedule)
        val start = parseEspnDate("2026-10-21T23:30Z")!!
        val knicks = game(team("New York", "Knicks", "NY"), team("Boston", "Celtics", "BOS"), start)
        assertEquals(listOf("MSG"), NbaTv.gameFor(knicks, games)?.broadcasts?.home)
        // The teams the other way round, or another night, are another game.
        assertNull(NbaTv.gameFor(game(team("Boston", "Celtics", "BOS"), team("New York", "Knicks", "NY"), start), games))
        assertNull(NbaTv.gameFor(knicks.copy(startMs = start + 24 * 3_600_000L), games))
        assertNotNull(NbaTv.gameFor(knicks.copy(startMs = start + 30 * 60_000L), games))
    }
}
