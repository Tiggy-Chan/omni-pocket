package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStatesTest {
    @Test
    fun updatesAndLogsAreIsolatedByDomain() {
        val states = TaskStates()
            .updateTask(TaskDomain.Image) {
                it.copy(running = true, progress = 35, status = "生成图像", resultPath = "image.png")
            }
            .appendLog(TaskDomain.Image, "image log")
            .appendLog(TaskDomain.Music, "music log")
            .updateTask(TaskDomain.Model) { it.copy(progress = 70, status = "下载模型") }

        assertEquals(35, states.imageTask.progress)
        assertEquals("生成图像", states.imageTask.status)
        assertEquals(listOf("image log"), states.imageTask.logs)
        assertEquals(listOf("music log"), states.musicTask.logs)
        assertEquals("准备就绪", states.musicTask.status)
        assertEquals(70, states.modelTask.progress)
        assertEquals(TaskUiState(), states.videoTask)
    }

    @Test
    fun indeterminateProgressRemainsNull() {
        val states = TaskStates(imageTask = TaskUiState(running = true, progress = 55))
            .updateTask(TaskDomain.Image) { it.copy(progress = null, status = "正在连接") }

        assertNull(states.imageTask.progress)
        assertTrue(states.imageTask.running)
        assertEquals("正在连接", states.imageTask.status)
    }

    @Test
    fun activeTaskIsMutuallyExclusiveAndReleasedOnlyByMatchingId() {
        val image = TaskStates().activate(TaskDomain.Image, "image-1", VideoBackend.LocalWan)!!

        assertEquals(TaskUiState(), image.imageTask)
        assertNull(image.activate(TaskDomain.Music, "music-1", VideoBackend.Workstation))
        assertEquals(image, image.release(TaskDomain.Image, "older-image"))
        assertEquals(image, image.release(TaskDomain.Music, "image-1"))
        assertTrue(image.isActive(TaskDomain.Image, "image-1"))
        assertEquals(VideoBackend.LocalWan, image.activeTask?.backend)

        val released = image.release(TaskDomain.Image, "image-1")
        assertNull(released.activeTask)
        val music = released.activate(TaskDomain.Music, "music-1", VideoBackend.Workstation)!!
        assertEquals(TaskDomain.Music, music.activeTask?.domain)
        assertEquals(VideoBackend.Workstation, music.activeTask?.backend)
    }

    @Test
    fun logLimitIsAppliedIndependentlyToEachDomain() {
        var states = TaskStates()
        repeat(85) { index ->
            states = states.appendLog(TaskDomain.Video, "video $index")
        }
        states = states.appendLog(TaskDomain.Music, "music")

        assertEquals(80, states.videoTask.logs.size)
        assertEquals("video 5", states.videoTask.logs.first())
        assertEquals("video 84", states.videoTask.logs.last())
        assertEquals(listOf("music"), states.musicTask.logs)
    }
}
