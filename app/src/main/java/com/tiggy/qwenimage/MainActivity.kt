package com.tiggy.qwenimage

import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.tiggy.qwenimage.ui.QwenApp

class MainActivity : ComponentActivity() {
    private val viewModel: QwenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFC5AEFF),
                secondary = Color(0xFF8CE7D3),
                background = Color(0xFF090A14),
                surface = Color.Transparent,
                surfaceVariant = Color(0xFF171729),
                onSurface = Color(0xFFF8F6FF)
            )) {
                Surface(color = Color.Transparent) { QwenApp(viewModel) }
            }
        }
    }
}
