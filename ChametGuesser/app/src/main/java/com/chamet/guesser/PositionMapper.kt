package com.chamet.guesser

import kotlin.math.abs

/**
 * Maps OCR'd car names and pool numbers onto the 3 screen columns.
 * Position 1 = left, 2 = middle, 3 = right (same convention as the AI prompt and
 * the position-bias multipliers). Pools are paired to cars by horizontal distance,
 * never by pool size.
 */
object PositionMapper {

    data class Item<T>(val value: T, val centerX: Float)

    /** Always 3 slots (position 1..3). Empty slot = car "—", pool 0. */
    data class Columns(val cars: List<String>, val pools: List<Long>) {
        val detectedCars: List<String> get() = cars.filter { it != EMPTY }
    }

    const val EMPTY = "—"

    fun map(cars: List<Item<String>>, pools: List<Item<Long>>, screenWidth: Int): Columns {
        val outCars = MutableList(3) { EMPTY }
        val outPools = MutableList(3) { 0L }

        // distinct cars, left to right
        val seen = HashSet<String>()
        val sorted = cars.sortedBy { it.centerX }.filter { seen.add(it.value) }.take(3)
        if (sorted.isEmpty()) return Columns(outCars, outPools)

        // position = screen third; if two cars share a third, fall back to left-to-right order
        val byThird = sorted.map { thirdOf(it.centerX, screenWidth) }
        val positions: List<Int> =
            if (byThird.toSet().size == sorted.size) byThird else sorted.indices.toList()

        // pair each car with the nearest unused pool column (closest x first)
        data class Pair3(val ci: Int, val pi: Int, val dist: Float)
        val candidates = ArrayList<Pair3>()
        for (ci in sorted.indices) for (pi in pools.indices) {
            candidates.add(Pair3(ci, pi, abs(sorted[ci].centerX - pools[pi].centerX)))
        }
        val usedCar = HashSet<Int>()
        val usedPool = HashSet<Int>()
        val maxDist = screenWidth / 3f // a pool further than one column away is not this car's
        for (c in candidates.sortedBy { it.dist }) {
            if (c.dist > maxDist) break
            if (c.ci in usedCar || c.pi in usedPool) continue
            usedCar.add(c.ci); usedPool.add(c.pi)
            outPools[positions[c.ci]] = pools[c.pi].value
        }
        for (ci in sorted.indices) outCars[positions[ci]] = sorted[ci].value
        return Columns(outCars, outPools)
    }

    fun thirdOf(centerX: Float, screenWidth: Int): Int {
        if (screenWidth <= 0) return 0
        return ((centerX / screenWidth) * 3).toInt().coerceIn(0, 2)
    }

    /** Position-ordered pools -> car name -> pool. Empty slots and zero pools are dropped. */
    fun poolsByCar(positionCars: List<String>, pools: List<Long>): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        positionCars.forEachIndexed { i, car ->
            val p = pools.getOrElse(i) { 0L }
            if (car != EMPTY && p > 0) m[car] = p
        }
        return m
    }
}
