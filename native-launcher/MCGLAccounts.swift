import Foundation

struct MCGLAccountInfo: Codable, Equatable {
    let profession: String
    let construction: Int
    let destruction: Int
    let avatar: Data?
    let fetchedAt: Date

    var isValid: Bool {
        !profession.isEmpty && profession.count <= 64
            && !profession.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
            && (0...100_000).contains(construction) && (0...100_000).contains(destruction)
            && (avatar?.count ?? 0) <= 49_152 && fetchedAt.timeIntervalSince1970.isFinite
    }

    var professionIcon: String? {
        switch profession.lowercased().replacingOccurrences(of: "ё", with: "е") {
        case "чернорабочий": return "laborer"
        case "шахтер": return "miner"
        case "охотник": return "hunter"
        case "медик": return "medic"
        case "строитель": return "builder"
        case "кузнец": return "smith"
        case "инженер": return "engineer"
        default: return nil
        }
    }

    var professionTitle: String {
        professionIcon == "miner" ? "Шахтёр" : profession
    }
}

/// Deliberately has no password, token, session or authentication fields.
struct MCGLAccount: Codable, Equatable, Identifiable {
    let id: UUID
    let nickname: String
    var label: String
    var info: MCGLAccountInfo?
    var title: String { label.isEmpty ? nickname : label }
}

enum MCGLAccountError: LocalizedError {
    case invalidNickname, duplicate, limit, corruptStore, invalidResponse, unavailable

    var errorDescription: String? {
        switch self {
        case .invalidNickname: return "Ник: от 1 до 64 латинских букв, цифр или знаков подчёркивания."
        case .duplicate: return "Этот аккаунт уже добавлен."
        case .limit: return "Можно сохранить до 30 аккаунтов."
        case .corruptStore: return "Не удалось прочитать список аккаунтов. Сохранённые данные не изменены."
        case .invalidResponse: return "Профиль не найден или форум вернул неподдерживаемый ответ."
        case .unavailable: return "Не удалось получить данные форума. Попробуйте позже."
        }
    }
}

final class MCGLAccountStore {
    private struct Document: Codable {
        var schema = 1
        var accounts: [MCGLAccount] = []
        var selectedID: UUID?
    }
    private let preferences: MCGLLauncherPreferences
    private var document = Document()
    private(set) var loadError: Error?
    var accounts: [MCGLAccount] { document.accounts }
    var selected: MCGLAccount? { accounts.first { $0.id == document.selectedID } }

    init(preferences: MCGLLauncherPreferences) {
        self.preferences = preferences
        if let data = preferences.accountData {
            guard data.count <= 3_000_000,
                  let saved = try? JSONDecoder().decode(Document.self, from: data),
                  saved.schema == 1, saved.accounts.count <= 30,
                  Set(saved.accounts.map { $0.id }).count == saved.accounts.count,
                  Set(saved.accounts.map { $0.nickname.lowercased() }).count == saved.accounts.count,
                  saved.accounts.allSatisfy({ Self.validNickname($0.nickname) && $0.label.count <= 40
                    && ($0.info?.isValid ?? true) }) else {
                loadError = MCGLAccountError.corruptStore
                return
            }
            document = saved
        } else {
            // One-time migration. A removed profile must not reappear on restart.
            let login = preferences.savedLogin.trimmingCharacters(in: .whitespacesAndNewlines)
            if Self.validNickname(login) {
                let account = MCGLAccount(id: UUID(), nickname: login, label: "", info: nil)
                document.accounts = [account]
                document.selectedID = account.id
            }
            try? persist()
        }
    }

    static func validNickname(_ nickname: String) -> Bool {
        nickname.range(of: #"\A[A-Za-z0-9_]{1,64}\z"#, options: .regularExpression) != nil
    }

    @discardableResult
    func add(nickname: String, label: String = "") throws -> MCGLAccount {
        try writable()
        let nickname = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.validNickname(nickname) else { throw MCGLAccountError.invalidNickname }
        guard !accounts.contains(where: { $0.nickname.caseInsensitiveCompare(nickname) == .orderedSame }) else {
            throw MCGLAccountError.duplicate
        }
        guard accounts.count < 30 else { throw MCGLAccountError.limit }
        let account = MCGLAccount(id: UUID(), nickname: nickname, label: Self.cleanLabel(label), info: nil)
        document.accounts.append(account)
        document.selectedID = account.id
        try persist()
        return account
    }

    func select(_ id: UUID?) throws {
        try writable()
        guard id == nil || accounts.contains(where: { $0.id == id }) else { return }
        document.selectedID = id
        try persist()
    }

    func rename(_ id: UUID, label: String) throws {
        try writable()
        guard let index = accounts.firstIndex(where: { $0.id == id }) else { return }
        document.accounts[index].label = Self.cleanLabel(label)
        try persist()
    }

    func remove(_ id: UUID) throws {
        try writable()
        document.accounts.removeAll { $0.id == id }
        if document.selectedID == id { document.selectedID = document.accounts.first?.id }
        try persist()
    }

    func update(_ id: UUID, nickname: String, info: MCGLAccountInfo) throws {
        try writable()
        guard info.isValid else { throw MCGLAccountError.invalidResponse }
        // An outstanding request must not resurrect a deleted/recreated account.
        guard let index = accounts.firstIndex(where: { $0.id == id && $0.nickname == nickname }) else { return }
        document.accounts[index].info = info
        try persist()
    }

    private static func cleanLabel(_ label: String) -> String {
        String(label.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) })
            .trimmingCharacters(in: .whitespacesAndNewlines).prefix(40).description
    }

    private func writable() throws {
        if let loadError { throw loadError }
    }

    private func persist() throws {
        preferences.accountData = try JSONEncoder().encode(document)
    }
}

/// Extract only the three text fields and an inline image. Never render server
/// HTML, CSS or JavaScript, and never fetch URLs embedded in a response.
enum MCGLAccountInfoParser {
    static let maximumBytes = 65_536

    static func parse(_ data: Data, now: Date = Date()) throws -> MCGLAccountInfo {
        guard data.count <= maximumBytes, let html = String(data: data, encoding: .utf8) else {
            throw MCGLAccountError.invalidResponse
        }
        func field(_ tag: String, _ name: String) throws -> String {
            let pattern = "<\(tag)\\b[^>]*\\bclass\\s*=\\s*([\"'])\(name)\\1[^>]*>\\s*([^<>]{1,128}?)\\s*</\(tag)\\s*>"
            let regex = try NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
            let matches = regex.matches(in: html, range: NSRange(html.startIndex..., in: html))
            guard matches.count == 1, let range = Range(matches[0].range(at: 2), in: html) else {
                throw MCGLAccountError.invalidResponse
            }
            return String(html[range]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let profession = try field("div", "prof")
        let make = try field("span", "make")
        let crush = try field("span", "crush")
        guard profession.count <= 64, !profession.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              make.allSatisfy({ $0.isASCII && $0.isNumber }), crush.allSatisfy({ $0.isASCII && $0.isNumber }),
              let construction = Int(make), let destruction = Int(crush),
              (0...100_000).contains(construction), (0...100_000).contains(destruction) else {
            throw MCGLAccountError.invalidResponse
        }
        let imagePattern = #"<img\b[^>]*\bsrc\s*=\s*["']data:image/(?:png|gif|jpeg);base64,([A-Za-z0-9+/=]+)["']"#
        let imageRegex = try NSRegularExpression(pattern: imagePattern, options: [.caseInsensitive])
        var avatar: Data?
        if let match = imageRegex.firstMatch(in: html, range: NSRange(html.startIndex..., in: html)),
           let range = Range(match.range(at: 1), in: html), html[range].count <= 49_152 {
            avatar = Data(base64Encoded: String(html[range]))
        }
        return MCGLAccountInfo(profession: profession, construction: construction,
                               destruction: destruction, avatar: avatar, fetchedAt: now)
    }
}

protocol MCGLAccountRequest: AnyObject { func cancel() }
extension URLSessionDataTask: MCGLAccountRequest {}
protocol MCGLAccountFetching {
    @discardableResult
    func fetch(nickname: String, completion: @escaping (Result<MCGLAccountInfo, Error>) -> Void) -> MCGLAccountRequest?
}

final class MCGLAccountService: MCGLAccountFetching {
    private let configuration: URLSessionConfiguration
    init(configuration: URLSessionConfiguration = .ephemeral) { self.configuration = configuration }

    static func request(nickname: String) throws -> URLRequest {
        guard MCGLAccountStore.validNickname(nickname) else { throw MCGLAccountError.invalidNickname }
        var components = URLComponents(string: "https://forum.minecraft-galaxy.ru/mcrunex/")!
        // Protocol version used by the official Windows 2.13 launcher, not ours.
        components.queryItems = [URLQueryItem(name: "v", value: "2.13"), URLQueryItem(name: "user", value: nickname)]
        var request = URLRequest(url: components.url!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 12)
        request.setValue("MCGL-ARM64-Launcher/1.6.7", forHTTPHeaderField: "User-Agent")
        request.setValue("text/html", forHTTPHeaderField: "Accept")
        request.httpShouldHandleCookies = false
        return request
    }

    @discardableResult
    func fetch(nickname: String, completion: @escaping (Result<MCGLAccountInfo, Error>) -> Void) -> MCGLAccountRequest? {
        guard let request = try? Self.request(nickname: nickname) else {
            DispatchQueue.main.async { completion(.failure(MCGLAccountError.invalidNickname)) }
            return nil
        }
        let delegate = BoundedReply(completion: completion)
        let config = configuration.copy() as! URLSessionConfiguration
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCredentialStorage = nil
        config.urlCache = nil
        config.timeoutIntervalForResource = 15
        let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
        let task = session.dataTask(with: request)
        task.resume()
        return task
    }

    private final class BoundedReply: NSObject, URLSessionDataDelegate {
        let completion: (Result<MCGLAccountInfo, Error>) -> Void
        var bytes = Data()
        var failure: Error?
        init(completion: @escaping (Result<MCGLAccountInfo, Error>) -> Void) { self.completion = completion }

        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            // No forwarding nicknames to another endpoint or downgrading HTTPS.
            failure = MCGLAccountError.unavailable
            completionHandler(nil)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  http.url?.scheme == "https", http.url?.host == "forum.minecraft-galaxy.ru",
                  http.url.flatMap({ URLComponents(url: $0, resolvingAgainstBaseURL: false)?.percentEncodedPath }) == "/mcrunex/",
                  http.mimeType == "text/html",
                  response.expectedContentLength <= MCGLAccountInfoParser.maximumBytes else {
                failure = MCGLAccountError.unavailable
                completionHandler(.cancel)
                return
            }
            completionHandler(.allow)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            guard bytes.count + data.count <= MCGLAccountInfoParser.maximumBytes else {
                failure = MCGLAccountError.invalidResponse
                dataTask.cancel()
                return
            }
            bytes.append(data)
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            let result: Result<MCGLAccountInfo, Error>
            if let failure = failure ?? error { result = .failure(failure) }
            else { result = Result { try MCGLAccountInfoParser.parse(bytes) } }
            session.finishTasksAndInvalidate()
            DispatchQueue.main.async { self.completion(result) }
        }
    }
}
