package com.backroadstatus.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the server's VehicleCategory enum. The `wire` name matches the
 * server's enum case exactly and is the value sent in `VehicleInput.category`.
 */
@Serializable
enum class VehicleCategory(val wire: String) {
    @SerialName("BICYCLE") BICYCLE("BICYCLE"),
    @SerialName("E_BICYCLE") E_BICYCLE("E_BICYCLE"),
    @SerialName("DIRTBIKE") DIRTBIKE("DIRTBIKE"),
    @SerialName("DUAL_SPORT") DUAL_SPORT("DUAL_SPORT"),
    @SerialName("ATV") ATV("ATV"),
    @SerialName("UTV_SXS") UTV_SXS("UTV_SXS"),
    @SerialName("CAR") CAR("CAR"),
    @SerialName("CROSSOVER") CROSSOVER("CROSSOVER"),
    @SerialName("SUV") SUV("SUV"),
    @SerialName("VAN") VAN("VAN"),
    @SerialName("TRUCK") TRUCK("TRUCK"),
    @SerialName("CUSTOM_4X4") CUSTOM_4X4("CUSTOM_4X4"),
    @SerialName("ON_FOOT") ON_FOOT("ON_FOOT");

    val displayName: String
        get() = when (this) {
            BICYCLE -> "Bicycle"
            E_BICYCLE -> "E-Bicycle"
            DIRTBIKE -> "Dirtbike"
            DUAL_SPORT -> "Dual-Sport Motorcycle"
            ATV -> "ATV"
            UTV_SXS -> "UTV / Side-by-Side"
            CAR -> "Passenger Car"
            CROSSOVER -> "Crossover"
            SUV -> "Jeep / SUV"
            VAN -> "Van"
            TRUCK -> "Truck"
            CUSTOM_4X4 -> "Custom 4WD"
            ON_FOOT -> "On-Foot"
        }

    val categoryDescription: String
        get() = when (this) {
            BICYCLE -> "Human-powered bicycle. Includes gravel, road, and mountain bikes."
            E_BICYCLE -> "Pedal-assist or throttle-driven electric bicycle, including e-MTBs."
            DIRTBIKE -> "Off-road motorcycle with knobby tires; typically not street legal."
            DUAL_SPORT -> "Dual-sport or adventure motorcycle for mixed on- and off-road travel."
            ATV -> "Four-wheel all-terrain vehicle (quad)."
            UTV_SXS -> "Side-by-side utility vehicle with roll cage and bench seating."
            CAR -> "Standard passenger car with low ground clearance."
            CROSSOVER -> "Unibody vehicle with car-like handling and modest ground clearance."
            SUV -> "Body-on-frame or heavy unibody SUV, typically with 4WD."
            VAN -> "Full-size or camper van; long wheelbase can limit clearance and turning."
            TRUCK -> "Pickup truck. Ground clearance and drivetrain vary by trim."
            CUSTOM_4X4 -> "Modified 4WD with upgraded suspension, tires, or drivetrain for demanding terrain."
            ON_FOOT -> "Traveled on foot; hiking, walking, or running."
        }

    companion object {
        val userPickable: List<VehicleCategory> = entries.toList()
    }
}
