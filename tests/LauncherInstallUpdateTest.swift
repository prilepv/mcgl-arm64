import Cocoa

@main
struct LauncherInstallUpdateTest {
    static func version(of bundle: URL) throws -> String {
        let data = try Data(contentsOf: bundle.appendingPathComponent("Contents/Info.plist"))
        let plist = try PropertyListSerialization.propertyList(from: data, format: nil) as! [String: Any]
        return plist["CFBundleShortVersionString"] as! String
    }

    static func main() throws {
        precondition(CommandLine.arguments.count == 3, "Usage: install-test disposable-old.app fixture.dmg")
        let installed = URL(fileURLWithPath: CommandLine.arguments[1])
        let diskImage = URL(fileURLWithPath: CommandLine.arguments[2])
        let oldVersion = try version(of: installed)
        precondition(oldVersion == "1.7.5")

        let digest = "sha256:" + String(repeating: "0", count: 64)
        let assetName = "Minecraft-Galaxy-ARM64-Bootstrap-1.7.6.dmg"
        let origin = "https://github.com/prilepv/mcgl-arm64/releases/download/v1.7.6/"
        func release(url: String, digest: String?) -> MCGLGitHubRelease {
            MCGLGitHubRelease(tagName: "v1.7.6", title: "Fixture", notes: "",
                pageURL: URL(string: origin)!,
                assets: [.init(name: assetName, downloadURL: URL(string: url)!, digest: digest)])
        }
        precondition(release(url: origin + assetName, digest: digest).installableDiskImage != nil)
        precondition(release(url: origin + assetName, digest: nil).installableDiskImage == nil)
        precondition(release(url: "https://example.invalid/" + assetName, digest: digest).installableDiskImage == nil)

        do {
            let invalid = try MCGLPreparedLauncherUpdate.prepare(diskImage: diskImage, version: "1.7.7", installed: installed)
            invalid.discard()
            fatalError("Accepted an application whose version differs from the release")
        } catch {
            let untouchedVersion = try version(of: installed)
            precondition(untouchedVersion == oldVersion)
        }

        let failed = try MCGLPreparedLauncherUpdate.prepare(diskImage: diskImage, version: "1.7.6", installed: installed)
        try failed.install()
        let exchangedVersion = try version(of: installed)
        precondition(exchangedVersion == "1.7.6")
        try failed.finish(relaunched: false)
        let restoredVersion = try version(of: installed)
        precondition(restoredVersion == oldVersion)

        let ready = try MCGLPreparedLauncherUpdate.prepare(diskImage: diskImage, version: "1.7.6", installed: installed)
        try ready.install()
        let marker = installed.deletingLastPathComponent().appendingPathComponent("relaunch-\(UUID()).marker")
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.createsNewApplicationInstance = true
        configuration.activates = false
        configuration.environment = ["MCGL_UPDATE_FIXTURE_MARKER": marker.path]
        var relaunched: Bool?
        NSWorkspace.shared.openApplication(at: installed, configuration: configuration) { app, error in
            DispatchQueue.main.async { relaunched = error == nil && app?.isTerminated == false }
        }
        let deadline = Date().addingTimeInterval(8)
        while Date() < deadline, relaunched == nil || !FileManager.default.fileExists(atPath: marker.path) {
            RunLoop.current.run(until: Date().addingTimeInterval(0.02))
        }
        let started = relaunched == true && (try? String(contentsOf: marker, encoding: .utf8)) == "1.7.6"
        try ready.finish(relaunched: started)
        let finalVersion = try version(of: installed)
        precondition(started && finalVersion == "1.7.6", "New application did not launch from the original path")
        print("LAUNCHER_INSTALL_PASS verified release selection, rejected wrong version, atomic exchange, rollback and relaunch")
    }
}
