import CryptoKit
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
}

final class MCGLLauncherUpdater {
    static let currentVersion = "1.6.5"
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
            guard let http = response as? HTTPURLResponse,
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
        let base = URL(fileURLWithPath: name).deletingPathExtension().lastPathComponent
        let ext = URL(fileURLWithPath: name).pathExtension
        for suffix in 0...999 {
            let filename = suffix == 0 ? name : "\(base)-\(suffix).\(ext)"
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

    private static func error(_ message: String) -> NSError {
        NSError(domain: "MinecraftGalaxyARM64.Updater", code: 1,
                userInfo: [NSLocalizedDescriptionKey: message])
    }
}
