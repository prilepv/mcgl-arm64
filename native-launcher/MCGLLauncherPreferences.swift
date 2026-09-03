import Foundation

/// Only the username is accepted here; passwords never enter preferences.
final class MCGLLauncherPreferences {
    static let fpsLimits = [0, 60, 120, 144, 165, 180, 240]
    static let initialMemoryMBValues = [512, 1024, 2048, 4096, 6144, 8192]
    static let maximumMemoryMBValues = [1024, 2048, 4096, 6144, 8192]
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var remembersLogin: Bool { defaults.bool(forKey: "MCGLRememberLogin") }

    var chunkVbo: Bool {
        get { (defaults.object(forKey: "MCGLChunkVBO") as? Bool) ?? true }
        set { defaults.set(newValue, forKey: "MCGLChunkVBO") }
    }

    var initialMemoryMB: Int {
        get {
            if let saved = defaults.object(forKey: "MCGLInitialMemoryMB") as? Int,
               Self.initialMemoryMBValues.contains(saved) {
                return min(saved, maximumMemoryMB)
            }
            let oldPreallocation = defaults.object(forKey: "MCGLPreallocateMemory") as? Bool
            return oldPreallocation == true ? maximumMemoryMB : 512
        }
        set {
            let accepted = Self.initialMemoryMBValues.contains(newValue) ? newValue : 512
            defaults.set(min(accepted, maximumMemoryMB), forKey: "MCGLInitialMemoryMB")
        }
    }

    var maximumMemoryMB: Int {
        get {
            let saved = defaults.integer(forKey: "MCGLMemoryLimitMB")
            return Self.maximumMemoryMBValues.contains(saved) ? saved : 2048
        }
        set {
            let accepted = Self.maximumMemoryMBValues.contains(newValue) ? newValue : 2048
            defaults.set(accepted, forKey: "MCGLMemoryLimitMB")
            if let initial = defaults.object(forKey: "MCGLInitialMemoryMB") as? Int,
               initial > accepted {
                defaults.set(accepted, forKey: "MCGLInitialMemoryMB")
            }
        }
    }

    var multicoreMemory: Bool {
        get { (defaults.object(forKey: "MCGLMulticoreMemoryProfile") as? Bool) ?? true }
        set { defaults.set(newValue, forKey: "MCGLMulticoreMemoryProfile") }
    }

    var graphicsDiagnostics: Bool {
        get { (defaults.object(forKey: "MCGLGraphicsDiagnostics") as? Bool) ?? false }
        set { defaults.set(newValue, forKey: "MCGLGraphicsDiagnostics") }
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
