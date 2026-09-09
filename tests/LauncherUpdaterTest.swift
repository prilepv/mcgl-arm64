import Foundation

@main
struct LauncherUpdaterTest {
    static func main() throws {
        check(MCGLLauncherUpdater.isNewer("1.6.5", than: "1.6.4"))
        check(MCGLLauncherUpdater.isNewer("1.6.8", than: "1.6.7"))
        check(!MCGLLauncherUpdater.isNewer("1.6.7", than: "1.6.8"))
        check(MCGLLauncherUpdater.isNewer("1.6.9", than: "1.6.8"))
        check(!MCGLLauncherUpdater.isNewer("1.6.8", than: "1.6.9"))
        check(MCGLLauncherUpdater.isNewer("1.6.10", than: "1.6.9"))
        check(!MCGLLauncherUpdater.isNewer("1.6.9", than: "1.6.10"))
        check(MCGLLauncherUpdater.isNewer("1.7.0", than: "1.6.10"))
        check(MCGLLauncherUpdater.isNewer("1.6.11", than: "1.6.10"))
        check(!MCGLLauncherUpdater.isNewer("1.6.10", than: "1.6.11"))
        check(MCGLLauncherUpdater.isNewer("1.6.12", than: "1.6.11"))
        check(!MCGLLauncherUpdater.isNewer("1.6.11", than: "1.6.12"))
        check(MCGLLauncherUpdater.isNewer("1.6.13", than: "1.6.12"))
        check(!MCGLLauncherUpdater.isNewer("1.6.12", than: "1.6.13"))
        check(MCGLLauncherUpdater.isNewer("1.6.14", than: "1.6.13"))
        check(!MCGLLauncherUpdater.isNewer("1.6.13", than: "1.6.14"))
        check(MCGLLauncherUpdater.isNewer("1.6.15", than: "1.6.14"))
        check(!MCGLLauncherUpdater.isNewer("1.6.14", than: "1.6.15"))
        check(MCGLLauncherUpdater.isNewer("1.6.16", than: "1.6.15"))
        check(!MCGLLauncherUpdater.isNewer("1.6.15", than: "1.6.16"))
        check(MCGLLauncherUpdater.isNewer("1.6.17", than: "1.6.16"))
        check(MCGLLauncherUpdater.isNewer("1.7.0", than: "1.6.99"))
        check(MCGLLauncherUpdater.isNewer("v2.0.0", than: "1.99.99"))
        check(MCGLLauncherUpdater.isNewer("1.6.5.1", than: "1.6.5"))
        check(!MCGLLauncherUpdater.isNewer("1.6.4", than: "1.6.5"))
        check(!MCGLLauncherUpdater.isNewer("v1.6.5", than: "1.6.5"))
        check(!MCGLLauncherUpdater.isNewer("1.6.5-beta", than: "1.6.5"))

        let json = """
        {
          "tag_name": "v1.6.5",
          "name": "Minecraft Galaxy ARM64 1.6.5",
          "body": "Release notes",
          "html_url": "https://github.com/prilepv/mcgl-arm64/releases/tag/v1.6.5",
          "assets": [{
            "name": "Minecraft-Galaxy-ARM64-Bootstrap-1.6.5.dmg",
            "browser_download_url": "https://example.invalid/release.dmg",
            "digest": "sha256:0123"
          }]
        }
        """.data(using: .utf8)!
        let release = try JSONDecoder().decode(MCGLGitHubRelease.self, from: json)
        check(release.version == "1.6.5")
        check(release.diskImage?.name.hasSuffix(".dmg") == true)
        print("LAUNCHER_UPDATER_PASS semantic versions, GitHub release metadata, DMG selection")
    }

    private static func check(_ condition: @autoclosure () -> Bool) {
        if !condition() { fatalError("launcher updater check failed") }
    }
}
