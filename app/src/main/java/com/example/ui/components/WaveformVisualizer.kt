package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.data.VoiceState
import com.example.ui.theme.SanaGold
import com.example.ui.theme.SanaPink
import com.example.ui.theme.SanaPurpleLight

@Composable
fun WaveformVisualizer(
    voiceState: VoiceState,
    modifier: Modifier = Modifier,
    barCount: Int = 18
) {
    val transition = rememberInfiniteTransition(label = "WaveTransition")

    val pulseScale by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    val activeGradient = Brush.verticalGradient(
        listOf(
            SanaPink,
            SanaPurpleLight,
            SanaGold
        )
    )

    val idleGradient = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
        )
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val baseHeights = listOf(
            0.25f, 0.45f, 0.7f, 0.9f, 0.6f, 0.85f, 1.0f, 0.75f,
            0.8f, 0.95f, 0.65f, 0.85f, 0.7f, 0.5f, 0.35f, 0.25f,
            0.6f, 0.4f
        )

        for (i in 0 until barCount) {
            val base = baseHeights.getOrElse(i) { 0.5f }

            val barHeightFraction = when (voiceState) {
                VoiceState.SPEAKING -> {
                    val factor = ((i % 4) * 0.2f)
                    ((base * pulseScale + factor) % 1.0f).coerceIn(0.2f, 1.0f)
                }
                VoiceState.LISTENING -> {
                    ((base * 0.85f * pulseScale) + 0.15f).coerceIn(0.2f, 0.95f)
                }
                VoiceState.PROCESSING -> {
                    val shift = ((i + (pulseScale * 8).toInt()) % barCount).toFloat() / barCount
                    (0.3f + shift * 0.5f).coerceIn(0.25f, 0.85f)
                }
                VoiceState.ERROR -> 0.18f
                VoiceState.IDLE -> 0.12f
            }

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(barHeightFraction)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (voiceState == VoiceState.IDLE || voiceState == VoiceState.ERROR) idleGradient else activeGradient
                    )
            )
        }
    }
}
