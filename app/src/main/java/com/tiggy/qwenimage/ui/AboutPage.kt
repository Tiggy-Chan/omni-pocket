package com.tiggy.qwenimage.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tiggy.qwenimage.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AUTHOR_NAME = "Tiggy-Chan"
private const val AUTHOR_GITHUB_URL = "https://github.com/Tiggy-Chan"
private const val OMNI_REPO_URL = "https://github.com/Tiggy-Chan/omni-pocket"

private enum class SponsorChannel(@DrawableRes val qrRes: Int, val fileName: String) {
    WeChat(R.drawable.donate_wechat, "omni-pocket-wechat-pay.png"),
    Alipay(R.drawable.donate_alipay, "omni-pocket-alipay.jpg")
}

@Composable
fun AboutPage(onMenu: (() -> Unit)?) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var sponsorChannel by rememberSaveable { mutableStateOf(SponsorChannel.WeChat) }
    var zoomedChannel by remember { mutableStateOf<SponsorChannel?>(null) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }
    var copiedRepo by remember { mutableStateOf(false) }

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "0.1.0" }
    }

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    val savedSuccessMsg = tr(R.string.about_qr_saved)
    val savedErrorMsg = tr(R.string.about_qr_save_failed)

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

                // Author & Open Source Projects
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

                // Sponsor Section (WeChat Pay & Alipay QR codes)
                item(key = "sponsor") {
                    SectionLabel(tr(R.string.about_section_sponsor))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = OmniColors.Surface,
                        border = BorderStroke(0.5.dp, OmniColors.Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = tr(R.string.about_sponsor_desc),
                                color = OmniColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                            val wechatLabel = tr(R.string.about_wechat_pay)
                            val alipayLabel = tr(R.string.about_alipay)
                            OmniTabs(
                                items = SponsorChannel.entries,
                                selected = sponsorChannel,
                                label = {
                                    when (it) {
                                        SponsorChannel.WeChat -> wechatLabel
                                        SponsorChannel.Alipay -> alipayLabel
                                    }
                                },
                                onSelect = {
                                    sponsorChannel = it
                                    saveFeedback = null
                                }
                            )
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White,
                                border = BorderStroke(0.5.dp, OmniColors.Line),
                                modifier = Modifier
                                    .widthIn(max = 260.dp)
                                    .fillMaxWidth(0.76f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { zoomedChannel = sponsorChannel }
                            ) {
                                Image(
                                    painter = painterResource(sponsorChannel.qrRes),
                                    contentDescription = when (sponsorChannel) {
                                        SponsorChannel.WeChat -> tr(R.string.about_wechat_pay)
                                        SponsorChannel.Alipay -> tr(R.string.about_alipay)
                                    },
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.75f)
                                        .padding(10.dp)
                                )
                            }
                            Text(
                                text = tr(R.string.about_tap_zoom),
                                color = OmniColors.Dim,
                                style = MaterialTheme.typography.labelSmall
                            )
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val ok = saveQrCodeToGallery(context, sponsorChannel)
                                        saveFeedback = if (ok) savedSuccessMsg else savedErrorMsg
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(0.5.dp, OmniColors.Line),
                                modifier = Modifier.heightIn(min = 42.dp)
                            ) {
                                Icon(Icons.Outlined.Download, null, tint = OmniColors.Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr(R.string.about_save_qr), color = OmniColors.Ink)
                            }
                            saveFeedback?.let { msg ->
                                Text(
                                    text = msg,
                                    color = if (msg == savedSuccessMsg) OmniColors.Success else OmniColors.Danger,
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center
                                )
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

    zoomedChannel?.let { channel ->
        Dialog(
            onDismissRequest = { zoomedChannel = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable { zoomedChannel = null }
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (channel) {
                                    SponsorChannel.WeChat -> tr(R.string.about_wechat_pay)
                                    SponsorChannel.Alipay -> tr(R.string.about_alipay)
                                },
                                color = Color(0xFF1F2328),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { zoomedChannel = null }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Close, tr(R.string.common_close), tint = Color(0xFF5F6670))
                            }
                        }
                        Image(
                            painter = painterResource(channel.qrRes),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.74f)
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val ok = saveQrCodeToGallery(context, channel)
                                    saveFeedback = if (ok) savedSuccessMsg else savedErrorMsg
                                    zoomedChannel = null
                                }
                            }
                        ) {
                            Text(tr(R.string.about_save_qr), color = Color(0xFF3A6FE0))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun saveQrCodeToGallery(context: Context, channel: SponsorChannel): Boolean =
    withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeResource(context.resources, channel.qrRes) ?: return@withContext false
        var insertedUri: Uri? = null
        try {
            val isPng = channel.fileName.endsWith(".png", ignoreCase = true)
            val mime = if (isPng) "image/png" else "image/jpeg"
            val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, channel.fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OmniPocket")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
            insertedUri = uri
            resolver.openOutputStream(uri)?.use { out ->
                check(bitmap.compress(format, 96, out))
            } ?: error("Failed to open gallery output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            insertedUri = null
            true
        } catch (_: Throwable) {
            insertedUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            false
        } finally {
            bitmap.recycle()
        }
    }
