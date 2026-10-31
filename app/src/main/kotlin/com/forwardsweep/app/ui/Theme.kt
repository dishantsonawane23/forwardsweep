package com.forwardsweep.app.ui

import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.forwardsweep.classifier.Category

@Composable
fun ForwardSweepTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Category colours. Real Photo follows the dynamic primary colour; the junk and screenshot hues
 * are fixed so the ring chart reads the same on every wallpaper, with lighter tones in dark theme.
 */
@Composable
@ReadOnlyComposable
fun Category.color(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        Category.REAL_PHOTO -> MaterialTheme.colorScheme.primary
        Category.GREETING -> if (dark) Color(0xFFF2B95C) else Color(0xFFC77D10)
        Category.MEME -> if (dark) Color(0xFFF08CB0) else Color(0xFFC2416F)
        Category.SCREENSHOT -> if (dark) Color(0xFF6EC6E0) else Color(0xFF237E9C)
        Category.UNCERTAIN -> MaterialTheme.colorScheme.outline
    }
}

val Category.icon: ImageVector
    get() = when (this) {
        Category.REAL_PHOTO -> Icons.Outlined.PhotoCamera
        Category.GREETING -> Icons.Outlined.LocalFlorist
        Category.MEME -> Icons.Outlined.EmojiEmotions
        Category.SCREENSHOT -> Icons.Outlined.Screenshot
        Category.UNCERTAIN -> Icons.Outlined.HelpOutline
    }

/** Plural title used for screens and cards. */
val Category.title: String
    get() = when (this) {
        Category.REAL_PHOTO -> "Real photos & videos"
        Category.GREETING -> "Greetings"
        Category.MEME -> "Memes"
        Category.SCREENSHOT -> "Screenshots & documents"
        Category.UNCERTAIN -> "Uncertain"
    }

val Category.blurb: String
    get() = when (this) {
        Category.REAL_PHOTO -> "Your own camera shots. Never pre-selected."
        Category.GREETING -> "Good morning and festival wishes, images and videos."
        Category.MEME -> "Captioned jokes and forwards."
        Category.SCREENSHOT -> "Screenshots, receipts, documents."
        Category.UNCERTAIN -> "Not sure enough to sort. Worth a look."
    }

fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

fun plural(n: Int, one: String, many: String = one + "s") = "%,d %s".format(n, if (n == 1) one else many)
