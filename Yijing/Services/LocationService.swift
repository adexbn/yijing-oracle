import Foundation
import CoreLocation

/// 获取东经用于真太阳时校正；无权限或定位失败时回退到东经 120°（中国标准时基准）。
/// CLLocationManager 委托默认在主线程回调，@Published 更新天然在主线程，无需额外隔离。
final class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var longitudeEast: Double = 120.0

    private let manager: CLLocationManager

    override init() {
        manager = CLLocationManager()
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
    }

    func request() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            manager.requestLocation()
        default:
            break
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            manager.requestLocation()
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let loc = locations.last, loc.coordinate.longitude != 0 {
            longitudeEast = loc.coordinate.longitude
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}