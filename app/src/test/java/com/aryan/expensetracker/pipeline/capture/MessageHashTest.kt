package com.aryan.expensetracker.pipeline.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private const val SHA_256_HEX_LENGTH = 64

class MessageHashTest {

    @Test
    fun `same input gives the same hash`() {
        val first = hashMessage("VM-HDFCBK", "Rs.450 debited from A/c XX1234")
        val second = hashMessage("VM-HDFCBK", "Rs.450 debited from A/c XX1234")
        assertEquals(first, second)
        assertEquals(SHA_256_HEX_LENGTH, first.length)
    }

    @Test
    fun `different sender gives a different hash`() {
        val first = hashMessage("VM-HDFCBK", "Rs.450 debited from A/c XX1234")
        val second = hashMessage("AD-HDFCBK", "Rs.450 debited from A/c XX1234")
        assertNotEquals(first, second)
    }

    @Test
    fun `different body gives a different hash`() {
        val first = hashMessage("VM-HDFCBK", "Rs.450 debited from A/c XX1234")
        val second = hashMessage("VM-HDFCBK", "Rs.451 debited from A/c XX1234")
        assertNotEquals(first, second)
    }
}
