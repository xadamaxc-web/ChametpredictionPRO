package com.chamet.guesser

/**
 * Exact finish-time model when the full layout is known (Session 6+).
 * T = Σ (lengthPx ÷ speed(vehicle, road)). Lowest T wins.
 *
 * Session 7 will add hidden-layout simulation; this module stays pure and
 * deterministic so the same tests run on-device and on the server later.
 */
object FinishTimeModel {

    data class CarTime(
        val car: String,
        val time: Double,
        val rank: Int
    )

    data class Result(
        val times: List<CarTime>,          // ascending by time
        val modelWinner: String?,
        /** JSON-ish map "Car:12.3,SUV:14.1" for DB storage */
        val modelTimesEncoded: String
    )

    /**
     * @param cars vehicle names
     * @param segments ordered (roadType, lengthPx) covering the full track
     */
    fun compute(
        cars: List<String>,
        segments: List<Pair<String, Double>>
    ): Result? {
        val usable = cars.filter { it.isNotBlank() && it != "—" }
        if (usable.isEmpty() || segments.isEmpty()) return null
        if (segments.any { it.second <= 0 }) return null

        val scored = usable.map { car ->
            var t = 0.0
            for ((road, px) in segments) {
                val sp = EngineParams.active.speedOf(car, road).toDouble()
                if (sp <= 0) return null
                t += px / sp
            }
            car to t
        }.sortedBy { it.second }

        val times = scored.mapIndexed { i, (car, t) ->
            CarTime(car, t, i + 1)
        }
        return Result(
            times = times,
            modelWinner = times.firstOrNull()?.car,
            modelTimesEncoded = encodeTimes(times)
        )
    }

    fun encodeTimes(times: List<CarTime>): String =
        times.joinToString(",") { "${it.car}:${"%.3f".format(it.time)}" }

    fun decodeTimes(s: String?): Map<String, Double> {
        if (s.isNullOrBlank()) return emptyMap()
        return s.split(",")
            .mapNotNull { part ->
                val idx = part.lastIndexOf(':')
                if (idx <= 0) return@mapNotNull null
                val name = part.substring(0, idx)
                val t = part.substring(idx + 1).toDoubleOrNull() ?: return@mapNotNull null
                name to t
            }.toMap()
    }

    /** True when model winner matches the observed winner. */
    fun modelHit(modelWinner: String?, realWinner: String?): Boolean =
        !modelWinner.isNullOrBlank() && !realWinner.isNullOrBlank() &&
            modelWinner.equals(realWinner, ignoreCase = true)
}
