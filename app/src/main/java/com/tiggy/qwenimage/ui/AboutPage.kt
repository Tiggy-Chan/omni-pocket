package com.tiggy.qwenimage.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tiggy.qwenimage.R

private const val AUTHOR_NAME = "Tiggy-Chan"
private const val AUTHOR_GITHUB_URL = "https://github.com/Tiggy-Chan"
private const val OMNI_REPO_URL = "https://github.com/Tiggy-Chan/omni-pocket"

@Composable
fun AboutPage(onMenu: (() -> Unit)?) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copiedRepo by remember { mutableStateOf(false) }

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "0.2.1" }
    }

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    WorkspaceScaffold(title = tr(R.string.nav_about), onMenu = onMenu) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Brand Hero
                item(key = "hero") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        OmniBrandMark(modifier = Modifier.size(68.dp), cornerRadius = 16.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Omni Pocket",
                            color = OmniColors.Ink,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${tr(R.string.about_version)} $version",
                            color = OmniColors.Dim,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = tr(R.string.about_tagline),
                            color = OmniColors.Muted,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Author & Open Source Project
                item(key = "project") {
                    SectionLabel(tr(R.string.about_section_project))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = OmniColors.Surface,
                        border = BorderStroke(0.5.dp, OmniColors.Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            SettingRow(
                                title = tr(R.string.about_author),
                                detail = "$AUTHOR_NAME · github.com/$AUTHOR_NAME",
                                onClick = { openUrl(AUTHOR_GITHUB_URL) },
                                trailing = {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.OpenInNew,
                                        contentDescription = null,
                                        tint = OmniColors.Muted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)

                            // Omni Pocket GitHub + Star CTA
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = tr(R.string.about_repo_title),
                                    color = OmniColors.Ink,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = tr(R.string.about_star_prompt),
                                    color = OmniColors.Muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { openUrl(OMNI_REPO_URL) },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = OmniColors.Accent,
                                            contentColor = OmniColors.OnAccent
                                        ),
                                        modifier = Modifier.heightIn(min = 40.dp)
                                    ) {
                                        Icon(Icons.Outlined.StarBorder, null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(tr(R.string.about_star_button))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(OMNI_REPO_URL))
                                            copiedRepo = true
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(0.5.dp, OmniColors.Line),
                                        modifier = Modifier.heightIn(min = 40.dp)
                                    ) {
                                        Icon(Icons.Outlined.ContentCopy, null, tint = OmniColors.Muted, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (copiedRepo) tr(R.string.common_copied) else tr(R.string.common_copy),
                                            color = OmniColors.Ink
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Local-first privacy note
                item(key = "local-note") {
                    Text(
                        text = tr(R.string.about_local_note),
                        color = OmniColors.Dim,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}
