import CryptoKit
import Darwin
import Foundation

@main struct CompatibilityAdapterInstallTest {
    static func main() throws {
        setbuf(stdout, nil)
        let a = CommandLine.arguments
        guard a.count == 6 else { fatalError("APP OFFICIAL_CLIENT_DIR TRUSTED_REFERENCE_DIR SIGNED_ADAPTER NEW_OUTPUT_DIR") }
        let fm = FileManager.default
        let resources = URL(fileURLWithPath: a[1]).appendingPathComponent("Contents/Resources")
        let fixture = URL(fileURLWithPath: a[2])
        let reference = URL(fileURLWithPath: a[3])
        let signed = try Data(contentsOf: URL(fileURLWithPath: a[4]))
        let root = URL(fileURLWithPath: a[5])
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        var runtimes: [String: String] = [:]
        for path in ["PortSupport/bin/lwjgl.jar", "Bgfx/lwjgl.jar"] {
            runtimes[path] = try MCGLInstaller.sha256File(resources.appendingPathComponent(path))
        }
        let key = Curve25519.Signing.PrivateKey()
        let publisher = Data(stride(from: 0, to: MCGLCompatibilityPacks.publisherKey.count, by: 2).map { offset in
            let start = MCGLCompatibilityPacks.publisherKey.index(MCGLCompatibilityPacks.publisherKey.startIndex, offsetBy: offset)
            return UInt8(MCGLCompatibilityPacks.publisherKey[start..<MCGLCompatibilityPacks.publisherKey.index(start, offsetBy: 2)], radix: 16)!
        })
        let authentic = try MCGLCompatibilityAdapter.decode(signed, key: publisher, build: 264, runtimes: runtimes)
        func envelope(archive: Data, revision: Int) throws -> Data {
            let m = MCGLCompatibilityAdapter.Manifest(schema: 1, abi: "core-17", installer: "installer-1",
                revision: revision, minimumBuild: 264, java: 21, runtimes: runtimes,
                archiveSHA256: MCGLCompatibilityAdapter.hash(archive))
            let json = try JSONEncoder().encode(m)
            var length = UInt32(json.count).bigEndian
            var bytes = withUnsafeBytes(of: &length) { Data($0) }
            bytes.append(json); bytes.append(archive)
            return MCGLCompatibilityAdapter.magic + (try key.signature(for: bytes)) + bytes
        }
        let good = try envelope(archive: authentic.archive, revision: 1)
        let broken = try envelope(archive: Data("broken tools archive".utf8), revision: 2)
        let cache = root.appendingPathComponent("modules")
        var remote: Data? = nil
        let store = MCGLCompatibilityAdapters(root: cache, build: 264, runtimes: runtimes,
            publisherKey: key.publicKey.rawRepresentation, transport: { _ in
                guard let remote else { throw NSError(domain: "offline", code: 1) }
                return remote
            })
        let client = try MCGLInstaller.sha256File(fixture.appendingPathComponent("bin/mcgl.jar"))
        func run(_ name: String, expectSuccess: Bool) throws -> URL {
            let supportRoot = root.appendingPathComponent(name)
            let references = supportRoot.appendingPathComponent("Compatibility/core-17")
            try fm.createDirectory(at: references, withIntermediateDirectories: true)
            let refJar = reference.appendingPathComponent("mcgl.jar")
            let refSHA = try MCGLInstaller.sha256File(refJar)
            try fm.copyItem(at: refJar, to: references.appendingPathComponent("official-reference.jar"))
            try fm.copyItem(at: reference.appendingPathComponent(refSHA + ".pack"), to: references.appendingPathComponent(refSHA + ".pack"))
            let staged = supportRoot.appendingPathComponent("staged")
            try fm.copyItem(at: resources.appendingPathComponent("PortSupport"), to: staged)
            for path in ["Minecraft.jar", "bin/mcgl.jar", "bin/lwjgl_util.jar", "bin/gson-2.8.5.jar"] {
                let target = staged.appendingPathComponent(path)
                if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
                try fm.copyItem(at: fixture.appendingPathComponent(path), to: target)
            }
            let installed = supportRoot.appendingPathComponent("installed")
            try fm.createDirectory(at: installed, withIntermediateDirectories: true)
            let canary = installed.appendingPathComponent("options.txt")
            try Data("keep-settings".utf8).write(to: canary)
            let installer = MCGLInstaller(supportRootURL: supportRoot, gameDirectoryURL: installed,
                portSupportURL: resources.appendingPathComponent("PortSupport"),
                patchToolsURL: resources.appendingPathComponent("PatchTools"),
                javaExecutableURL: resources.appendingPathComponent("java21-arm64/Home/bin/java"),
                jarExecutableURL: resources.appendingPathComponent("java21-arm64/Home/bin/jar"),
                packTransport: { _ in throw NSError(domain: "missing-pack", code: 404) }, adapterStore: store)
            do {
                try installer.prepareStagedClient(in: staged) { print(name + ": " + $0) }
                precondition(expectSuccess)
            } catch {
                if expectSuccess { throw error }
                let untouched = try MCGLInstaller.sha256File(staged.appendingPathComponent("bin/mcgl.jar"))
                precondition(untouched == client)
            }
            let settings = try String(contentsOf: canary, encoding: .utf8)
            precondition(settings == "keep-settings")
            let remains = try fm.contentsOfDirectory(atPath: supportRoot.path)
            precondition(!remains.contains(where: { $0.hasPrefix(".adapter-attempt-") || $0.hasPrefix(".client-compatibility-") }))
            return staged
        }
        _ = try run("bundled-old", expectSuccess: false)
        remote = good
        let installed = try run("downloaded-fix", expectSuccess: true)
        let accepted = store.candidates(client: client, progress: { _ in }).first!
        try store.confirm(accepted)
        remote = broken
        let fallback = try run("failed-update", expectSuccess: true)
        let originalResult = try MCGLInstaller.sha256File(installed.appendingPathComponent("bin/mcgl.jar"))
        let rollbackResult = try MCGLInstaller.sha256File(fallback.appendingPathComponent("bin/mcgl.jar"))
        precondition(originalResult == rollbackResult)
        remote = nil
        let offline = try run("offline", expectSuccess: true)
        let offlineResult = try MCGLInstaller.sha256File(offline.appendingPathComponent("bin/mcgl.jar"))
        precondition(originalResult == offlineResult)
        print("ADAPTER_INSTALL_PASS old bundled tools rejected, downloaded tools fixed current MCGL, failed module rolled back, offline cache, unchanged installed settings")
    }
}
