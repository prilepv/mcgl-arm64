import Foundation
import Darwin

@main
struct PasswordStoreTest {
    static func main() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent("MCGLPasswordTest-\(UUID())", isDirectory: true)
        try fm.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? fm.removeItem(at: root) }
        let directory = root.appendingPathComponent("Credentials", isDirectory: true)
        let vault = MCGLPasswordStore(directory: directory)
        let first = MCGLAccount(id: UUID(), nickname: "DemoMiner", label: "", info: nil)
        let second = MCGLAccount(id: UUID(), nickname: "DemoBuilder", label: "", info: nil)
        func fails(_ operation: () throws -> Void) {
            do { try operation(); preconditionFailure("Unsafe vault operation was accepted") }
            catch { precondition(error is MCGLPasswordStore.Failure) }
        }
        func record(_ account: MCGLAccount) -> URL {
            directory.appendingPathComponent(account.id.uuidString.lowercased() + ".sealed")
        }
        let secret = "synthetic-only-НЕ-пароль-🔮-quotes\"\\ spaces "
        precondition(try! vault.password(for: first) == nil)
        try vault.remove(for: first)
        for bad in ["", "a\nb", "a\rb", "a\0b", String(repeating: "a", count: 4097)] {
            fails { try vault.save(bad, for: first) }
        }
        precondition(!fm.fileExists(atPath: directory.path), "Reading or opt-out must not create a vault")
        try vault.save(secret, for: first)
        let initialCiphertext = try Data(contentsOf: record(first))
        precondition(initialCiphertext.range(of: Data(secret.utf8)) == nil)
        precondition(try! MCGLPasswordStore(directory: directory).password(for: first) == secret)
        try vault.save(secret, for: first)
        precondition(try! Data(contentsOf: record(first)) != initialCiphertext, "AES-GCM nonce must be fresh")
        try vault.save("synthetic-second", for: second)
        for url in [record(first), record(second), directory.appendingPathComponent("local-key")] {
            precondition((try! fm.attributesOfItem(atPath: url.path)[.posixPermissions] as! NSNumber).intValue == 0o600)
        }
        precondition((try! fm.attributesOfItem(atPath: directory.path)[.posixPermissions] as! NSNumber).intValue == 0o700)
        let alias = MCGLAccount(id: first.id, nickname: "demominer", label: "New label", info: nil)
        precondition(try! vault.password(for: alias) == secret)
        let wrongNick = MCGLAccount(id: first.id, nickname: "AnotherPlayer", label: "", info: nil)
        fails { _ = try vault.password(for: wrongNick) }
        let firstData = try Data(contentsOf: record(first))
        let secondData = try Data(contentsOf: record(second))
        try firstData.write(to: record(second))
        fails { _ = try vault.password(for: second) }
        try secondData.write(to: record(second))
        var corrupted = firstData
        corrupted[corrupted.count - 1] ^= 1
        try corrupted.write(to: record(first))
        fails { _ = try vault.password(for: first) }
        precondition(try! Data(contentsOf: record(first)) == corrupted, "Read failure must preserve ciphertext")
        try firstData.write(to: record(first))

        let key = directory.appendingPathComponent("local-key")
        let backup = root.appendingPathComponent("test-key-backup")
        try fm.moveItem(at: key, to: backup)
        fails { _ = try vault.password(for: first) }
        fails { try vault.save("replacement", for: first) }
        precondition(!fm.fileExists(atPath: key.path), "A lost key must not be silently regenerated")
        precondition(try! Data(contentsOf: record(first)) == firstData)
        try fm.moveItem(at: backup, to: key)
        try fm.setAttributes([.posixPermissions: 0o644], ofItemAtPath: record(first).path)
        fails { _ = try vault.password(for: first) }
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: record(first).path)
        let hardLink = root.appendingPathComponent("test-hard-link")
        try fm.linkItem(at: record(first), to: hardLink)
        fails { _ = try vault.password(for: first) }
        try fm.removeItem(at: hardLink)
        try vault.remove(for: first)
        precondition(try! vault.password(for: first) == nil)
        precondition(try! vault.password(for: second) == "synthetic-second")
        try fm.createSymbolicLink(at: record(first), withDestinationURL: record(second))
        fails { _ = try vault.password(for: first) }
        fails { try vault.save("replacement", for: first) }
        fails { try vault.remove(for: first) }
        precondition(try! Data(contentsOf: record(second)) == secondData)
        try fm.removeItem(at: record(first))
        let linkedDirectory = root.appendingPathComponent("linked-vault")
        try fm.createSymbolicLink(at: linkedDirectory, withDestinationURL: directory)
        fails { _ = try MCGLPasswordStore(directory: linkedDirectory).password(for: second) }
        try fm.setAttributes([.posixPermissions: 0o755], ofItemAtPath: directory.path)
        fails { _ = try vault.password(for: second) }
        try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        try vault.remove(for: second)
        precondition(try! vault.password(for: second) == nil)
        print("PASSWORD_STORE_PASS opt-in only; restart; per-account binding; authenticated encryption; fresh nonces; permissions; corruption, missing key and links rejected; opt-out removal; synthetic data only")
    }
}
