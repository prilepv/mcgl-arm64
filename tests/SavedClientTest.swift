import Foundation

@main
struct SavedClientTest {
    static func main() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent("mcgl-saved-client-\(UUID().uuidString)")
        defer { try? fm.removeItem(at: root) }
        let game = root.appendingPathComponent("game"), port = root.appendingPathComponent("port")
        func write(_ url: URL, _ text: String) throws {
            try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(text.utf8).write(to: url)
        }
        try write(port.appendingPathComponent("bin/lwjgl.jar"), "fixture")
        let installer = MCGLInstaller(supportRootURL: root, gameDirectoryURL: game, portSupportURL: port,
            patchToolsURL: root, javaExecutableURL: root, jarExecutableURL: root)
        precondition(!installer.canLaunchSavedClient())
        for path in ["Minecraft.jar", "bin/mcgl.jar", "bin/lwjgl_util.jar", "bin/lwjgl.jar", "version.md5", ".arm64-port-version"] {
            try write(game.appendingPathComponent(path), "fixture")
        }
        try write(game.appendingPathComponent("update_f.lst"), "00000000000000000000000000000000 ./bin/mcgl.jar\n")
        try installer.recordSavedClient(); precondition(installer.canLaunchSavedClient())
        try write(game.appendingPathComponent("options.txt"), "user-choice:changed\n")
        precondition(installer.canLaunchSavedClient())
        try write(game.appendingPathComponent("bin/mcgl.jar"), "modified")
        precondition(!installer.canLaunchSavedClient())
        try write(game.appendingPathComponent("bin/mcgl.jar"), "fixture")
        precondition(installer.canLaunchSavedClient())
        try fm.removeItem(at: game.appendingPathComponent("bin/lwjgl.jar"))
        precondition(!installer.canLaunchSavedClient())
        try fm.createSymbolicLink(at: game.appendingPathComponent("bin/lwjgl.jar"), withDestinationURL: port.appendingPathComponent("bin/lwjgl.jar"))
        precondition(!installer.canLaunchSavedClient())
        try fm.removeItem(at: game.appendingPathComponent("bin/lwjgl.jar"))
        try write(game.appendingPathComponent("bin/lwjgl.jar"), "fixture")
        precondition(installer.canLaunchSavedClient())
        try write(game.appendingPathComponent("update_f.lst"), "00000000000000000000000000000000 ./bin/mcgl.jar\n00000000000000000000000000000000 ./bin/extra.jar\n")
        precondition(!installer.canLaunchSavedClient())
        print("SAVED_CLIENT_TEST_PASS fresh profile rejected, complete receipt accepted, settings preserved, changed/missing/external files and changed manifest rejected")
    }
}
