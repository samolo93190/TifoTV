package tv.own.owntv.tifo.sports

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsScheduleTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 10, 11)

    private fun at(day: LocalDate, hour: Int) = ZonedDateTime.of(day.atTime(hour, 0), paris).toInstant().toEpochMilli()

    private fun event(id: String, start: Long, state: EventState = EventState.UPCOMING, end: Long? = null, c: Competition = Competition.NFL) =
        SportsEvent(id, c, "", start, state, "", null, null, emptyList(), end)

    @Test
    fun aLeagueRowListsTheDaysGamesAndWhateverIsLive() {
        val lateLastNight = event("late", at(today.minusDays(1), 23), EventState.LIVE)
        val tonight = event("tonight", at(today, 21))
        val tomorrow = event("tomorrow", at(today.plusDays(1), 2))
        val state = SportsUiState(loading = false, events = listOf(lateLastNight, tonight, tomorrow))
        assertEquals(listOf(listOf("late", "tonight")), state.rows(emptySet(), today, paris).map { r -> r.events.map { it.id } })
        val picked = state.copy(day = today.plusDays(1))
        assertEquals(listOf(listOf("tomorrow")), picked.rows(emptySet(), today, paris).map { r -> r.events.map { it.id } })
    }

    @Test
    fun oneRowPerLeagueInLeagueOrderAndNoEmptyRows() {
        val nfl = event("nfl", at(today, 19), c = Competition.NFL)
        val ligue1 = event("l1", at(today, 21), c = Competition.LIGUE_1)
        val ucl = event("ucl", at(today, 18), EventState.LIVE, c = Competition.CHAMPIONS_LEAGUE)
        val rows = SportsUiState(loading = false, events = listOf(nfl, ligue1, ucl)).rows(emptySet(), today, paris)
        assertEquals(listOf(Competition.LIGUE_1, Competition.CHAMPIONS_LEAGUE, Competition.NFL), rows.map { it.competition })
    }

    @Test
    fun myTeamsComesFirstAndListsEveryDay() {
        val team = SportsTeam("Jets", "Jets", emptyList(), null, null, id = "20")
        val other = SportsTeam("Browns", "Browns", emptyList(), null, null, id = "5")
        val later = SportsEvent("g", Competition.NFL, "", at(today.plusDays(7), 19), EventState.UPCOMING, "", team, other, emptyList())
        val tonight = event("t", at(today, 21))
        val state = SportsUiState(loading = false, events = listOf(later, tonight))
        val rows = state.rows(setOf(SportsFavorites.keyOf(Competition.NFL, "20")), today, paris)
        assertEquals(listOf(null, Competition.NFL), rows.map { it.competition })
        assertEquals(listOf("g"), rows.first().events.map { it.id })
        // No favourites, no My teams row.
        assertEquals(listOf(Competition.NFL), state.rows(emptySet(), today, paris).map { it.competition })
    }

    @Test
    fun aTournamentIsOnEveryDayOfItsRun() {
        val masters = event("m", at(today.minusDays(3), 10), end = at(today.plusDays(4), 18), c = Competition.ATP)
        assertTrue(SportsSchedule.isOn(masters, today.plusDays(2), today, paris))
        assertFalse(SportsSchedule.isOn(masters, today.plusDays(5), today, paris))
    }

    @Test
    fun nextDayIsTheFirstDayWithAGame() {
        val events = listOf(event("a", at(today.plusDays(4), 20)), event("b", at(today.plusDays(2), 13)), event("c", at(today, 9)))
        assertEquals(today.plusDays(2), SportsSchedule.nextDay(events, today, paris))
        assertNull(SportsSchedule.nextDay(events, today.plusDays(4), paris))
    }
}
