package tv.own.owntv.tifo.sports

import java.util.Locale

/** The game page's channel tabs. */
enum class ChannelTab {
    /** Channels of the countries the app is set up for: France, the US, the UK, Canada. */
    TV,

    /** Channels named after the game itself, from any country. */
    EVENT,

    /** Every other country's channels. */
    WORLD,
}

/** Why a row's channel is there: it shows the game, it is named after it, or it's a guess. */
enum class ChannelKind { TV, EVENT, GUESS }

/**
 * One row of the game page's channel list: a channel in one country ("CANAL+ FOOT" in France) with
 * all its streams ("FR| CANAL+ FOOT FHD", "FR| CANAL+ FOOT HD"), or for event channels, one provider
 * category ("US| PARAMOUNT+ PPV") with its game's slots. [streams] go best first.
 */
data class ChannelGroup(
    val key: String,
    val tab: ChannelTab,
    /** "FR", "UK"…; null for a channel without a country. */
    val country: String?,
    val title: String,
    val kind: ChannelKind,
    val streams: List<SportsChannelMatch>,
) {
    val best: SportsChannelMatch get() = streams.first()
    val favorite: Boolean get() = streams.any { it.favorite }
}

/** The tabs a game page shows, each with its rows in order, and the countries' order for TV and World. */
object SportsChannelGroups {

    /** The countries of the TV tab, in the order their sections show. */
    val TV_COUNTRIES = listOf("FR", "US", "UK", "CA")

    /**
     * [matches] (best first) as rows per tab: TV and World grouped by country and channel, Event
     * by provider category. Rows go by country (France, the US, the UK, Canada first), then by
     * their best stream.
     */
    fun group(matches: List<SportsChannelMatch>): Map<ChannelTab, List<ChannelGroup>> {
        val groups = LinkedHashMap<String, MutableList<SportsChannelMatch>>()
        val meta = HashMap<String, Triple<ChannelTab, String?, String>>()
        for (match in matches.sortedByDescending { it.score }) {
            val name = match.channel.name
            val category = match.category?.trim()?.takeIf { it.isNotEmpty() }
            // An event slot is often named after the game alone ("AS Roma Real Madrid @ Oct 14
            // :MTV FI 09"): its category says the country ("US| SOCCER PPV").
            val event = match.base == SportsMatching.EVENT_CHANNEL
            val country = if (event) {
                category?.let(SportsMatching::countryOf) ?: SportsMatching.countryOf(name)
            } else {
                SportsMatching.countryOf(name) ?: category?.let(SportsMatching::countryOf)
            }
            val key: String
            if (event) {
                key = "event:" + (category ?: name).lowercase(Locale.ROOT)
                meta.getOrPut(key) { Triple(ChannelTab.EVENT, country, category ?: name) }
            } else {
                val tab = if (country in TV_COUNTRIES) ChannelTab.TV else ChannelTab.WORLD
                val title = SportsMatching.displayName(name)
                key = "ch:" + country.orEmpty() + ":" + SportsMatching.tvKey(name).ifEmpty { name.lowercase(Locale.ROOT) }
                meta.getOrPut(key) { Triple(tab, country, title) }
            }
            groups.getOrPut(key) { mutableListOf() }.add(match)
        }
        val rows = groups.map { (key, streams) ->
            val (tab, country, title) = meta.getValue(key)
            val best = streams.first()
            val kind = when {
                tab == ChannelTab.EVENT -> ChannelKind.EVENT
                best.base < SportsMatching.SHOWS_THE_GAME -> ChannelKind.GUESS
                else -> ChannelKind.TV
            }
            ChannelGroup(key, tab, country, title, kind, streams)
        }
        return ChannelTab.entries.associateWith { tab ->
            val inTab = rows.filter { it.tab == tab }
            // Event rows: France's, the US's, the UK's and Canada's first, each by its best match.
            if (tab == ChannelTab.EVENT) inTab.sortedBy { countryRank(it.country).coerceAtMost(TV_COUNTRIES.size) }
            else inTab.sortedWith(compareBy<ChannelGroup> { countryRank(it.country) }.thenBy { it.country.orEmpty() })
        }
    }

    /** The tab a game page opens on: the first one with channels. */
    fun firstTab(groups: Map<ChannelTab, List<ChannelGroup>>): ChannelTab =
        ChannelTab.entries.firstOrNull { groups[it].orEmpty().isNotEmpty() } ?: ChannelTab.TV

    /** The row a game page lists first, whose best stream it previews and Watch would play. */
    fun first(groups: Map<ChannelTab, List<ChannelGroup>>): ChannelGroup? = groups[firstTab(groups)]?.firstOrNull()

    /** France, the US, the UK and Canada first, then the others alphabetically, no country last. */
    private fun countryRank(country: String?): Int = when (country) {
        null -> TV_COUNTRIES.size + 1
        in TV_COUNTRIES -> TV_COUNTRIES.indexOf(country)
        else -> TV_COUNTRIES.size
    }
}
