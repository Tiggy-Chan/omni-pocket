package com.tiggy.qwenimage.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.KnowledgeBaseViewModel
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.KnowledgeBaseSummary
import com.tiggy.qwenimage.data.KnowledgeDocumentSummary

@Composable
fun KnowledgeBasePage(
    kbViewModel: KnowledgeBaseViewModel,
    onMenu: (() -> Unit)?,
    onUseInChat: (KnowledgeBaseSummary) -> Unit,
    onOpenModels: () -> Unit
) {
    val state by kbViewModel.state.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteBaseTarget by remember { mutableStateOf<KnowledgeBaseSummary?>(null) }
    var deleteDocTarget by remember { mutableStateOf<KnowledgeDocumentSummary?>(null) }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) kbViewModel.importDocument(uri)
    }
    val selected = state.selectedBase
    val busy = state.indexing != null

    WorkspaceScaffold(
        title = tr(R.string.nav_knowledge_base),
        subtitle = state.activeEmbeddingModel?.name ?: tr(R.string.kb_lexical_mode_subtitle),
        onMenu = onMenu,
        actions = {
            IconButton(onClick = { showCreateDialog = true }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Add, tr(R.string.kb_create_title), tint = OmniColors.Ink)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "embedding-card") {
                ReadingColumn {
                    ContentCard(title = tr(R.string.kb_embedding_section)) {
                        if (state.embeddingModels.isEmpty()) {
                            Text(
                                tr(R.string.kb_no_embedding_note),
                                color = OmniColors.Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = onOpenModels) {
                                Text(tr(R.string.kb_get_embedding_model))
                            }
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(state.embeddingModels, key = { it.id }) { model ->
                                    ComposerChip(
                                        label = model.name,
                                        onClick = { kbViewModel.selectEmbeddingModel(model.id) },
                                        selected = model.id == state.selectedEmbeddingModelId,
                                        enabled = !busy
                                    )
                                }
                            }
                        }
                    }
                }
            }

            state.indexing?.let { progress ->
                item(key = "indexing-progress") {
                    ReadingColumn {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = OmniColors.Surface,
                            border = BorderStroke(0.5.dp, OmniColors.Line),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        tr(R.string.kb_indexing_fmt, progress.fileName, progress.currentChunk, progress.totalChunks),
                                        color = OmniColors.Ink,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(onClick = kbViewModel::cancelIndexing) {
                                        Text(tr(R.string.common_cancel))
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = {
                                        if (progress.totalChunks > 0) progress.currentChunk.toFloat() / progress.totalChunks else 0f
                                    },
                                    modifier = Modifier.fillMaxWidth().height(3.dp),
                                    color = OmniColors.Accent,
                                    trackColor = OmniColors.Line
                                )
                            }
                        }
                    }
                }
            }

            state.error?.let { err ->
                item(key = "kb-error") {
                    ReadingColumn {
                        Text(err, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (state.bases.isEmpty()) {
                item(key = "kb-empty") {
                    ReadingColumn {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OmniEmptyState(
                                icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                                title = tr(R.string.kb_empty_title),
                                subtitle = tr(R.string.kb_empty_subtitle)
                            )
                            OutlinedButton(
                                onClick = { showCreateDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(0.5.dp, OmniColors.Line),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = OmniColors.Accent)
                            ) {
                                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr(R.string.kb_create_title))
                            }
                        }
                    }
                }
            } else {
                item(key = "kb-selector") {
                    ReadingColumn {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.bases, key = { it.id }) { base ->
                                ComposerChip(
                                    label = "${base.name} (${base.documentCount})",
                                    onClick = { kbViewModel.selectBase(base.id) },
                                    selected = base.id == selected?.id,
                                    enabled = !busy
                                )
                            }
                        }
                    }
                }

                if (selected != null) {
                    item(key = "kb-actions") {
                        ReadingColumn {
                            ContentCard(
                                title = selected.name,
                                trailing = {
                                    IconButton(
                                        onClick = { deleteBaseTarget = selected },
                                        enabled = !busy,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, tr(R.string.common_delete), tint = OmniColors.Danger, modifier = Modifier.size(18.dp))
                                    }
                                }
                            ) {
                                Text(
                                    tr(R.string.kb_stats_fmt, selected.documentCount, selected.chunkCount, selected.embeddedChunkCount),
                                    color = OmniColors.Muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            docPicker.launch(
                                                arrayOf(
                                                    "text/*",
                                                    "application/pdf",
                                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                                    "application/json",
                                                    "*/*"
                                                )
                                            )
                                        },
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(0.5.dp, OmniColors.Line),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OmniColors.Accent)
                                    ) {
                                        Icon(Icons.Outlined.UploadFile, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(tr(R.string.kb_add_document), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    OutlinedButton(
                                        onClick = { onUseInChat(selected) },
                                        enabled = !busy && selected.chunkCount > 0,
                                        modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(0.5.dp, OmniColors.Line),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OmniColors.Ink)
                                    ) {
                                        Icon(Icons.Outlined.ChatBubbleOutline, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(tr(R.string.kb_ask_in_chat), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (state.activeEmbeddingModel != null && selected.chunkCount > 0 && selected.embeddedChunkCount < selected.chunkCount) {
                                    TextButton(onClick = kbViewModel::reindexSelectedBase, enabled = !busy) {
                                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(tr(R.string.kb_reindex_vectors))
                                    }
                                }
                            }
                        }
                    }

                    item(key = "kb-docs-header") {
                        ReadingColumn {
                            SectionLabel(tr(R.string.kb_documents_section))
                        }
                    }

                    if (state.documents.isEmpty()) {
                        item(key = "kb-no-docs") {
                            ReadingColumn {
                                Text(
                                    tr(R.string.kb_no_documents_hint),
                                    color = OmniColors.Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    } else {
                        items(state.documents, key = { it.id }) { doc ->
                            ReadingColumn {
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.Description, null, tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                doc.fileName,
                                                color = OmniColors.Ink,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                tr(R.string.kb_doc_meta_fmt, doc.chunkCount, doc.charCount, doc.embeddedChunkCount),
                                                color = OmniColors.Muted,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        IconButton(
                                            onClick = { deleteDocTarget = doc },
                                            enabled = !busy,
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(Icons.Outlined.DeleteOutline, tr(R.string.common_delete), tint = OmniColors.Dim, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(tr(R.string.kb_create_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    placeholder = { Text(tr(R.string.kb_name_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        kbViewModel.createBase(name)
                        showCreateDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(tr(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(tr(R.string.common_cancel)) }
            },
            containerColor = OmniColors.Raised
        )
    }

    deleteBaseTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteBaseTarget = null },
            title = { Text(tr(R.string.kb_delete_title, target.name)) },
            text = { Text(tr(R.string.kb_delete_body)) },
            confirmButton = {
                TextButton(onClick = { kbViewModel.deleteBase(target.id); deleteBaseTarget = null }) {
                    Text(tr(R.string.common_delete), color = OmniColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteBaseTarget = null }) { Text(tr(R.string.common_cancel)) }
            },
            containerColor = OmniColors.Raised
        )
    }

    deleteDocTarget?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleteDocTarget = null },
            title = { Text(tr(R.string.common_delete)) },
            text = { Text(doc.fileName) },
            confirmButton = {
                TextButton(onClick = { kbViewModel.deleteDocument(doc.id); deleteDocTarget = null }) {
                    Text(tr(R.string.common_delete), color = OmniColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDocTarget = null }) { Text(tr(R.string.common_cancel)) }
            },
            containerColor = OmniColors.Raised
        )
    }
}
