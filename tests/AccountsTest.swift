import Foundation

private let profileHTML = """
<html><style>%css%</style><div class="prof">Шахтер</div>
<div class="levels"><span class='make'>14</span> / <span class='crush'>21</span></div></html>
"""

private final class InfoFixture: URLProtocol {
    static var status = 200
    static var body = Data(profileHTML.utf8)
    static var count = 0
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.count += 1
        precondition(request.url?.scheme == "https")
        precondition(request.value(forHTTPHeaderField: "Cookie") == nil)
        precondition(request.value(forHTTPHeaderField: "Authorization") == nil)
        precondition(request.httpBody == nil)
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.status,
            httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/html; charset=UTF-8"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@main
struct AccountsTest {
    static func fails(_ operation: () throws -> Void) {
        do { try operation(); preconditionFailure("Expected rejection") } catch {}
    }

    static func main() throws {
        let suite = "MCGLAccountsTest.\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = MCGLLauncherPreferences(defaults: defaults)
        prefs.rememberLogin(true, login: "DemoMiner")
        prefs.maximumMemoryMB = 4096
        prefs.fpsLimit = 144
        let store = MCGLAccountStore(preferences: prefs)
        let first = store.selected!
        precondition(first.nickname == "DemoMiner" && store.accounts.count == 1)
        precondition(prefs.maximumMemoryMB == 4096 && prefs.fpsLimit == 144)
        fails { try store.add(nickname: "demominer") }
        for invalid in ["", "x&user=another", "http://evil.test", "nick\nother", String(repeating: "a", count: 65), "МойНик"] {
            fails { try store.add(nickname: invalid) }
            fails { _ = try MCGLAccountService.request(nickname: invalid) }
        }
        let second = try store.add(nickname: "  DemoBuilder  ", label: "Твинк")
        precondition(store.selected?.id == second.id)
        try store.rename(second.id, label: "Строитель\n")
        precondition(store.selected?.label == "Строитель")
        let info = try MCGLAccountInfoParser.parse(Data(profileHTML.utf8), now: Date(timeIntervalSince1970: 1000))
        precondition(info.construction == 14 && info.destruction == 21)
        precondition(info.professionIcon == "miner" && info.professionTitle == "Шахтёр")
        try store.update(first.id, nickname: first.nickname, info: info)
        precondition(store.selected?.info == nil, "Result applied to wrong selected profile")
        precondition(store.accounts.first?.info == info)
        let reloaded = MCGLAccountStore(preferences: prefs)
        precondition(reloaded.accounts == store.accounts && reloaded.selected?.id == second.id)
        let dictionary = try JSONSerialization.jsonObject(with: prefs.accountData!) as! [String: Any]
        let row = (dictionary["accounts"] as! [[String: Any]])[0]
        precondition(Set(row.keys) == Set(["id", "nickname", "label", "info"]))
        let infoKeys = Set((row["info"] as! [String: Any]).keys)
        precondition(infoKeys == Set(["profession", "construction", "destruction", "fetchedAt"]))
        try store.remove(first.id)
        try store.update(first.id, nickname: first.nickname, info: info)
        precondition(store.accounts.count == 1, "Deleted account resurrected by late request")
        try store.remove(second.id)
        precondition(store.selected == nil)
        precondition(MCGLAccountStore(preferences: prefs).accounts.isEmpty, "Legacy login migrated a second time")
        for i in 0..<30 { try store.add(nickname: "Demo\(i)") }
        fails { try store.add(nickname: "DemoOverflow") }
        try store.select(nil)
        precondition(MCGLAccountStore(preferences: prefs).selected == nil)
        prefs.accountData = Data("unsupported data".utf8)
        let broken = MCGLAccountStore(preferences: prefs)
        precondition(broken.loadError != nil)
        fails { try broken.add(nickname: "DontOverwrite") }
        precondition(prefs.accountData == Data("unsupported data".utf8))

        for invalid in ["<html>not found</html>", profileHTML + "<span class='make'>99</span>",
                        profileHTML.replacingOccurrences(of: ">14<", with: ">-1<"),
                        profileHTML.replacingOccurrences(of: ">21<", with: ">bad<"),
                        profileHTML.replacingOccurrences(of: ">14<", with: ">100001<"),
                        String(repeating: "a", count: 65_537)] {
            fails { _ = try MCGLAccountInfoParser.parse(Data(invalid.utf8)) }
        }
        let maliciousImage = "<img src='https://evil.invalid/track?secret=never-fetched'>"
        precondition(try! MCGLAccountInfoParser.parse(Data((profileHTML + maliciousImage).utf8)).avatar == nil)
        let unknown = profileHTML.replacingOccurrences(of: "Шахтер", with: "Новая профессия")
        precondition(try! MCGLAccountInfoParser.parse(Data(unknown.utf8)).professionIcon == nil)
        let professions = ["Чернорабочий", "Шахтер", "Шахтёр", "Охотник", "Медик", "Строитель", "Кузнец", "Инженер"]
        for name in professions {
            let parsed = try MCGLAccountInfoParser.parse(Data(profileHTML.replacingOccurrences(of: "Шахтер", with: name).utf8))
            precondition(parsed.professionIcon != nil)
        }
        let request = try MCGLAccountService.request(nickname: "DemoMiner")
        precondition(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems == [
            URLQueryItem(name: "v", value: "2.13"), URLQueryItem(name: "user", value: "DemoMiner")])

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [InfoFixture.self]
        let service = MCGLAccountService(configuration: config)
        for test in 0..<4 {
            InfoFixture.status = test == 1 ? 503 : 200
            InfoFixture.body = Data((test == 2 ? String(repeating: "x", count: 70_000)
                : test == 3 ? "<html>missing account</html>" : profileHTML).utf8)
            var result: Result<MCGLAccountInfo, Error>?
            service.fetch(nickname: "DemoMiner") { result = $0 }
            let deadline = Date().addingTimeInterval(5)
            while result == nil && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.01)) }
            precondition(result != nil)
            if test == 0 { precondition(try! result!.get().construction == 14) }
            else { fails { _ = try result!.get() } }
        }
        precondition(InfoFixture.count == 4, "Unexpected hidden requests")
        print("ACCOUNTS_PASS migration, CRUD, uniqueness, no secrets, malformed store, bounds, seven professions, HTTP errors, oversized reply, no embedded URL requests")
    }
}
