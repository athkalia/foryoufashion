package allAutomatedChecks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PriceAdjustmentTest {

    @Test
    fun `test price below 70 ending in 9 with non-zero pennies`() {
        // Test case: 19.30 should become 19.99
        assertEquals("18.99", adjustPrice(19.30))
    }

    @Test
    fun `test price below 70 ending in 9 with zero pennies`() {
        // Test case: 19.00 should become 19.99
        assertEquals("18.99", adjustPrice(19.00))
    }

    @Test
    fun `test price below 70 ending in non-9 with zero pennies`() {
        // Test case: 18.00 should become 17.99 (subtract 1 cent)
        assertEquals("17.99", adjustPrice(18.00))
    }

    @Test
    fun `test price below 70 ending in non-9 with random pennies`() {
        // Test case: 18.21 should become 18.99
        assertEquals("17.99", adjustPrice(18.21))
    }

    @Test
    fun `test price below 70 with exactly 70`() {
        // Test case: 70.00 should remain 70.00
        assertEquals("70", adjustPrice(70.00))
    }

    @Test
    fun `test price above 70 without pennies`() {
        // Test case: 85.00 should remain 85.00
        assertEquals("85", adjustPrice(85.00))
    }

    @Test
    fun `test price above 70 with random pennies`() {
        // Test case: 177.21 should become 178.00 (round up to next major digit)
        assertEquals("177", adjustPrice(177.21))
    }

    @Test
    fun `test price above 70 ending in 9 with random pennies`() {
        // Test case: 189.21 should become 189.00 (strip pennies)
        assertEquals("189", adjustPrice(189.21))
    }

    @Test
    fun `test price above 70 ending in 9 without pennies`() {
        // Test case: 189.00 should remain 189.00
        assertEquals("189", adjustPrice(189.00))
    }

    @Test
    fun `test edge case with price just below 70`() {
        // Test case: 69.99 should remain 69.99
        assertEquals("69.99", adjustPrice(69.99))
    }

    @Test
    fun `test edge case with price just above 70`() {
        // Test case: 70.01 should remain 70.01
        assertEquals("70", adjustPrice(70.01))
    }

    @Test
    fun `test price well above 70 without pennies`() {
        // Test case: 200.00 should remain 200.00
        assertEquals("200", adjustPrice(200.00))
    }

    @Test
    fun `test price well above 70 with random pennies`() {
        // Test case: 200.55 should become 201.00 (round up to next major digit)
        assertEquals("200", adjustPrice(200.55))
    }
}
