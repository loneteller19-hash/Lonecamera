package com.lone.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shape as UiShape

val AppleYellow = Color(0xFFFFD60A)
val AppleRed = Color(0xFFFF3B30)

/** Frosted-glass look: translucent white wash, bright top rim, faint bottom rim. */
fun Modifier.glass(shape: UiShape, strength: Float = 0.16f): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = strength + 0.08f), Color.Black.copy(alpha = 0.28f)),
        ),
    )
    .background(Color.White.copy(alpha = strength * 0.4f))
    .border(
        0.8.dp,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f))),
        shape,
    )

@Composable
fun GlassIcon(
    icon: ImageVector,
    active: Boolean = false,
    size: Int = 44,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size.dp)
            .glass(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = tint ?: if (active) AppleYellow else Color.White,
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}

@Composable
fun GlassPill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .glass(RoundedCornerShape(50), if (selected) 0.28f else 0.12f)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) AppleYellow else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun GlassToggleRow(title: String, sub: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .glass(RoundedCornerShape(18.dp), 0.1f)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
        Box(
            Modifier
                .size(width = 46.dp, height = 28.dp)
                .clip(RoundedCornerShape(50))
                .background(if (on) Color(0xFF34C759) else Color.White.copy(alpha = 0.2f))
                .padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White))
        }
    }
}
