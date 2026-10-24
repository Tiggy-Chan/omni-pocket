package com.tiggy.qwenimage

import com.tiggy.qwenimage.data.AppDomain
import com.tiggy.qwenimage.data.TaskStates
import com.tiggy.qwenimage.data.TaskUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenUiStateTest {
    @Test
    fun compatibilityPropertiesFollowTheSelectedDomain() {
        val tasks = TaskStates(
            imageTask = TaskUiState(
                running = true,
                progress = 40,
                status = "图像状态",
                logs = listOf("图像日志"),
                error = "图像错误",
                resultPath = "image.png"
            ),
            musicTask = TaskUiState(
                progress = 65,
                status = "音乐状态",
                logs = listOf("音乐日志"),
                resultPath = "music.wav"
            )
        )
        val image = QwenUiState(tasks = tasks, alert = "独立弹窗")

        assertTrue(image.isBusy)
        assertEquals(40, image.progress)
        assertEquals("图像状态", image.status)
        assertEquals(listOf("图像日志"), image.liveLogs)
        assertEquals("图像错误", image.error)
        assertEquals("image.png", image.resultPath)
        assertEquals("独立弹窗", image.alert)

        val music = image.copy(domain = AppDomain.MusicStudio)
        assertFalse(music.isBusy)
        assertEquals(65, music.progress)
        assertEquals("音乐状态", music.status)
        assertEquals(listOf("音乐日志"), music.liveLogs)
        assertNull(music.error)
        assertEquals("music.wav", music.resultPath)
    }

    @Test
    fun compatibilityProgressDoesNotEraseIndeterminateState() {
        val state = QwenUiState(
            domain = AppDomain.VideoStudio,
            tasks = TaskStates(
                imageTask = TaskUiState(progress = 88),
                videoTask = TaskUiState(running = true, progress = null)
            )
        )

        assertEquals(0, state.progress)
        assertNull(state.videoTask.progress)
        assertEquals(88, state.copy(domain = AppDomain.ImageStudio).progress)
    }
}
