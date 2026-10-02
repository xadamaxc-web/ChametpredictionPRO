package com.chamet.guesser

/** Vehicle and road images (res/drawable-nodpi). Keys match SpeedDatabase / CarMatcher names. */
object Assets {
    val CAR_RES: Map<String, Int> = mapOf(
        "Monster Truck" to R.drawable.veh_01_monster_truck,
        "ORV" to R.drawable.veh_02_orv,
        "SUV" to R.drawable.veh_03_suv,
        "Car" to R.drawable.veh_04_car,
        "Motorcycle" to R.drawable.veh_05_motor_cycle,
        "Stock Car" to R.drawable.veh_06_stock_car,
        "ATV" to R.drawable.veh_07_atv,
        "Sports Car" to R.drawable.veh_08_sport_car,
        "Supercar" to R.drawable.veh_09_super_car
    )

    val ROAD_RES: Map<String, Int> = mapOf(
        "Highway" to R.drawable.road_highway,
        "Expressway" to R.drawable.road_express,
        "Dirt" to R.drawable.road_dirt,
        "Bumpy" to R.drawable.road_bumpy,
        "Potholes" to R.drawable.road_potholes,
        "Desert" to R.drawable.road_desert
    )
}
