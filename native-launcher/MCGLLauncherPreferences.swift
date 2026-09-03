import Foundation

/// Only the username is accepted here; passwords never enter preferences.
final class MCGLLauncherPreferences {
    static let fpsLimits = [0, 60, 120, 144, 165, 180, 240]
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var remembersLogin: Bool { defaults.bool(forKey: "MCGLRememberLogin") }

    var chunkVbo: Bool {
        get { defaults.bool(forKey: "MCGLChunkVBO") }
        set { defaults.set(newValue, forKey: "MCGLChunkVBO") }
    }

    var savedLogin: String {
        remembersLogin ? (defaults.string(forKey: "MCGLSavedLogin") ?? "") : ""
    }

    func rememberLogin(_ enabled: Bool, login: String) {
        defaults.set(enabled, forKey: "MCGLRememberLogin")
        updateLogin(login)
    }

    func updateLogin(_ login: String) {
        if remembersLogin {
            defaults.set(login.trimmingCharacters(in: .whitespacesAndNewlines),
                         forKey: "MCGLSavedLogin")
        } else {
            defaults.removeObject(forKey: "MCGLSavedLogin")
        }
    }

    var fpsLimit: Int {
        get {
            let value = defaults.integer(forKey: "MCGLFPSLimit")
            return Self.fpsLimits.contains(value) ? value : 0
        }
        set {
            defaults.set(Self.fpsLimits.contains(newValue) ? newValue : 0,
                         forKey: "MCGLFPSLimit")
        }
    }
}
