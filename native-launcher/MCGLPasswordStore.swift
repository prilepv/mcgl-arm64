import Foundation
import CryptoKit
import Darwin

/// Convenience storage, NOT a boundary against software running as this user.
/// The AES key is local too; no Keychain, master password, hardware ID or network.
final class MCGLPasswordStore {
    enum Failure: LocalizedError {
        case unavailable, invalidPassword
        var errorDescription: String? {
            switch self {
            case .unavailable:
                return "Не удалось прочитать или изменить сохранённый пароль. Данные не сброшены."
            case .invalidPassword:
                return "Пароль должен содержать от 1 до 4096 байт и не содержать переносов строки."
            }
        }
    }
    private struct Record: Codable {
        let version: Int
        let password: String
    }
    let directory: URL
    private let lock = NSLock()
    private let maximumRecordSize = 32_768

    init(directory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Minecraft Galaxy ARM64/Credentials", isDirectory: true)) {
        self.directory = directory
    }

    func password(for account: MCGLAccount) throws -> String? {
        lock.lock(); defer { lock.unlock() }
        guard let dir = try openDirectory(create: false) else { return nil }
        defer { close(dir) }
        guard let sealed = try read(name(account), in: dir) else { return nil }
        do {
            let key = try localKey(in: dir, create: false)
            let data = try AES.GCM.open(AES.GCM.SealedBox(combined: sealed), using: key,
                                        authenticating: identity(account))
            let record = try JSONDecoder().decode(Record.self, from: data)
            guard record.version == 1, Self.valid(record.password) else { throw Failure.unavailable }
            return record.password
        } catch { throw Failure.unavailable }
    }

    func save(_ password: String, for account: MCGLAccount) throws {
        guard Self.valid(password) else { throw Failure.invalidPassword }
        lock.lock(); defer { lock.unlock() }
        guard let dir = try openDirectory(create: true) else { throw Failure.unavailable }
        defer { close(dir) }
        do {
            let key = try localKey(in: dir, create: true)
            let data = try JSONEncoder().encode(Record(version: 1, password: password))
            let sealed = try AES.GCM.seal(data, using: key, authenticating: identity(account))
            guard let combined = sealed.combined else { throw Failure.unavailable }
            // Reject suspicious existing files rather than following links.
            _ = try read(name(account), in: dir)
            try atomicWrite(combined, to: name(account), in: dir)
        } catch { throw Failure.unavailable }
    }

    func remove(for account: MCGLAccount) throws {
        lock.lock(); defer { lock.unlock() }
        guard let dir = try openDirectory(create: false) else { return }
        defer { close(dir) }
        guard try read(name(account), in: dir) != nil else { return }
        guard unlinkat(dir, name(account), 0) == 0 else { throw Failure.unavailable }
    }

    private static func valid(_ password: String) -> Bool {
        !password.isEmpty && password.utf8.count <= 4096
            && !password.contains("\n") && !password.contains("\r") && !password.contains("\0")
    }
    private func name(_ account: MCGLAccount) -> String { account.id.uuidString.lowercased() + ".sealed" }
    private func identity(_ account: MCGLAccount) -> Data {
        Data("MCGL-password-v1:\(account.id.uuidString.lowercased()):\(account.nickname.lowercased())".utf8)
    }

    private func openDirectory(create: Bool) throws -> Int32? {
        var descriptor = Darwin.open(directory.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        if descriptor < 0, errno == ENOENT {
            guard create else { return nil }
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                         attributes: [.posixPermissions: 0o700])
            } catch { throw Failure.unavailable }
            descriptor = Darwin.open(directory.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        }
        guard descriptor >= 0 else { throw Failure.unavailable }
        var info = stat()
        guard fstat(descriptor, &info) == 0, info.st_uid == getuid(),
              info.st_mode & 0o777 == 0o700 else {
            close(descriptor)
            throw Failure.unavailable
        }
        return descriptor
    }

    private func read(_ filename: String, in directory: Int32) throws -> Data? {
        let descriptor = openat(directory, filename, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
        if descriptor < 0, errno == ENOENT { return nil }
        guard descriptor >= 0 else { throw Failure.unavailable }
        defer { close(descriptor) }
        var info = stat()
        guard fstat(descriptor, &info) == 0, info.st_uid == getuid(), info.st_nlink == 1,
              info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o777 == 0o600,
              info.st_size >= 0, info.st_size <= maximumRecordSize else { throw Failure.unavailable }
        var data = Data(count: Int(info.st_size))
        try data.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) in
            var offset = 0
            while offset < buffer.count {
                let count = Darwin.read(descriptor, buffer.baseAddress!.advanced(by: offset), buffer.count - offset)
                if count < 0, errno == EINTR { continue }
                guard count > 0 else { throw Failure.unavailable }
                offset += count
            }
        }
        return data
    }

    private func localKey(in dir: Int32, create: Bool) throws -> SymmetricKey {
        if let data = try read("local-key", in: dir) {
            guard data.count == 32 else { throw Failure.unavailable }
            return SymmetricKey(data: data)
        }
        guard create else { throw Failure.unavailable }
        // A lost key must never be silently regenerated over an existing vault.
        guard let entries = try? FileManager.default.contentsOfDirectory(atPath: directory.path),
              !entries.contains(where: { $0.hasSuffix(".sealed") }) else { throw Failure.unavailable }
        let key = SymmetricKey(size: .bits256)
        let data = key.withUnsafeBytes { Data($0) }
        // Publish a complete key atomically and without replacing another launcher's key.
        let temporary = ".key-\(UUID()).tmp"
        try createFile(data, name: temporary, in: dir)
        defer { unlinkat(dir, temporary, 0) }
        if linkat(dir, temporary, dir, "local-key", 0) != 0 {
            guard errno == EEXIST else { throw Failure.unavailable }
        }
        // Remove the temporary hard link before read() checks the link count.
        guard unlinkat(dir, temporary, 0) == 0 else { throw Failure.unavailable }
        guard let saved = try read("local-key", in: dir), saved.count == 32 else { throw Failure.unavailable }
        return SymmetricKey(data: saved)
    }

    private func createFile(_ data: Data, name: String, in dir: Int32) throws {
        let descriptor = openat(dir, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, mode_t(0o600))
        guard descriptor >= 0 else { throw Failure.unavailable }
        defer { close(descriptor) }
        do {
            guard fchmod(descriptor, 0o600) == 0 else { throw Failure.unavailable }
            try data.withUnsafeBytes { buffer in
                var offset = 0
                while offset < buffer.count {
                    let count = Darwin.write(descriptor, buffer.baseAddress!.advanced(by: offset), buffer.count - offset)
                    if count < 0, errno == EINTR { continue }
                    guard count > 0 else { throw Failure.unavailable }
                    offset += count
                }
            }
            guard fsync(descriptor) == 0 else { throw Failure.unavailable }
        } catch {
            unlinkat(dir, name, 0)
            throw Failure.unavailable
        }
    }

    private func atomicWrite(_ data: Data, to filename: String, in dir: Int32) throws {
        let temporary = ".password-\(UUID()).tmp"
        try createFile(data, name: temporary, in: dir)
        defer { unlinkat(dir, temporary, 0) }
        guard renameat(dir, temporary, dir, filename) == 0 else { throw Failure.unavailable }
    }
}
