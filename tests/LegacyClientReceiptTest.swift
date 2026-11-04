import Foundation

// Reads an explicit legacy profile; all newly written metadata stays in a disposable directory.
@main
struct LegacyClientReceiptTest {
    static func main() throws {
        let args = CommandLine.arguments
        precondition(args.count == 3, "TEST.app EXISTING_PROFILE")
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent("mcgl-legacy-receipt-\(UUID().uuidString)")
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: root) }
        let resources = URL(fileURLWithPath: args[1]).appendingPathComponent("Contents/Resources")
        let profile = URL(fileURLWithPath: args[2])
        let jar = profile.appendingPathComponent("bin/mcgl.jar")
        let before = try MCGLInstaller.sha256File(jar)
        let installer = MCGLInstaller(supportRootURL: root, gameDirectoryURL: profile,
            portSupportURL: resources.appendingPathComponent("PortSupport"),
            patchToolsURL: resources.appendingPathComponent("PatchTools"),
            javaExecutableURL: resources.appendingPathComponent("java21-arm64/Home/bin/java"),
            jarExecutableURL: resources.appendingPathComponent("java21-arm64/Home/bin/jar"))
        precondition(!installer.canLaunchSavedClient())
        try installer.adoptPreviouslyPreparedClient()
        precondition(installer.canLaunchSavedClient())
        let after = try MCGLInstaller.sha256File(jar)
        precondition(before == after)
        print("LEGACY_CLIENT_RECEIPT_PASS linkage audit, legacy import, integrity check, existing client unchanged")
    }
}
