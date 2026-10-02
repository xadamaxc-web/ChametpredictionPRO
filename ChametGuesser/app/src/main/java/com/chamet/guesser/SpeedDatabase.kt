package com.chamet.guesser

/**
 * Speed / road-family facade. Values come from [EngineParams.active]
 * (defaults = params.json / user speed table). Session 8 can swap params.
 */
object SpeedDatabase {

    val ROADS: List<String> get() = EngineParams.active.roads

    val CAR_SPEEDS: Map<String, Map<String, Int>> get() = EngineParams.active.speeds

    val ROAD_FAMILIES: Map<String, List<String>> get() = EngineParams.active.roadFamilies

    fun speedOf(car: String, road: String): Int = EngineParams.active.speedOf(car, road)

    fun reloadFrom(params: EngineParams) {
        // active is already set by EngineParams.use; this is a no-op hook for tests
        EngineParams.active // touch
    }

    val paramsVersion: String get() = EngineParams.active.version
}
