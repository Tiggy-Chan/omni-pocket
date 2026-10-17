package com.tiggy.qwenimage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tiggy.qwenimage.data.AppDomain
import com.tiggy.qwenimage.data.WorkspaceTab

private val Ink = Color(0xFFF8F6FF)
private val Muted = Color(0xFF9E9AA9)
private val Subtle = Color(0xFF6B6678)
private val Purple = Color(0xFFC5AEFF)
private val Mint = Color(0xFF8CE7D3)
private val Rose = Color(0xFFFFAEC5)
private val CardBg = Color(0xFF131424)

@Composable
fun OmniDrawerContent(
    currentDomain: AppDomain,
    onSelectDomain: (AppDomain) -> Unit,
    onSelectTab: (WorkspaceTab) -> Unit,
    onCloseDrawer: () -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier
            .width(310.dp)
            .fillMaxHeight(),
        drawerContainerColor = Color(0xFF0C0D1A),
        drawerContentColor = Ink
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(20.dp)
                .statusBarsPadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF9B7BFF), Color(0xFF5ED4BC))
                                )
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = Color.Black, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Omni Pocket", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("端侧全模态创意工坊 · v0.2.0", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                // Domain Selection
                Text("创作业务领域 (Domains)", color = Subtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Image Studio Item
                    val isImageSelected = currentDomain == AppDomain.ImageStudio
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isImageSelected) Purple.copy(alpha = 0.18f) else CardBg)
                            .border(
                                1.dp,
                                if (isImageSelected) Purple else Color.White.copy(alpha = 0.06f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                onSelectDomain(AppDomain.ImageStudio)
                                onCloseDrawer()
                            }
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Purple.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Image, null, tint = Purple, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("绘卷 · 生图工坊", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Qwen Image 2.1 · 8步 Turbo", color = Subtle, fontSize = 11.sp)
                            }
                            if (isImageSelected) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Purple)
                                )
                            }
                        }
                    }

                    // Music Studio Item
                    val isMusicSelected = currentDomain == AppDomain.MusicStudio
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isMusicSelected) Mint.copy(alpha = 0.18f) else CardBg)
                            .border(
                                1.dp,
                                if (isMusicSelected) Mint else Color.White.copy(alpha = 0.06f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                onSelectDomain(AppDomain.MusicStudio)
                                onCloseDrawer()
                            }
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Mint.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.MusicNote, null, tint = Mint, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("乐坊 · 音乐工坊", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("YuE2-3B · 48kHz 符号乐理", color = Subtle, fontSize = 11.sp)
                            }
                            if (isMusicSelected) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Mint)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                // Utilities
                Text("全局管理 (Utilities)", color = Subtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectTab(WorkspaceTab.Models)
                                onCloseDrawer()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Memory, null, tint = Muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("模型资产管理", color = Ink, fontSize = 13.sp)
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectTab(WorkspaceTab.Settings)
                                onCloseDrawer()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Settings, null, tint = Muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("引擎与硬件设置", color = Ink, fontSize = 13.sp)
                    }
                }
            }

            // Bottom Device Status Card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Speed, null, tint = Mint, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("OnePlus 12 · 24GB LPDDR5X", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Snapdragon 8 Gen 3 · Adreno 750 OpenCL", color = Subtle, fontSize = 10.sp)
                    Text("100% 端侧离线原生加速 · 无需云端", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
