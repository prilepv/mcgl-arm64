import Foundation

// Exercises the real staging pipeline, not network/authentication or the user's
// installation. The caller must provide a fresh, disposable output directory.
@main
struct InstallerCompatibilityTest {
    static func main() throws {
        let args = CommandLine.arguments
        guard args.count == 5 else { fatalError("TEST.app ORIGINAL_CLIENT_DIR UPDATED_MCGL.jar NEW_OUTPUT_DIR") }
        let fm = FileManager.default
        let resources = URL(fileURLWithPath: args[1]).appendingPathComponent("Contents/Resources")
        let original = URL(fileURLWithPath: args[2])
        let updated = URL(fileURLWithPath: args[3])
        let root = URL(fileURLWithPath: args[4], isDirectory: true)
        precondition(!fm.fileExists(atPath: root.path), "Test output already exists")
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        let installed = root.appendingPathComponent("installed-canary")
        try fm.createDirectory(at: installed, withIntermediateDirectories: true)
        let options = installed.appendingPathComponent("options.txt")
        let canary = Data("alphaSort:false\nlocal-test-canary:keep\n".utf8)
        try canary.write(to: options)
        func checkCanary() throws {
            let actual = try Data(contentsOf: options)
            precondition(actual == canary)
        }
        let installer = MCGLInstaller(
            supportRootURL: root, gameDirectoryURL: installed,
            portSupportURL: resources.appendingPathComponent("PortSupport"),
            patchToolsURL: resources.appendingPathComponent("PatchTools"),
            javaExecutableURL: resources.appendingPathComponent("java21-arm64/Home/bin/java"),
            jarExecutableURL: resources.appendingPathComponent("java21-arm64/Home/bin/jar"))
        func stage(_ name: String, _ jar: URL?) throws -> URL {
            let result = root.appendingPathComponent(name)
            try fm.copyItem(at: resources.appendingPathComponent("PortSupport"), to: result)
            try fm.copyItem(at: original.appendingPathComponent("Minecraft.jar"), to: result.appendingPathComponent("Minecraft.jar"))
            try fm.copyItem(at: original.appendingPathComponent("bin/lwjgl_util.jar"), to: result.appendingPathComponent("bin/lwjgl_util.jar"))
            if let jar { try fm.copyItem(at: jar, to: result.appendingPathComponent("bin/mcgl.jar")) }
            return result
        }
        for (name, jar) in [("updated", updated), ("original", original.appendingPathComponent("bin/mcgl.jar"))] {
            let staged = try stage(name, jar)
            try installer.prepareStagedClient(in: staged)
            precondition(fm.fileExists(atPath: staged.appendingPathComponent("bin/mcgl.jar").path))
            try checkCanary()
            let bin = try fm.contentsOfDirectory(atPath: staged.appendingPathComponent("bin").path)
            precondition(!bin.contains(where: { $0.contains(".optimized.") || $0.contains(".renderer.") || $0.contains(".game-core.") || $0.contains(".chunks.") || $0.contains(".lwjgl3.") }))
            print("INSTALLER_COMPATIBILITY_PASS profile=\(name) all stages, official names restored, installed profile unchanged")
        }
        let utility = try stage("utility-only", nil)
        try installer.prepareStagedClient(in: utility)
        let unknown = root.appendingPathComponent("unknown.jar")
        var bytes = try Data(contentsOf: updated); bytes.append(42); try bytes.write(to: unknown)
        let rejected = try stage("unsupported", unknown)
        do { try installer.prepareStagedClient(in: rejected); fatalError("Unknown official client accepted") }
        catch {
            precondition(error.localizedDescription.contains("пока не поддерживается"))
            precondition(error.localizedDescription.contains("не изменены"))
        }
        do { try installer.prepareStagedClient(in: installed); fatalError("Installed profile allowed as staging") }
        catch { precondition(error.localizedDescription.contains("временной папке")) }
        try checkCanary()
        let remains = try fm.contentsOfDirectory(atPath: root.path)
        precondition(!remains.contains(where: { $0.hasPrefix(".client-compatibility-") || $0.hasPrefix(".patching-") }))
        print("INSTALLER_COMPATIBILITY_PASS utility-only update, unsupported-client rejection, private scratch cleanup, canary unchanged")
    }
}
