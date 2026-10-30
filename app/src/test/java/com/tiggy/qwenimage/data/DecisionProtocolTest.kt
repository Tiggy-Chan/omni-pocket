package com.tiggy.qwenimage.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionProtocolTest {
    @Test
    fun officialTicketQuestionsRenderExactly() {
        val (state, questions) = parseDecisionRequest("""
            {"state":"I was charged twice this month, please refund one of them.","questions":{
              "refund":{"type":"noul","instructions":"Is the customer asking for a refund?"},
              "team":{"type":"choice","instructions":"Which team should handle this?","criteria":{
                "billing":"Charges, refunds, invoices", "technical":"App or site faults", "fraud":"Suspected unauthorised use"}},
              "urgency":{"type":"score","instructions":"How urgent is this?","criteria":["Can wait","Today","Blocking the customer now"]}}}
        """.trimIndent())
        val plan = planDecisionRequest(state, questions)
        val branches = plan.branches.associateBy { it.question.id }
        assertEquals(3, plan.branches.size)
        assertEquals("Question: Is the customer asking for a refund?\n\nOptions:\n" +
            "A. yes: the statement is true / the answer is yes\n" +
            "B. no: the statement is false / the answer is no\n\nAnswer with one letter: A, B.",
            branches.getValue("refund").suffix)
        val team = branches.getValue("team").suffix
        assertTrue(team.startsWith("Question: Which team should handle this?\n\nOptions:\n"))
        assertTrue(team.endsWith("\n\nAnswer with one letter: A, B, C."))
        assertTrue("billing: Charges, refunds, invoices" in team)
        assertTrue("technical: App or site faults" in team)
        assertTrue("fraud: Suspected unauthorised use" in team)
        assertTrue(branches.getValue("urgency").suffix.startsWith(
            "Rate the state: How urgent is this?\n\nOptions:\nA. level 0: Can wait"))
        assertEquals(setOf(2, 3), plan.optionCounts.toSet())
    }

    @Test
    fun slotLabelsAndScoreFormulasMatchOneJev() {
        val slots = decisionSlotLabels()
        assertEquals(256, slots.size)
        assertEquals(256, slots.distinct().size)
        assertEquals("AA", slots[26])
        assertTrue("BQ" !in slots)
        assertEquals(0.25, choiceConfidence(listOf(0.5, 0.25, 0.25)), 1e-9)
        assertEquals(0.0, choiceConfidence(listOf(0.25, 0.25, 0.25, 0.25)), 1e-9)
        assertEquals(1.5, expectedScore(listOf(0.0, 0.5, 0.5)), 1e-9)
        assertEquals(0.25, scoreConfidence(listOf(0.0, 0.5, 0.5)), 1e-9)
        assertEquals(0.625, scoreConfidence(listOf(0.0, 0.75, 0.25)), 1e-9)
        assertEquals(1.0, scoreConfidence(listOf(1.0)), 1e-9)
    }

    @Test
    fun choiceResultComputesProbabilityAndConfidence() {
        val question = DecisionQuestion("route", DecisionKind.Choice, "Choose", listOf(
            DecisionOption("billing", "charges"), DecisionOption("technical", "bugs"), DecisionOption("fraud", "risk")
        ))
        val result = parseDecisionResult(nativeResult(listOf(
            branch(doubleArrayOf(0.2, 0.5, 0.3))
        )), planDecisionRequest("Ticket", listOf(question))).answers.single()
        assertEquals(1, result.bestIndex)
        assertEquals(0.25, result.confidence!!, 1e-9)
        assertEquals("B", result.options[1].slot)
        assertEquals(0.5, result.options[1].probability, 1e-9)
        assertEquals(1.0, result.options.sumOf { it.probability }, 1e-9)
    }

    @Test
    fun noulAndScoreKeepTheirTypedSemantics() {
        val questions = listOf(
            DecisionQuestion("yes", DecisionKind.Noul, "Is it true?"),
            DecisionQuestion("score", DecisionKind.Score, "Rate", listOf(
                DecisionOption(description = "low"), DecisionOption(description = "middle"), DecisionOption(description = "high")
            ))
        )
        val answers = parseDecisionResult(nativeResult(listOf(
            branch(doubleArrayOf(0.8, 0.2)), branch(doubleArrayOf(0.1, 0.6, 0.3))
        )), planDecisionRequest("Evidence", questions)).answers
        assertEquals(0.8, answers[0].yesProbability!!, 1e-9)
        assertNull(answers[0].confidence)
        assertEquals(1.2, answers[1].score!!, 1e-9)
        assertEquals(0.4, answers[1].confidence!!, 1e-9)
        assertEquals(listOf("level 0", "level 1", "level 2"), answers[1].options.map { it.name })
    }

    @Test
    fun manyChoicesUseBalancedNoneChunksAndRestoreOriginalSlots() {
        val question = DecisionQuestion("large", DecisionKind.Choice, "Pick", (1..30).map {
            DecisionOption("option-$it", "description $it")
        })
        val plan = planDecisionRequest("Evidence", listOf(question), maxSlots = 10)
        assertEquals(4, plan.branches.size)
        assertEquals(listOf(9, 9, 9, 7), plan.optionCounts)
        assertTrue(plan.branches.all { it.optionIndices.last() == -1 })
        assertTrue(plan.suffixes.all { DECISION_NONE_OPTION in it })
        val probabilities = listOf(
            DoubleArray(9) { if (it == 8) 0.2 else 0.1 },
            DoubleArray(9) { if (it == 8) 0.6 else 0.05 },
            DoubleArray(9) { if (it == 8) 0.5 else 0.0625 },
            DoubleArray(7) { if (it == 6) 0.5 else 1.0 / 12 }
        )
        val result = parseDecisionResult(nativeResult(probabilities.map(::branch)), plan).answers.single()
        assertEquals(30, result.options.size)
        assertEquals(4, result.chunks)
        assertEquals(1.0, result.options.sumOf { it.probability }, 1e-9)
        assertEquals("A", result.options[8].slot)
        assertEquals("B", result.options[9].slot)
        assertEquals("A", result.options[16].slot)
        assertEquals("A", result.options[24].slot)
        assertEquals(0.05 / 2.2, result.options[10].probability, 1e-9)
    }

    @Test
    fun largeChoiceAndSmallTokenizerChunkingAreBounded() {
        val question = DecisionQuestion("large", DecisionKind.Choice, "Pick", (1..255).map {
            DecisionOption("option-$it", "description $it")
        })
        assertEquals(255, planDecisionRequest("state", listOf(question)).branches.single().optionIndices.size)
        val small = planDecisionRequest("state", listOf(question), maxSlots = 26)
        assertEquals(11, small.branches.size)
        assertEquals(255, small.branches.sumOf { branch -> branch.optionIndices.count { it >= 0 } })
        assertTrue(small.optionCounts.all { it <= 26 })
    }

    @Test
    fun threeHundredChoicesUseTwoFullSlotChunks() {
        val question = DecisionQuestion("large", DecisionKind.Choice, "Pick", (1..300).map {
            DecisionOption("option-$it", "description $it")
        })
        val plan = planDecisionRequest("state", listOf(question))
        assertEquals(2, plan.branches.size)
        assertEquals(listOf(151, 151), plan.optionCounts)
        assertEquals(300, plan.branches.sumOf { it.optionIndices.count { index -> index >= 0 } })
        assertTrue(plan.branches.all { it.optionIndices.last() == -1 })
    }

    @Test
    fun jsonStateAndInvalidInputsAreNotSilentlyTruncated() {
        val state = renderDecisionState("""{"ticket":{"id":7}}""", json = true)
        assertEquals(7, org.json.JSONObject(state).getJSONObject("ticket").getInt("id"))
        assertThrows(IllegalArgumentException::class.java) { renderDecisionState("false", json = true) }
        assertThrows(IllegalArgumentException::class.java) {
            DecisionQuestion("x", DecisionKind.Choice, options = listOf(
                DecisionOption("duplicate", "a"), DecisionOption("duplicate", "b")
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DecisionQuestion("x", DecisionKind.Score, options = List(11) { DecisionOption(description = "$it") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            planDecisionRequest("state", listOf(
                DecisionQuestion("same", DecisionKind.Noul), DecisionQuestion("same", DecisionKind.Noul)
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            renderDecisionState("x".repeat(131073), json = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            planDecisionRequest("state", listOf(DecisionQuestion("long", DecisionKind.Noul, "x".repeat(131072))))
        }
    }

    @Test
    fun invalidNativeProbabilitiesAreRejected() {
        val plan = planDecisionRequest("Evidence", listOf(DecisionQuestion("yes", DecisionKind.Noul)))
        val zeros = branch(doubleArrayOf(0.5, 0.5)).put("probabilities", JSONArray(listOf(0.0, 0.0)))
        assertThrows(IllegalArgumentException::class.java) {
            parseDecisionResult(nativeResult(listOf(zeros)), plan)
        }
        val nonNumeric = branch(doubleArrayOf(0.5, 0.5)).put("probabilities", JSONArray(listOf("NaN", 1.0)))
        assertThrows(IllegalArgumentException::class.java) {
            parseDecisionResult(nativeResult(listOf(nonNumeric)), plan)
        }
    }

    private fun branch(probabilities: DoubleArray): JSONObject {
        return JSONObject()
            .put("suffixTokens", 20).put("prefillUs", 100).put("allowedMass", 0.5)
            .put("logits", JSONArray(probabilities.map { kotlin.math.ln(it) }))
            .put("probabilities", JSONArray(probabilities.toList()))
    }

    private fun nativeResult(branches: List<JSONObject>): String = JSONObject()
        .put("prefixTokens", 58).put("prefixPrefillUs", 200)
        .put("branches", JSONArray().apply { branches.forEach(::put) }).toString()
}
