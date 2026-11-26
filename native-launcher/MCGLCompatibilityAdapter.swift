import CryptoKit
import Darwin
import Foundation

/// Executable patch tools, authenticated separately from data-only name packs.
struct MCGLCompatibilityAdapter {
    struct Manifest: Codable, Equatable {
        let schema: Int
        let abi: String
        let installer: String
        let revision: Int
        let minimumBuild: Int
        let java: Int
        let runtimes: [String: String]
        let archiveSHA256: String
    }

    let manifest: Manifest
    let archive: Data
    let envelope: Data
    let identity: String

    static let magic = Data("MCGLAD1\n".utf8)
    static let limit = 16 * 1024 * 1024

    static func decode(_ data: Data, key: Data, build: Int, runtimes: [String: String]) throws -> Self {
        guard data.count > 76, data.count <= limit, data.prefix(8) == magic else {
            throw failure("Некорректный формат модуля совместимости.")
        }
        let payload = data.subdata(in: 72..<data.count)
        let publisher = try Curve25519.Signing.PublicKey(rawRepresentation: key)
        guard publisher.isValidSignature(data.subdata(in: 8..<72), for: payload) else {
            throw failure("Подпись модуля совместимости не прошла проверку.")
        }
        let length = payload.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        guard length > 0, length <= 16 * 1024, length + 4 < payload.count else {
            throw failure("Некорректное описание модуля совместимости.")
        }
        let manifest = try JSONDecoder().decode(Manifest.self, from: payload.subdata(in: 4..<(4 + length)))
        let archive = payload.subdata(in: (4 + length)..<payload.count)
        guard manifest.schema == 1, manifest.abi == "core-17", manifest.installer == "installer-1",
              manifest.revision > 0, manifest.minimumBuild >= 0, manifest.minimumBuild <= build,
              manifest.java == 21, manifest.runtimes == runtimes,
              manifest.archiveSHA256 == hash(archive) else {
            throw failure("Модуль не подходит к этой версии лаунчера или графических библиотек.")
        }
        return Self(manifest: manifest, archive: archive, envelope: data, identity: hash(data))
    }

    static func hash(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func failure(_ message: String) -> NSError {
        NSError(domain: "MinecraftGalaxyARM64.CompatibilityAdapter", code: 1,
                userInfo: [NSLocalizedDescriptionKey: message])
    }
}

/// Keeps signed archives, promotes only a successfully installed module, and
/// remembers failures per official-client hash so a bad update can be bypassed.
final class MCGLCompatibilityAdapters {
    private struct State: Codable {
        var current: String?
        var previous: String?
        var highestRevision = 0
        var rejected: [String: String] = [:]
    }

    static let endpoint = URL(string: "https://raw.githubusercontent.com/prilepv/mcgl-arm64/compatibility/adapters/v1/core-17/latest.adapter")!
    private let root: URL
    private let key: Data
    private let build: Int
    private let runtimes: [String: String]
    private let transport: (URL) throws -> Data
    private let fm = FileManager.default

    init(root: URL, build: Int, runtimes: [String: String], publisherKey: Data? = nil,
         transport: ((URL) throws -> Data)? = nil) {
        self.root = root
        self.build = build
        self.runtimes = runtimes
        self.key = publisherKey ?? Data(stride(from: 0, to: MCGLCompatibilityPacks.publisherKey.count, by: 2).map { offset in
            let start = MCGLCompatibilityPacks.publisherKey.index(MCGLCompatibilityPacks.publisherKey.startIndex, offsetBy: offset)
            return UInt8(MCGLCompatibilityPacks.publisherKey[start..<MCGLCompatibilityPacks.publisherKey.index(start, offsetBy: 2)], radix: 16)!
        })
        self.transport = transport ?? { try BoundedPackRequest.fetch($0, limit: MCGLCompatibilityAdapter.limit) }
    }

    private func withState<T>(_ action: (inout State) throws -> T) throws -> T {
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        let lock = open(root.appendingPathComponent(".lock").path, O_CREAT | O_RDWR | O_NOFOLLOW, 0o600)
        guard lock >= 0 else { throw MCGLCompatibilityAdapter.failure("Не удалось открыть состояние модулей.") }
        defer { close(lock) }
        guard flock(lock, LOCK_EX) == 0 else { throw MCGLCompatibilityAdapter.failure("Не удалось заблокировать состояние модулей.") }
        defer { flock(lock, LOCK_UN) }
        let url = root.appendingPathComponent("state.json")
        var state = State()
        if fm.fileExists(atPath: url.path) {
            let info = try url.resourceValues(forKeys: [.fileSizeKey, .isSymbolicLinkKey, .isRegularFileKey])
            guard info.isSymbolicLink != true, info.isRegularFile == true, (info.fileSize ?? Int.max) <= 64 * 1024 else {
                throw MCGLCompatibilityAdapter.failure("Некорректное состояние модулей.")
            }
            state = try JSONDecoder().decode(State.self, from: Data(contentsOf: url))
        }
        let result = try action(&state)
        try JSONEncoder().encode(state).write(to: url, options: .atomic)
        return result
    }

    private func read(_ identity: String?) throws -> MCGLCompatibilityAdapter? {
        guard let identity else { return nil }
        guard identity.count == 64, identity.allSatisfy({ "0123456789abcdef".contains($0) }) else {
            throw MCGLCompatibilityAdapter.failure("Некорректный идентификатор модуля.")
        }
        let url = root.appendingPathComponent(identity + ".adapter")
        let info = try url.resourceValues(forKeys: [.fileSizeKey, .isSymbolicLinkKey, .isRegularFileKey])
        guard info.isSymbolicLink != true, info.isRegularFile == true,
              (info.fileSize ?? Int.max) <= MCGLCompatibilityAdapter.limit else {
            throw MCGLCompatibilityAdapter.failure("Некорректный файл модуля.")
        }
        let adapter = try MCGLCompatibilityAdapter.decode(Data(contentsOf: url), key: key, build: build, runtimes: runtimes)
        guard adapter.identity == identity else { throw MCGLCompatibilityAdapter.failure("Файл модуля изменился.") }
        return adapter
    }

    func candidates(client: String?, progress: (String) -> Void) -> [MCGLCompatibilityAdapter] {
        if let client, client.count != 64 || !client.allSatisfy({ "0123456789abcdef".contains($0) }) { return [] }
        var downloaded: MCGLCompatibilityAdapter?
        progress("Проверка обновлений модуля совместимости…")
        do {
            downloaded = try MCGLCompatibilityAdapter.decode(transport(Self.endpoint), key: key, build: build, runtimes: runtimes)
        } catch {
            progress("Обновление модуля совместимости недоступно: \(error.localizedDescription)")
        }
        do {
            return try withState { state in
                var result: [MCGLCompatibilityAdapter] = []
                if let downloaded, downloaded.manifest.revision >= state.highestRevision {
                    let sameRevision = downloaded.manifest.revision == state.highestRevision
                    if !sameRevision || state.current == downloaded.identity || state.highestRevision == 0 {
                        let destination = root.appendingPathComponent(downloaded.identity + ".adapter")
                        try downloaded.envelope.write(to: destination, options: .atomic)
                        result.append(downloaded)
                    }
                }
                for identity in [state.current, state.previous] {
                    if let adapter = try? read(identity), !result.contains(where: { $0.identity == adapter.identity }) {
                        result.append(adapter)
                    }
                }
                return result.filter { client == nil || state.rejected[$0.identity] != client }
            }
        } catch {
            progress("Не удалось прочитать модули совместимости: \(error.localizedDescription)")
            return []
        }
    }

    func reject(_ adapter: MCGLCompatibilityAdapter, client: String) throws {
        try withState { state in
            state.rejected[adapter.identity] = client
            // Bound the record while retaining the current rollback pair.
            if state.rejected.count > 32 {
                state.rejected = state.rejected.filter { $0.key == state.current || $0.key == state.previous || $0.key == adapter.identity }
            }
        }
    }

    func confirm(_ adapter: MCGLCompatibilityAdapter) throws {
        try withState { state in
            guard adapter.manifest.revision >= state.highestRevision ||
                  adapter.identity == state.current || adapter.identity == state.previous else {
                throw MCGLCompatibilityAdapter.failure("Версия модуля устарела.")
            }
            if state.current != adapter.identity {
                state.previous = state.current
                state.current = adapter.identity
            }
            state.highestRevision = max(state.highestRevision, adapter.manifest.revision)
            state.rejected.removeValue(forKey: adapter.identity)
            let keep = Set([state.current, state.previous].compactMap { $0 })
            for url in try fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
                where url.pathExtension == "adapter" && !keep.contains(url.deletingPathExtension().lastPathComponent) {
                try? fm.removeItem(at: url)
            }
        }
    }
}
