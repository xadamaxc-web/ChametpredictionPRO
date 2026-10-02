package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Test
import com.chamet.guesser.PositionMapper.Item

class Session1Tests {

    private val W = 1080

    @Test fun poolsFollowScreenPositionNotSize() {
        // left=ATV (small pool), middle=Car (biggest), right=Stock Car (medium)
        val cars = listOf(Item("ATV", 180f), Item("Car", 540f), Item("Stock Car", 900f))
        val pools = listOf(Item(574_208L, 185f), Item(3_327_187L, 545f), Item(841_561L, 905f))
        val c = PositionMapper.map(cars, pools, W)
        assertEquals(listOf("ATV", "Car", "Stock Car"), c.cars)
        assertEquals(listOf(574_208L, 3_327_187L, 841_561L), c.pools)
    }

    @Test fun carOrderInOcrDoesNotMatter() {
        val cars = listOf(Item("Stock Car", 900f), Item("ATV", 180f), Item("Car", 540f))
        val pools = listOf(Item(841_561L, 905f), Item(574_208L, 185f), Item(3_327_187L, 545f))
        val c = PositionMapper.map(cars, pools, W)
        assertEquals(listOf("ATV", "Car", "Stock Car"), c.cars)
        assertEquals(574_208L, c.pools[0])
    }

    @Test fun missingMiddleCarKeepsPositions() {
        val cars = listOf(Item("ATV", 180f), Item("Stock Car", 900f))
        val pools = listOf(Item(100_000L, 180f), Item(200_000L, 900f))
        val c = PositionMapper.map(cars, pools, W)
        assertEquals(listOf("ATV", "—", "Stock Car"), c.cars)
        assertEquals(listOf(100_000L, 0L, 200_000L), c.pools)
    }

    @Test fun poolFarFromAnyCarIsIgnored() {
        val cars = listOf(Item("ATV", 180f))
        val pools = listOf(Item(999_999L, 1000f))
        assertEquals(0L, PositionMapper.map(cars, pools, W).pools[0])
    }

    @Test fun poolsByCarDropsEmptySlots() {
        val m = PositionMapper.poolsByCar(listOf("ATV", "—", "Car"), listOf(5L, 0L, 7L))
        assertEquals(mapOf("ATV" to 5L, "Car" to 7L), m)
    }

    @Test fun rankLabelFollowsRealRankNotSlotOne() {
        // ranked best->worst: ATV, Car, Stock Car. Only the 3rd-rank car has a good EV.
        val cars = listOf("ATV", "Car", "Stock Car")
        val pools = mapOf("ATV" to 3_000_000L, "Car" to 3_000_000L, "Stock Car" to 100_000L)
        val s = OddsEngine.compute(cars, pools, 80, 100_000L)
        assertEquals("Stock Car", s.car1)
        assertEquals(3, s.rank1)
        assertEquals("3rd", OddsEngine.ordinal(s.rank1))
        assertEquals(2, s.topEvIndex)
    }

    @Test fun findPoolsReadsInOrderOfAppearance() {
        assertEquals(listOf(12_345L, 1_234_567L), OddsEngine.findPools("12,345 / 1,234,567 / 99"))
    }
}
