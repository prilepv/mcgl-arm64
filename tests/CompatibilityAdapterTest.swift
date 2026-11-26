import CryptoKit
import Foundation

@main struct CompatibilityAdapterTest {
    static func main() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent("mcgl-adapter-test-\(UUID().uuidString)")
        defer { try? fm.removeItem(at: root) }
        let key = Curve25519.Signing.PrivateKey()
        let runtimes = ["PortSupport/bin/lwjgl.jar": String(repeating: "a", count: 64),
                        "Bgfx/lwjgl.jar": String(repeating: "b", count: 64)]
        let client = String(repeating: "c", count: 64)
        func envelope(_ revision: Int, minimumBuild: Int = 264, runtime: [String: String]? = nil) throws -> Data {
            let archive = Data("tools-\(revision)".utf8)
            let manifest = MCGLCompatibilityAdapter.Manifest(schema: 1, abi: "core-17", installer: "installer-1",
                revision: revision, minimumBuild: minimumBuild, java: 21, runtimes: runtime ?? runtimes,
                archiveSHA256: MCGLCompatibilityAdapter.hash(archive))
            let json = try JSONEncoder().encode(manifest)
            var length = UInt32(json.count).bigEndian
            var payload = withUnsafeBytes(of: &length) { Data($0) }
            payload.append(json)
            payload.append(archive)
            return MCGLCompatibilityAdapter.magic + (try key.signature(for: payload)) + payload
        }
        func rejects(_ action: () throws -> Void) {
            do { try action(); fatalError("Invalid adapter accepted") } catch { }
        }
        func decode(_ bytes: Data, build: Int = 264) throws -> MCGLCompatibilityAdapter {
            try MCGLCompatibilityAdapter.decode(bytes, key: key.publicKey.rawRepresentation, build: build, runtimes: runtimes)
        }
        let first = try envelope(1)
        let second = try envelope(2)
        let store = MCGLCompatibilityAdapters(root: root, build: 264, runtimes: runtimes,
            publisherKey: key.publicKey.rawRepresentation, transport: { _ in first })
        let one = store.candidates(client: client, progress: { _ in }).first!
        try store.confirm(one)
        let update = MCGLCompatibilityAdapters(root: root, build: 264, runtimes: runtimes,
            publisherKey: key.publicKey.rawRepresentation, transport: { _ in second })
        let two = update.candidates(client: client, progress: { _ in }).first!
        precondition(two.manifest.revision == 2)
        try update.reject(two, client: client)
        let fallback = update.candidates(client: client, progress: { _ in })
        precondition(fallback.map(\.identity) == [one.identity])
        let otherClient = String(repeating: "d", count: 64)
        precondition(update.candidates(client: otherClient, progress: { _ in }).first?.identity == two.identity)
        try update.confirm(two)
        let offline = MCGLCompatibilityAdapters(root: root, build: 264, runtimes: runtimes,
            publisherKey: key.publicKey.rawRepresentation, transport: { _ in throw NSError(domain: "offline", code: 1) })
        let cached = offline.candidates(client: client, progress: { _ in })
        precondition(cached.map(\.identity) == [two.identity, one.identity])
        let replay = store.candidates(client: client, progress: { _ in })
        precondition(replay.first?.identity == two.identity)
        try offline.confirm(one)
        precondition(offline.candidates(client: client, progress: { _ in }).first?.identity == one.identity)
        // The failed update remains suppressed for this client across a restart.
        try offline.reject(two, client: client)
        let rollback = update.candidates(client: client, progress: { _ in })
        precondition(rollback.first?.identity == one.identity)
        var tampered = second
        tampered[tampered.count - 1] ^= 1
        rejects { _ = try decode(tampered) }
        rejects { _ = try decode(Data(second.prefix(75))) }
        rejects { _ = try decode(envelope(3, minimumBuild: 999)) }
        rejects { _ = try decode(envelope(3, runtime: [:])) }
        rejects { _ = try MCGLCompatibilityAdapter.decode(first, key: Curve25519.Signing.PrivateKey().publicKey.rawRepresentation,
            build: 264, runtimes: runtimes) }
        rejects { _ = try decode(Data(repeating: 0, count: MCGLCompatibilityAdapter.limit + 1)) }
        try Data("damaged cache".utf8).write(to: root.appendingPathComponent(one.identity + ".adapter"))
        precondition(offline.candidates(client: client, progress: { _ in }).isEmpty)
        print("ADAPTER_DELIVERY_PASS signature, runtime/build binding, offline cache, rollback, replay, rejection persistence, corrupt cache")
    }
}
