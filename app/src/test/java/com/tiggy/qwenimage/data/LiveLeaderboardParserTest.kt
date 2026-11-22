package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLeaderboardParserTest {

    @Test
    fun parsesArtificialAnalysisOpenSourceTinyModels() {
        val rscPayload = """{"initialModels":[
            {"id":"1","slug":"k2-horizon-3-7b","name":"K2 Horizon 3.7B","shortName":"K2 Horizon 3.7B","parameters":3.7,"contextWindowTokens":524288,"intelligenceIndex":8.02,"isReasoning":true,"modelCreator":{"name":"IFM"}},
            {"id":"2","slug":"lfm3-1-7b","name":"LFM3-1.7B","shortName":"LFM3-1.7B","parameters":1.73,"contextWindowTokens":131072,"intelligenceIndex":5.99,"isReasoning":false,"creator":{"name":"Liquid AI"}}
        ]}"""
        val escaped = rscPayload.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "")
        val html = """<script>self.__next_f.push([1,"$escaped"])</script>"""

        val entries = LiveLeaderboardClient.parseAaOpenSourceModels(html, LiveChatTier.Tiny)
        assertEquals(2, entries.size)
        assertEquals(1, entries[0].rank)
        assertEquals("K2 Horizon 3.7B", entries[0].name)
        assertEquals("IFM", entries[0].creator)
        assertTrue(entries[0].primaryMetricLabel.contains("8.0"))
        assertTrue(entries[0].openWeights)
        assertEquals(HubSearchCategory.GgufChat, entries[0].searchCategory)
        assertNotNull(entries[0].estimatedBytes)
    }

    @Test
    fun parsesArtificialAnalysisDecisionIndexAndTagsOpenWeights() {
        val rscPayload = """[{"slug":"baseten_bison-31b","kind":"decisionModel","name":"Bison 31B","creatorName":"Base Labs","indexScore":75.88,"accuracyIndexScore":63.32,"timePerTaskMs":120.0},
            {"slug":"baseten_elephant-9b","kind":"decisionModel","name":"Elephant 9B","creatorName":"Base Labs","indexScore":66.39,"accuracyIndexScore":45.66,"timePerTaskMs":78.75},
            {"slug":"liquid-ai_d1-2-ultra","kind":"decisionModel","name":"d1.2-ultra","creatorName":"Liquid AI","indexScore":74.85,"accuracyIndexScore":61.56}]"""
        val escaped = rscPayload.replace("\"", "\\\"").replace("\n", "")
        val html = """<script>self.__next_f.push([1,"$escaped"])</script>"""

        val entries = LiveLeaderboardClient.parseAaDecisionIndex(html)
        assertEquals(3, entries.size)
        assertEquals("Bison 31B", entries[0].name)
        assertEquals("d1.2-ultra", entries[1].name)
        assertFalse(entries[1].openWeights)
        assertEquals("Elephant 9B", entries[2].name)
        assertTrue(entries[2].openWeights)
        assertEquals("mradermacher/elephant-1-GGUF", entries[2].huggingFaceRepo)
        assertEquals(HubSearchCategory.DecisionGguf, entries[2].searchCategory)
    }

    @Test
    fun parsesArtificialAnalysisImageArenaAndStopsBeforeSubcategoryTables() {
        val rscPayload = """{"rows":[
            {"values":{"id":"m0","name":"GPT Image 2.6","url":"/image/gpt","rank":0,"elo":1262.99,"appearances":5421,"creator":{"name":"OpenAI"},"openWeightsUrl":null}},
            {"values":{"id":"m1","name":"Z-Image Turbo","url":"/image/z","rank":1,"elo":936.30,"appearances":8879,"creator":{"name":"Alibaba"},"openWeightsUrl":"https://huggingface.co/Tongyi-MAI/Z-Image-Turbo"}},
            {"values":{"id":"m2","name":"Qwen-Image-3.0-Pro","url":null,"rank":2,"elo":900.0,"appearances":1000,"creator":{"name":"Alibaba"},"openWeightsUrl":null}},
            {"values":{"id":"m3","name":"Model D","url":null,"rank":3,"elo":880.0,"appearances":1000,"creator":{"name":"Lab"},"openWeightsUrl":null}},
            {"values":{"id":"m4","name":"Model E","url":null,"rank":4,"elo":860.0,"appearances":1000,"creator":{"name":"Lab"},"openWeightsUrl":null}},
            {"values":{"id":"m5","name":"Model F","url":null,"rank":5,"elo":840.0,"appearances":1000,"creator":{"name":"Lab"},"openWeightsUrl":null}},
            {"values":{"id":"sub0","name":"Subcategory Duplicate","url":null,"rank":0,"elo":1299.0,"appearances":500,"creator":{"name":"OpenAI"},"openWeightsUrl":null}}
        ]}"""
        val escaped = rscPayload.replace("\"", "\\\"").replace("\n", "")
        val html = """<script>self.__next_f.push([1,"$escaped"])</script>"""

        val entries = LiveLeaderboardClient.parseAaArenaLeaderboard(html, BenchmarkModality.Image)
        assertEquals(6, entries.size)
        assertEquals("GPT Image 2.6", entries[0].name)
        assertFalse(entries[0].openWeights)
        assertEquals("Z-Image Turbo", entries[1].name)
        assertTrue(entries[1].openWeights)
        assertEquals("unsloth/Z-Image-Turbo-GGUF", entries[1].huggingFaceRepo)
        assertEquals("Qwen-Image-3.0-Pro", entries[2].name)
        assertFalse(entries[2].openWeights)
        assertTrue(entries.none { it.name == "Subcategory Duplicate" })
    }

    @Test
    fun parsesJevDecisionIndexAndMapsOneJevRepos() {
        val json = """{
            "edition":"0.4",
            "models":[
                {"engine":"karpo-decision-31b","name":"karpo-decision-31B","v":73.86,"pub":75.05,"rank_tie":1},
                {"engine":"onejev1.6-4b","name":"OneJev1.6-4B","v":62.01,"pub":62.01,"rank_tie":12}
            ]
        }"""
        val entries = LiveLeaderboardClient.parseJevDecisionIndex(json)
        assertEquals(2, entries.size)
        assertEquals("karpo-decision-31B", entries[0].name)
        assertEquals("OneJev1.6-4B", entries[1].name)
        assertTrue(entries[1].openWeights)
        assertEquals("mradermacher/OneJev1.6-4B-GGUF", entries[1].huggingFaceRepo)
    }

    @Test
    fun parsesHfOpenLlmLeaderboardRows() {
        val json = """{
            "rows":[
                {"row":{"fullname":"unsloth/phi-4-mini","Average ⬆️":39.21,"#Params (B)":3.8,"Hub License":"mit","Available on the hub":true}},
                {"row":{"fullname":"private/removed","Average ⬆️":50.0,"#Params (B)":7.0,"Hub License":"mit","Available on the hub":false}}
            ]
        }"""
        val entries = LiveLeaderboardClient.parseHfOpenLlmLeaderboard(json)
        assertEquals(1, entries.size)
        assertEquals("phi-4-mini", entries[0].name)
        assertEquals("unsloth", entries[0].creator)
        assertTrue(entries[0].openWeights)
        assertEquals(HubSearchCategory.GgufChat, entries[0].searchCategory)
    }
}
