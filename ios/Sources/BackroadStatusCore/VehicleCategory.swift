import Foundation

/// Mirrors the server's VehicleCategory enum. Raw values match the Kotlin
/// enum's case name exactly so `rawValue` is the wire format the API
/// expects in `VehicleInput.category`.
public enum VehicleCategory: String, CaseIterable, Identifiable, Hashable, Sendable, Codable {
    case bicycle = "BICYCLE"
    case eBicycle = "E_BICYCLE"
    case dirtbike = "DIRTBIKE"
    case dualSport = "DUAL_SPORT"
    case atv = "ATV"
    case utvSxs = "UTV_SXS"
    case car = "CAR"
    case crossover = "CROSSOVER"
    case suv = "SUV"
    case van = "VAN"
    case truck = "TRUCK"
    case custom4x4 = "CUSTOM_4X4"
    case onFoot = "ON_FOOT"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .bicycle:    return "Bicycle"
        case .eBicycle:   return "E-Bicycle"
        case .dirtbike:   return "Dirtbike"
        case .dualSport:  return "Dual-Sport Motorcycle"
        case .atv:        return "ATV"
        case .utvSxs:     return "UTV / Side-by-Side"
        case .car:        return "Passenger Car"
        case .crossover:  return "Crossover"
        case .suv:        return "Jeep / SUV"
        case .van:        return "Van"
        case .truck:      return "Truck"
        case .custom4x4:  return "Custom 4WD"
        case .onFoot:     return "On-Foot"
        }
    }

    public var categoryDescription: String {
        switch self {
        case .bicycle:    return "Human-powered bicycle. Includes gravel, road, and mountain bikes."
        case .eBicycle:   return "Pedal-assist or throttle-driven electric bicycle, including e-MTBs."
        case .dirtbike:   return "Off-road motorcycle with knobby tires; typically not street legal."
        case .dualSport:  return "Dual-sport or adventure motorcycle for mixed on- and off-road travel."
        case .atv:        return "Four-wheel all-terrain vehicle (quad)."
        case .utvSxs:     return "Side-by-side utility vehicle with roll cage and bench seating."
        case .car:        return "Standard passenger car with low ground clearance."
        case .crossover:  return "Unibody vehicle with car-like handling and modest ground clearance."
        case .suv:        return "Body-on-frame or heavy unibody SUV, typically with 4WD."
        case .van:        return "Full-size or camper van; long wheelbase can limit clearance and turning."
        case .truck:      return "Pickup truck. Ground clearance and drivetrain vary by trim."
        case .custom4x4:  return "Modified 4WD with upgraded suspension, tires, or drivetrain for demanding terrain."
        case .onFoot:     return "Traveled on foot; hiking, walking, or running."
        }
    }

    /// Categories available in the composer's "Modes of Travel" dropdown.
    public static let userPickable: [VehicleCategory] = allCases
}
