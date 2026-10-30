package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DecodeListTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aBadItemCostsOnlyThatItem() {
        val decoded = decodeItems(3) { i -> if (i == 1) error("damaged") else "rule$i" }
        assertEquals(listOf("rule0", "rule2"), decoded.items)
        assertEquals(1, decoded.bad)
    }

    @Test fun aCleanListHasNothingBad() {
        assertEquals(0, decodeItems(2) { it }.bad)
    }

    @Test fun theRawDataIsCopiedOnceAndKept() {
        val dir = tmp.newFolder("unreadable")
        assertNotNull(UnreadableCopies.keep(dir, "app_rules", "[broken"))
        assertNull(UnreadableCopies.keep(dir, "app_rules", "[broken"))
        assertEquals("[broken", dir.listFiles()!!.single().readText())
    }

    @Test fun onlyTheNewestCopiesStay() {
        val names = (1..12).map { "c$it" }
        assertEquals(listOf("c1", "c2"), UnreadableCopies.excess(names))
    }
}
