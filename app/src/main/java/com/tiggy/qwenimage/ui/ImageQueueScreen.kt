package com.tiggy.qwenimage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tiggy.qwenimage.data.GenerationJob
import com.tiggy.qwenimage.data.ImageGenerationBackend
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ImageQueueScreen(
    jobs: List<GenerationJob>,
    onCancel: (String) -> Unit,
    onReuse: (GenerationJob) -> Unit,
    onPreview: (String) -> Unit
) {
    if (jobs.isEmpty()) {
        OmniEmptyState(Icons.Outlined.Queue, "队列是空的", "提交图像任务后会显示在这里")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(jobs.asReversed(), key = { it.id }) { job ->
            ContentCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (job.state) {
                            "done" -> "完成"
                            "running" -> "生成中"
                            "queued" -> "排队中"
                            "cancelled" -> "已取消"
                            "interrupted" -> "已中断"
                            else -> "失败"
                        },
                        color = when (job.state) {
                            "done" -> OmniColors.Mint
                            "failed" -> OmniColors.Rose
                            "running" -> OmniColors.Amber
                            else -> OmniColors.Muted
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (job.backend == ImageGenerationBackend.MossWorkstation) "MOSS · 工作站" else "Qwen · 本机",
                        color = OmniColors.Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(job.createdAt)),
                        color = OmniColors.Dim,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(job.settings.prompt, color = OmniColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                val dimensions = if (job.outputWidth > 0 && job.outputHeight > 0) "${job.outputWidth} × ${job.outputHeight}" else "尺寸待生成"
                Text("$dimensions · ${job.settings.steps} 步 · seed ${job.settings.seed}", color = OmniColors.Muted, style = MaterialTheme.typography.labelSmall)
                if (job.state == "running") {
                    if (job.backend == ImageGenerationBackend.MossWorkstation) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                job.error?.let { Text(it, color = OmniColors.Rose, style = MaterialTheme.typography.bodySmall) }
                job.outputPath?.let { path ->
                    if (job.state == "done" && File(path).isFile) {
                        OmniAsyncImage(
                            path = path,
                            contentDescription = job.settings.prompt,
                            modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)).clickable { onPreview(path) },
                            maxDimension = 768
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onReuse(job) }, modifier = Modifier.weight(1f)) { Text("复用参数") }
                    if (job.state == "queued" || job.state == "running") {
                        TextButton(onClick = { onCancel(job.id) }, modifier = Modifier.weight(1f)) { Text("取消任务") }
                    }
                }
            }
        }
    }
}
