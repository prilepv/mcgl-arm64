import Foundation

@main
struct ChangelogTest {
    static func main() {
        let entries = MCGLChangelog.entries
        precondition(entries.map(\.version) == ["1.7.0", "1.6.7", "1.6.6", "1.6.5", "1.6.4"])
        precondition(MCGLLauncherUpdater.currentVersion == entries.first!.version)
        for (index, entry) in entries.enumerated() {
            precondition(!entry.date.isEmpty && !entry.title.isEmpty)
            precondition((3...5).contains(entry.points.count))
            precondition(entry.points.allSatisfy { !$0.isEmpty && $0.count < 180 })
            precondition(entry.releaseURL.scheme == "https" && entry.releaseURL.host == "github.com")
            precondition(entry.releaseURL.path == "/prilepv/mcgl-arm64/releases/tag/v\(entry.version)")
            if index > 0 { precondition(MCGLLauncherUpdater.isNewer(entries[index-1].version, than: entry.version)) }
        }
        let suite = "MCGLChangelogTest.\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = MCGLLauncherPreferences(defaults: defaults)
        precondition(preferences.lastReadChangelogVersion == nil)
        preferences.maximumMemoryMB = 4096
        preferences.fpsLimit = 144
        preferences.lastReadChangelogVersion = "1.7.0"
        precondition(MCGLLauncherPreferences(defaults: defaults).lastReadChangelogVersion == "1.7.0")
        precondition(preferences.maximumMemoryMB == 4096 && preferences.fpsLimit == 144)
        print("CHANGELOG_PASS five concise public releases, descending versions, exact HTTPS links, offline data, independent read marker")
    }
}
