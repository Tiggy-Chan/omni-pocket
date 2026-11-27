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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AUTHOR_NAME = "Tiggy-Chan"
private const val AUTHOR_GITHUB_URL = "https://github.com/Tiggy-Chan"
private const val OMNI_REPO_URL = "https://github.com/Tiggy-Chan/omni-pocket"
private const val QQ_GROUP_NUMBER = "1128661823"

private enum class AboutQrAsset(@DrawableRes val qrRes: Int, val fileName: String) {
    WeChat(R.drawable.donate_wechat, "omni-pocket-wechat-pay.png"),
    Alipay(R.drawable.donate_alipay, "omni-pocket-alipay.jpg"),
    CommunityQq(R.drawable.community_qq_qr, "omni-pocket-qq-group.jpg")
}

private enum class SponsorChannel(val asset: AboutQrAsset) {
    WeChat(AboutQrAsset.WeChat),
    Alipay(AboutQrAsset.Alipay)
}

@Composable
fun AboutPage(onMenu: (() -> Unit)?) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val updateState by AppUpdateManager.state.collectAsStateWithLifecycle()

    var sponsorChannel by rememberSaveable { mutableStateOf(SponsorChannel.WeChat) }
    var zoomedQr by remember { mutableStateOf<AboutQrAsset?>(null) }
    var sponsorFeedback by remember { mutableStateOf<String?>(null) }
    var groupFeedback by remember { mutableStateOf<String?>(null) }
    var copiedRepo by remember { mutableStateOf(false) }
    var copiedGroup by remember { mutableStateOf(false) }

    val version = remember(context) { AppUpdateManager.currentAppVersion(context) }

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    val sponsorSavedMsg = tr(R.string.about_qr_saved)
    val groupSavedMsg = tr(R.string.about_group_qr_saved)
    val saveFailedMsg = tr(R.string.about_qr_save_failed)

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
                // Brand Hero + Version & Self-Update entry
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
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            onClick = {
                                if (updateState.hasUpdate) {
                                    AppUpdateManager.openUpdateDialog()
                                } else if (!updateState.checking) {
                                    AppUpdateManager.checkForUpdate(context, manual = true)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (updateState.hasUpdate) OmniColors.AccentSoft else OmniColors.Field,
                            border = BorderStroke(0.5.dp, if (updateState.hasUpdate) OmniColors.Accent.copy(alpha = 0.35f) else OmniColors.Line)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${tr(R.string.about_version)} $version",
                                    color = if (updateState.hasUpdate) OmniColors.Accent else OmniColors.Muted,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (updateState.checking) {
                                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.6.dp, color = OmniColors.Accent)
                                } else if (updateState.hasUpdate) {
                                    UpdateNewBadge()
                                }
                            }
                        }
                        if (updateState.upToDateNotice) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = tr(R.string.update_already_latest),
                                color = OmniColors.Success,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else if (!updateState.error.isNullOrBlank() && !updateState.showDialog) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = updateState.error.orEmpty(),
                                color = OmniColors.Danger,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
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

                // Community QQ Group QR
                item(key = "community") {
                    SectionLabel(tr(R.string.about_section_community))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = OmniColors.Surface,
                        border = BorderStroke(0.5.dp, OmniColors.Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = tr(R.string.about_group_desc, QQ_GROUP_NUMBER),
                                color = OmniColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF161618),
                                border = BorderStroke(0.5.dp, OmniColors.Line),
                                modifier = Modifier
                                    .widthIn(max = 220.dp)
                                    .fillMaxWidth()
                                    .clickable { zoomedQr = AboutQrAsset.CommunityQq }
                            ) {
                                Image(
                                    painter = painterResource(AboutQrAsset.CommunityQq.qrRes),
                                    contentDescription = tr(R.string.about_section_community),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.68f).padding(6.dp)
                                )
                            }
                            Text(
                                text = tr(R.string.about_tap_zoom),
                                color = OmniColors.Dim,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(QQ_GROUP_NUMBER))
                                        copiedGroup = true
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(0.5.dp, OmniColors.Line),
                                    modifier = Modifier.weight(1f).heightIn(min = 42.dp)
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, null, tint = OmniColors.Muted, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (copiedGroup) tr(R.string.common_copied) else tr(R.string.about_copy_group_id),
                                        color = OmniColors.Ink,
                                        maxLines = 1
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val ok = saveQrToGallery(context, AboutQrAsset.CommunityQq)
                                            groupFeedback = if (ok) groupSavedMsg else saveFailedMsg
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(0.5.dp, OmniColors.Line),
                                    modifier = Modifier.weight(1f).heightIn(min = 42.dp)
                                ) {
                                    Icon(Icons.Outlined.Download, null, tint = OmniColors.Accent, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(tr(R.string.about_save_group_qr), color = OmniColors.Accent, maxLines = 1)
                                }
                            }
                            groupFeedback?.let { feedback ->
                                Text(
                                    text = feedback,
                                    color = if (feedback == groupSavedMsg) OmniColors.Success else OmniColors.Danger,
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Sponsor QR codes (WeChat Pay / Alipay)
                item(key = "sponsor") {
                    val wechatLabel = tr(R.string.about_wechat_pay)
                    val alipayLabel = tr(R.string.about_alipay)
                    SectionLabel(tr(R.string.about_section_sponsor))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = OmniColors.Surface,
                        border = BorderStroke(0.5.dp, OmniColors.Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = tr(R.string.about_sponsor_desc),
                                color = OmniColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                                    sponsorFeedback = null
                                }
                            )
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.White,
                                border = BorderStroke(0.5.dp, OmniColors.Line),
                                modifier = Modifier
                                    .widthIn(max = 240.dp)
                                    .fillMaxWidth()
                                    .clickable { zoomedQr = sponsorChannel.asset }
                            ) {
                                Image(
                                    painter = painterResource(sponsorChannel.asset.qrRes),
                                    contentDescription = if (sponsorChannel == SponsorChannel.WeChat) wechatLabel else alipayLabel,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.82f).padding(8.dp)
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
                                        val ok = saveQrToGallery(context, sponsorChannel.asset)
                                        sponsorFeedback = if (ok) sponsorSavedMsg else saveFailedMsg
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(0.5.dp, OmniColors.Line),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
                            ) {
                                Icon(Icons.Outlined.Download, null, tint = OmniColors.Accent, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr(R.string.about_save_qr), color = OmniColors.Accent)
                            }
                            sponsorFeedback?.let { feedback ->
                                Text(
                                    text = feedback,
                                    color = if (feedback == sponsorSavedMsg) OmniColors.Success else OmniColors.Danger,
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

    zoomedQr?.let { qr ->
        Dialog(
            onDismissRequest = { zoomedQr = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { zoomedQr = null },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (qr == AboutQrAsset.CommunityQq) Color(0xFF161618) else Color.White,
                        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
                    ) {
                        Image(
                            painter = painterResource(qr.qrRes),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                val ok = saveQrToGallery(context, qr)
                                val msg = if (ok) {
                                    if (qr == AboutQrAsset.CommunityQq) groupSavedMsg else sponsorSavedMsg
                                } else saveFailedMsg
                                if (qr == AboutQrAsset.CommunityQq) groupFeedback = msg else sponsorFeedback = msg
                            }
                        }) {
                            Text(
                                if (qr == AboutQrAsset.CommunityQq) tr(R.string.about_save_group_qr) else tr(R.string.about_save_qr),
                                color = Color.White
                            )
                        }
                        IconButton(onClick = { zoomedQr = null }) {
                            Icon(Icons.Outlined.Close, tr(R.string.common_close_preview), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun saveQrToGallery(context: Context, asset: AboutQrAsset): Boolean =
    withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeResource(context.resources, asset.qrRes) ?: return@withContext false
        var insertedUri: Uri? = null
        try {
            val isPng = asset.fileName.endsWith(".png", ignoreCase = true)
            val mime = if (isPng) "image/png" else "image/jpeg"
            val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, asset.fileName)
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
