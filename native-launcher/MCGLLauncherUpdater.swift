import CryptoKit
import Darwin
import Foundation

struct MCGLGitHubRelease: Decodable {
    struct Asset: Decodable {
        let name: String
        let downloadURL: URL
        let digest: String?

        enum CodingKeys: String, CodingKey {
            case name
            case downloadURL = "browser_download_url"
            case digest
        }
    }

    let tagName: String
    let title: String
    let notes: String
    let pageURL: URL
    let assets: [Asset]

    enum CodingKeys: String, CodingKey {
        case tagName = "tag_name"
        case title = "name"
        case notes = "body"
        case pageURL = "html_url"
        case assets
    }

    var version: String {
        tagName.hasPrefix("v") || tagName.hasPrefix("V")
            ? String(tagName.dropFirst()) : tagName
    }

    var diskImage: Asset? {
        assets.first { $0.name.lowercased().hasSuffix(".dmg") }
    }

    var installableDiskImage: Asset? {
        guard version.range(of: #"^[0-9]+\.[0-9]+\.[0-9]+$"#, options: .regularExpression) != nil,
              tagName == "v\(version)",
              let asset = diskImage,
              asset.name == "Minecraft-Galaxy-ARM64-Bootstrap-\(version).dmg",
              asset.downloadURL.absoluteString ==
                "https://github.com/prilepv/mcgl-arm64/releases/download/\(tagName)/\(asset.name)",
              asset.digest?.range(of: #"^sha256:[a-fA-F0-9]{64}$"#, options: .regularExpression) != nil
        else { return nil }
        return asset
    }
}

final class MCGLLauncherUpdater {
    // The installed app is authoritative; the fallback is for standalone tools/tests.
    static let currentVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.8.1"
    static let releasesPage = URL(string: "https://github.com/prilepv/mcgl-arm64/releases")!
    private static let latestReleaseAPI = URL(
        string: "https://api.github.com/repos/prilepv/mcgl-arm64/releases/latest")!

    enum CheckResult {
        case current
        case available(MCGLGitHubRelease)
    }

    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func check(completion: @escaping (Result<CheckResult, Error>) -> Void) {
        var request = URLRequest(url: Self.latestReleaseAPI)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("MCGL-ARM64-Launcher/\(Self.currentVersion)",
                         forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20
        session.dataTask(with: request) { data, response, error in
            if let error { completion(.failure(error)); return }
            guard let http = response as? HTTPURLResponse, http.url == Self.latestReleaseAPI,
                  (200...299).contains(http.statusCode), let data else {
                completion(.failure(Self.error("GitHub вернул некорректный ответ.")))
                return
            }
            do {
                let release = try JSONDecoder().decode(MCGLGitHubRelease.self, from: data)
                completion(.success(Self.isNewer(release.version,
                                                 than: Self.currentVersion)
                    ? .available(release) : .current))
            } catch {
                completion(.failure(error))
            }
        }.resume()
    }

    func download(_ release: MCGLGitHubRelease,
                  completion: @escaping (Result<URL, Error>) -> Void) {
        guard let asset = release.diskImage else {
            completion(.failure(Self.error("В релизе нет установочного DMG.")))
            return
        }
        var request = URLRequest(url: asset.downloadURL)
        request.setValue("MCGL-ARM64-Launcher/\(Self.currentVersion)",
                         forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 180
        session.downloadTask(with: request) { temporaryURL, response, error in
            if let error { completion(.failure(error)); return }
            guard let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode), let temporaryURL else {
                completion(.failure(Self.error("Не удалось загрузить DMG с GitHub.")))
                return
            }
            do {
                try Self.verifyDigest(asset.digest, file: temporaryURL)
                let destination = try Self.availableDownloadURL(named: asset.name)
                try FileManager.default.copyItem(at: temporaryURL, to: destination)
                completion(.success(destination))
            } catch {
                completion(.failure(error))
            }
        }.resume()
    }

    func prepareInstallation(_ release: MCGLGitHubRelease, installed: URL,
                             completion: @escaping (Result<MCGLPreparedLauncherUpdate, Error>) -> Void) {
        guard let asset = release.installableDiskImage, Self.isNewer(release.version, than: Self.currentVersion) else {
            completion(.failure(Self.error("Для установки из лаунчера нужен новый релиз с SHA-256.")))
            return
        }
        var request = URLRequest(url: asset.downloadURL)
        request.timeoutInterval = 180
        session.downloadTask(with: request) { temporaryURL, response, error in
            if let error { completion(.failure(error)); return }
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  http.url?.scheme == "https",
                  let host = http.url?.host,
                  ["github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"].contains(host),
                  let temporaryURL else {
                completion(.failure(Self.error("Не удалось получить установочный пакет с GitHub.")))
                return
            }
            completion(Result {
                try Self.verifyDigest(asset.digest, file: temporaryURL)
                return try MCGLPreparedLauncherUpdate.prepare(diskImage: temporaryURL,
                                                               version: release.version, installed: installed)
            })
        }.resume()
    }

    static func isNewer(_ candidate: String, than installed: String) -> Bool {
        let left = numericVersion(candidate)
        let right = numericVersion(installed)
        for index in 0..<max(left.count, right.count) {
            let a = index < left.count ? left[index] : 0
            let b = index < right.count ? right[index] : 0
            if a != b { return a > b }
        }
        return false
    }

    private static func numericVersion(_ value: String) -> [Int] {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
            .split(separator: ".")
            .map { component in
                let digits = component.prefix { $0.isNumber }
                return Int(digits) ?? 0
            }
    }

    private static func availableDownloadURL(named name: String) throws -> URL {
        guard let downloads = FileManager.default.urls(for: .downloadsDirectory,
                                                       in: .userDomainMask).first else {
            throw error("Не удалось найти папку «Загрузки».")
        }
        let assetName = URL(fileURLWithPath: name).lastPathComponent
        let base = URL(fileURLWithPath: assetName).deletingPathExtension().lastPathComponent
        let ext = URL(fileURLWithPath: assetName).pathExtension
        for suffix in 0...999 {
            let filename = suffix == 0 ? assetName : "\(base)-\(suffix).\(ext)"
            let destination = downloads.appendingPathComponent(filename)
            if !FileManager.default.fileExists(atPath: destination.path) {
                return destination
            }
        }
        throw error("В папке «Загрузки» слишком много копий этого релиза.")
    }

    private static func verifyDigest(_ expected: String?, file: URL) throws {
        guard let expected, expected.lowercased().hasPrefix("sha256:") else { return }
        let wanted = String(expected.dropFirst("sha256:".count)).lowercased()
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let data = try handle.read(upToCount: 1024 * 1024) ?? Data()
            if data.isEmpty { break }
            hasher.update(data: data)
        }
        let actual = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard actual == wanted else {
            throw error("Контрольная сумма загруженного DMG не совпала с GitHub.")
        }
    }

    fileprivate static func error(_ message: String) -> NSError {
        NSError(domain: "MinecraftGalaxyARM64.Updater", code: 1,
                userInfo: [NSLocalizedDescriptionKey: message])
    }
}

/// Owns the staged bundle and keeps the previous app until relaunch succeeds.
final class MCGLPreparedLauncherUpdate {
    private static let appIdentifier = "community.mcgl.arm64-port"
    private let directory: URL
    private let staged: URL
    private let installed: URL
    private let installedIdentity: NSObject
    private enum State { case staged, exchanged, finished }
    private var state: State = .staged

    private init(directory: URL, staged: URL, installed: URL, installedIdentity: NSObject) {
        self.directory = directory
        self.staged = staged
        self.installed = installed
        self.installedIdentity = installedIdentity
    }

    static func canReplace(_ app: URL) -> Bool {
        let values = try? app.resourceValues(forKeys: [.volumeIsReadOnlyKey, .isSymbolicLinkKey])
        return app.pathExtension == "app" && values?.volumeIsReadOnly == false
            && values?.isSymbolicLink == false
            && FileManager.default.isWritableFile(atPath: app.deletingLastPathComponent().path)
    }

    static func prepare(diskImage: URL, version: String, installed: URL) throws -> MCGLPreparedLauncherUpdate {
        let files = FileManager.default
        guard canReplace(installed),
              let current = Bundle(url: installed),
              current.bundleIdentifier == appIdentifier,
              let currentVersion = current.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
              MCGLLauncherUpdater.isNewer(version, than: currentVersion),
              let identity = try installed.resourceValues(forKeys: [.fileResourceIdentifierKey]).fileResourceIdentifier as? NSObject
        else {
            throw MCGLLauncherUpdater.error("Сначала перемести лаунчер в папку, в которой можно менять приложения.")
        }
        let directory = try files.url(for: .itemReplacementDirectory, in: .userDomainMask,
                                      appropriateFor: installed, create: true)
        let mount = directory.appendingPathComponent("image", isDirectory: true)
        var mounted = false
        do {
            let staged = directory.appendingPathComponent("Minecraft Galaxy ARM64.app", isDirectory: true)
            try files.createDirectory(at: mount, withIntermediateDirectories: false)
            try run("/usr/bin/hdiutil", ["attach", "-readonly", "-nobrowse", "-noautoopen",
                                         "-mountpoint", mount.path, diskImage.path])
            mounted = true
            let source = mount.appendingPathComponent(staged.lastPathComponent, isDirectory: true)
            let sourceValues = try source.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard sourceValues.isDirectory == true, sourceValues.isSymbolicLink == false else {
                throw MCGLLauncherUpdater.error("В образе нет приложения Minecraft Galaxy ARM64.")
            }
            try run("/usr/bin/ditto", [source.path, staged.path])
            try run("/usr/bin/hdiutil", ["detach", mount.path])
            mounted = false
            guard let bundle = Bundle(url: staged), bundle.bundleIdentifier == appIdentifier,
                  bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String == version,
                  bundle.executableArchitectures?.contains(NSNumber(value: CPU_TYPE_ARM64)) == true else {
                throw MCGLLauncherUpdater.error("Версия, тип или архитектура приложения не совпадают с релизом.")
            }
            if let minimum = bundle.object(forInfoDictionaryKey: "LSMinimumSystemVersion") as? String {
                let os = ProcessInfo.processInfo.operatingSystemVersion
                let system = "\(os.majorVersion).\(os.minorVersion).\(os.patchVersion)"
                guard !MCGLLauncherUpdater.isNewer(minimum, than: system) else {
                    throw MCGLLauncherUpdater.error("Для этой версии требуется macOS \(minimum).")
                }
            }
            try run("/usr/bin/codesign", ["--verify", "--deep", "--strict", staged.path])
            return MCGLPreparedLauncherUpdate(directory: directory, staged: staged, installed: installed,
                                              installedIdentity: identity)
        } catch {
            if !mounted || (try? run("/usr/bin/hdiutil", ["detach", mount.path])) != nil {
                try? files.removeItem(at: directory)
            }
            throw error
        }
    }

    func install() throws {
        guard state == .staged, Self.canReplace(installed),
              let identity = try installed.resourceValues(forKeys: [.fileResourceIdentifierKey]).fileResourceIdentifier as? NSObject,
              identity == installedIdentity else {
            throw MCGLLauncherUpdater.error("Лаунчер изменился во время загрузки обновления.")
        }
        try exchange()
        state = .exchanged
    }

    func finish(relaunched: Bool) throws {
        guard state == .exchanged else { return }
        if !relaunched {
            do {
                try exchange()
            } catch {
                throw MCGLLauncherUpdater.error("Не удалось вернуть предыдущую версию. Она сохранена здесь: \(staged.path)")
            }
        }
        state = .finished
        try? FileManager.default.removeItem(at: directory)
    }

    func discard() {
        guard state == .staged else { return }
        state = .finished
        try? FileManager.default.removeItem(at: directory)
    }

    private func exchange() throws {
        guard renamex_np(staged.path, installed.path, UInt32(RENAME_SWAP)) == 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
    }

    private static func run(_ executable: String, _ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.standardInput = FileHandle.nullDevice
        let output = Pipe()
        process.standardOutput = output
        process.standardError = output
        try process.run()
        let result = output.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let detail = String(decoding: result.suffix(600), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            throw MCGLLauncherUpdater.error(detail.isEmpty ? "Не удалось подготовить приложение." : detail)
        }
    }
}
