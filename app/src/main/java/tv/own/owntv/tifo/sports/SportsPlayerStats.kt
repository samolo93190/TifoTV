package tv.own.owntv.tifo.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.components.ContentPanelFill
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.roundedPanel

/**
 * The game's score and ESPN's details over the playing video, on the right where the watch
 * history is for other channels: what Right opens while a game started from its page plays.
 * Back, or pushing Right past the last tab, closes it.
 */
@Composable
fun SportsPlayerStats(event: SportsEvent, state: SportsUiState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val layoutDirection = LocalLayoutDirection.current
    val firstTab = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstTab.requestFocus() }
    }
    BackHandler { onDismiss() }

    Box(modifier.fillMaxSize().modalScrim(strength = 0.58f)) {
        GameStatsPanel(
            event = event,
            state = state,
            firstTab = firstTab,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(620.dp)
                .roundedPanel(radius = 22.dp, fillColor = ContentPanelFill, surface = GlassSurface.DIALOGS)
                // Right moves between the tabs; only past the last one does it close the panel. The
                // cursor never leaves it any other way.
                .focusProperties {
                    onExit = {
                        val outward = if (layoutDirection == LayoutDirection.Rtl) FocusDirection.Left else FocusDirection.Right
                        if (requestedFocusDirection == outward) onDismiss()
                        cancelFocusChange()
                    }
                }
                .focusGroup()
                .padding(20.dp),
        )
    }
}
