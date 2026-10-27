package org.screenlite.webkiosk.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun TouchKioskInputOverlay(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    // Taps on it never reach the page, so it stays small enough to sit in the page's own margin
    touchAreaSize: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(touchAreaSize)
            .pointerInput(Unit) {
                detectTapGestures { onTap() }
            }
    )
}


