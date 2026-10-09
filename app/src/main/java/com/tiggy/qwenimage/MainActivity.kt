package com.tiggy.qwenimage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.tiggy.qwenimage.ui.QwenApp

class MainActivity : ComponentActivity() {
    private val viewModel: QwenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { QwenApp(viewModel) }
            }
        }
    }
}
