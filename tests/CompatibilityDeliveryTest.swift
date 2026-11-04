import Foundation

@main
struct CompatibilityDeliveryTest {
    static func check(_ value: Bool) { precondition(value) }
    static func main() throws {
        let fm = FileManager.default
        let pack = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
        let client = CommandLine.arguments[2]
        let root = fm.temporaryDirectory.appendingPathComponent("mcgl-delivery-test-\(UUID().uuidString)")
        defer { try? fm.removeItem(at: root) }
        var calls = 0, validations = 0
        let delivery = MCGLCompatibilityPacks(cache: root) { url in
            precondition(url.lastPathComponent == client + ".pack")
            calls += 1; return pack
        }
        func verify(_ url: URL) throws {
            check(try Data(contentsOf: url) == pack); validations += 1
        }
        let target = try delivery.resolve(client: client, verifyPayload: verify)
        precondition(calls == 1 && validations == 1)
        let offline = MCGLCompatibilityPacks(cache: root) { _ in
            throw MCGLCompatibilityPacks.failure("offline")
        }
        _ = try offline.resolve(client: client, verifyPayload: verify)
        precondition(calls == 1 && validations == 2)
        func rejects(_ action: () throws -> Void) {
            do { try action(); fatalError("Invalid delivery accepted") } catch { }
        }
        rejects { _ = try delivery.resolve(client: "../../invalid", verifyPayload: verify) }
        var damaged = pack; damaged[72] ^= 1
        try damaged.write(to: target)
        rejects { _ = try offline.resolve(client: client, verifyPayload: verify) }
        _ = try delivery.resolve(client: client, verifyPayload: verify)
        let restored = try Data(contentsOf: target)
        check(calls == 2 && restored == pack)
        let invalid = MCGLCompatibilityPacks(cache: root) { _ in damaged }
        rejects { _ = try invalid.resolve(client: client) { _ in throw MCGLCompatibilityPacks.failure("wrong ABI") } }
        check(try Data(contentsOf: target) == pack)
        let badPayload = MCGLCompatibilityPacks(cache: root) { _ in pack }
        rejects { _ = try badPayload.resolve(client: client) { _ in throw MCGLCompatibilityPacks.failure("wrong client") } }
        check(try Data(contentsOf: target) == pack)
        let remains = try fm.contentsOfDirectory(atPath: root.path)
        precondition(remains == [client + ".pack"])
        print("COMPATIBILITY_DELIVERY_PASS signed download, offline cache, corrupt cache recovery, invalid signature/payload rejection, cache preserved, scratch cleanup")
    }
}
