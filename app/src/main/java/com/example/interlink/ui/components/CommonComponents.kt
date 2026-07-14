package com.example.interlink.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun IntercomButton(
    isTalking: Boolean,
    onClick: () -> Unit,
    size: Dp = 150.dp,
    iconSize: Dp = 40.dp,
    activeLabel: String = "STOP",
    inactiveLabel: String = "TALK"
) {
    val infiniteTransition = rememberInfiniteTransition(label = "talking")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(contentAlignment = Alignment.Center) {
        if (isTalking) {
            Box(
                modifier = Modifier
                    .size(size + 20.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
            )
        }

        Button(
            onClick = onClick,
            modifier = Modifier.size(size),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isTalking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (isTalking) activeLabel else inactiveLabel,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
