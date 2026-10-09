package com.tiggy.qwenimage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import com.tiggy.qwenimage.ui.QwenApp

class MainActivity : ComponentActivity() {
    private val viewModel: QwenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFFB7A1FF),
                secondary = androidx.compose.ui.graphics.Color(0xFF85E6D1),
                background = androidx.compose.ui.graphics.Color(0xFF090A12),
                surface = androidx.compose.ui.graphics.Color(0xFF111321)
            )) {
                Surface { QwenApp(viewModel) }
            }
        }
    }
}
