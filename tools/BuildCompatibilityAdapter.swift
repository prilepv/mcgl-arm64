import CryptoKit
import Foundation

/// Publisher-side signing. The key and the assembled envelope stay outside the repository.
@main struct BuildCompatibilityAdapter {
    static func main() throws {
        let args = CommandLine.arguments
        guard args.count == 7, let revision = Int(args[3]), let minimumBuild = Int(args[4]), revision > 0, minimumBuild >= 264 else {
            fatalError("PRIVATE_KEY APP REVISION MINIMUM_BUILD TOOLS_ARCHIVE NEW_OUTPUT.adapter")
        }
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: Data(contentsOf: URL(fileURLWithPath: args[1])))
        let resources = URL(fileURLWithPath: args[2]).appendingPathComponent("Contents/Resources")
        let archive = try Data(contentsOf: URL(fileURLWithPath: args[5]))
        var runtimes: [String: String] = [:]
        for name in ["PortSupport/bin/lwjgl.jar", "Bgfx/lwjgl.jar"] {
            runtimes[name] = MCGLCompatibilityAdapter.hash(try Data(contentsOf: resources.appendingPathComponent(name)))
        }
        let manifest = MCGLCompatibilityAdapter.Manifest(schema: 1, abi: "core-17", installer: "installer-1",
            revision: revision, minimumBuild: minimumBuild, java: 21, runtimes: runtimes,
            archiveSHA256: MCGLCompatibilityAdapter.hash(archive))
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let json = try encoder.encode(manifest)
        var length = UInt32(json.count).bigEndian
        var payload = withUnsafeBytes(of: &length) { Data($0) }
        payload.append(json); payload.append(archive)
        let envelope = MCGLCompatibilityAdapter.magic + (try key.signature(for: payload)) + payload
        _ = try MCGLCompatibilityAdapter.decode(envelope, key: key.publicKey.rawRepresentation, build: minimumBuild, runtimes: runtimes)
        try envelope.write(to: URL(fileURLWithPath: args[6]), options: .withoutOverwriting)
        print("SIGNED_ADAPTER_CREATED revision=\(revision) bytes=\(envelope.count)")
    }
}
