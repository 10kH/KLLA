package com.woolab.lumella.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerSlipsTest {

    /** The shared table (aaai27 learner_slips.py --self-test reads the same file). */
    @Test
    fun everyCaseInTheSharedTableFindsExactlyItsSlips() {
        val cases = javaClass.getResource("/learner-slips-cases.tsv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { (it.split('\t') + "").let { f -> f[0] to f[1] } }
        assertTrue("the table has both kinds of case", cases.count { it.second.isEmpty() } >= 10 && cases.count { it.second.isNotEmpty() } >= 10)
        val wrong = cases.mapNotNull { (sentence, expected) ->
            val got = LearnerSlips.find(sentence).joinToString(",") { "${it.said}>${it.fixed}" }
            if (got == expected) null else "$sentence: expected '$expected', got '$got'"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun anIrregularBSlipCarriesTheFormsARecastMaySayAndTheSlipItself() {
        val slip = LearnerSlips.find("떡볶이가 너무 맵었어요").single()

        assertEquals(listOf("매웠", "매워"), slip.right)
        assertEquals("맵었", slip.wrong)
        assertEquals("맵다 drops its ㅂ: 매워요, 매웠어요", slip.why)
    }

    @Test
    fun theTwoWaStemsTakeWaInBothTenses() {
        assertEquals(listOf("도왔", "도와"), LearnerSlips.find("친구를 돕어요").single().right)
        assertEquals("도와요", LearnerSlips.find("친구를 돕어요").single().fixed)
    }

    @Test
    fun aPlaceSlipNamesTheWordWithItsEnding() {
        val slip = LearnerSlips.find("시장에 떡볶이를 먹었어요").single()

        assertEquals(listOf("시장에서"), slip.right)
        assertEquals("a place slip is only seen with its predicate, so no early 'wrong'", null, slip.wrong)
    }

    @Test
    fun aLongerPlaceIsSaidRightWithItsListedPlaceToo() {
        val slip = LearnerSlips.find("광장시장에 빈대떡을 먹었어요").single()

        assertEquals("광장시장에서", slip.fixed)
        assertEquals(listOf("광장시장에서", "시장에서"), slip.right)
    }

    @Test
    fun everyIrregularStemEndsInBieup() {
        val notB = LearnerSlips.IRREGULAR_B.filter { s -> (s.last().code - 0xAC00).let { it < 0 || it % 28 != 17 } }
        assertEquals(emptyList<String>(), notB)
    }

    @Test
    fun noTranscriptNoSlips() {
        assertEquals(emptyList<LearnerSlip>(), LearnerSlips.find(""))
        assertEquals(emptyList<LearnerSlip>(), LearnerSlips.find("   "))
        assertEquals(emptyList<LearnerSlip>(), LearnerSlips.find("네"))
    }
}
