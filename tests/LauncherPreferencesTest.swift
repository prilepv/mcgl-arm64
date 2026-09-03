import Foundation

@main
struct LauncherPreferencesTest {
    static func main() {
        let suite = "MCGLLauncherPreferencesTest.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = MCGLLauncherPreferences(defaults: defaults)

        precondition(preferences.initialMemoryMB == 512)
        precondition(preferences.maximumMemoryMB == 2048)
        precondition(preferences.multicoreMemory)
        precondition(preferences.chunkVbo)
        precondition(!preferences.graphicsDiagnostics)

        preferences.maximumMemoryMB = 4096
        preferences.initialMemoryMB = 2048
        precondition(preferences.initialMemoryMB == 2048)
        precondition(preferences.maximumMemoryMB == 4096)
        preferences.initialMemoryMB = 8192
        precondition(preferences.initialMemoryMB == 4096)
        preferences.maximumMemoryMB = 1024
        precondition(preferences.initialMemoryMB == 1024)

        let oldSuite = "MCGLLauncherPreferencesMigrationTest.\(UUID().uuidString)"
        let oldDefaults = UserDefaults(suiteName: oldSuite)!
        defer { oldDefaults.removePersistentDomain(forName: oldSuite) }
        oldDefaults.set(4096, forKey: "MCGLMemoryLimitMB")
        oldDefaults.set(true, forKey: "MCGLPreallocateMemory")
        let migrated = MCGLLauncherPreferences(defaults: oldDefaults)
        precondition(migrated.initialMemoryMB == 4096)
        precondition(migrated.maximumMemoryMB == 4096)
        print("LAUNCHER_PREFERENCES_PASS independent Xms/Xmx, clamping and 1.6.4 migration")
    }
}
