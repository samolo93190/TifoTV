package tv.own.owntv.tifo.sports

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the Sports page shows games, kept on the device: for now, whether the cards hide the score. */
class SportsDisplay private constructor(private val prefs: SharedPreferences) {

    private val _hideScores = MutableStateFlow(prefs.getBoolean(KEY_HIDE_SCORES, false))

    /** The cards show "vs" in place of the score, for games watched later. */
    val hideScores: StateFlow<Boolean> = _hideScores.asStateFlow()

    fun toggleScores() {
        val next = !_hideScores.value
        _hideScores.value = next
        prefs.edit().putBoolean(KEY_HIDE_SCORES, next).apply()
    }

    companion object {
        private const val PREFS = "tifo_sports_display"
        private const val KEY_HIDE_SCORES = "hide_scores"

        @Volatile private var instance: SportsDisplay? = null

        fun get(context: Context): SportsDisplay = instance ?: synchronized(this) {
            instance ?: SportsDisplay(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
