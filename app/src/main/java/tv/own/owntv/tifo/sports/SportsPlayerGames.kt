package tv.own.owntv.tifo.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * The games live right now as a row of cards along the bottom of the playing video: what Down
 * opens while a game started from its page plays. The playing game takes the cursor; OK on
 * another one switches to it. Back, or Down again, closes it.
 */
@Composable
fun SportsPlayerGames(
    games: List<SportsEvent>,
    currentId: String?,
    onPick: (SportsEvent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val favoriteKeys by remember { SportsFavorites.get(context) }.keys.collectAsStateWithLifecycle()
    val hideScores by remember { SportsDisplay.get(context) }.hideScores.collectAsStateWithLifecycle()
    val start = games.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val rowState = rememberLazyListState((start - 1).coerceAtLeast(0))
    val focusStart = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusStart.requestFocus() }
    }
    BackHandler { onDismiss() }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                // Down again closes it; the cursor never leaves it any other way.
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection == FocusDirection.Down) onDismiss()
                        cancelFocusChange()
                    }
                }
                .focusGroup()
                .padding(top = 60.mpx, bottom = 40.mpx),
            verticalArrangement = Arrangement.spacedBy(14.mpx),
        ) {
            Text(
                stringResource(R.string.tifo_sports_live_now),
                style = stageText(24, 800), color = StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 64.mpx),
            )
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(20.mpx),
                contentPadding = PaddingValues(horizontal = 64.mpx),
                modifier = Modifier.focusGroup(),
            ) {
                items(games, key = { it.id }) { event ->
                    GameCard(
                        event = event,
                        showLeague = true,
                        isFavorite = { team -> SportsFavorites.isFavorite(favoriteKeys, event.competition, team) },
                        hideScore = hideScores,
                        onClick = { if (event.id == currentId) onDismiss() else onPick(event) },
                        modifier = if (event.id == games.getOrNull(start)?.id) Modifier.focusRequester(focusStart) else Modifier,
                    )
                }
            }
        }
    }
}
