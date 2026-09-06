import Foundation

/// Also compile with the unmodified 1.6.6 updater to exercise its real parser.
private final class AccountsReleaseFixture: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let data = Data("""
        {"tag_name":"v1.6.7","name":"Minecraft Galaxy ARM64 1.6.7",
         "body":"Менеджер аккаунтов","html_url":"https://github.com/prilepv/mcgl-arm64/releases/tag/v1.6.7",
         "assets":[{"name":"Minecraft-Galaxy-ARM64-Bootstrap-1.6.7.dmg",
         "browser_download_url":"https://github.com/prilepv/mcgl-arm64/releases/download/v1.6.7/Minecraft-Galaxy-ARM64-Bootstrap-1.6.7.dmg",
         "digest":"sha256:0000000000000000000000000000000000000000000000000000000000000000"}]}
        """.utf8)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200,
            httpVersion: "HTTP/1.1", headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@main
struct LauncherAccountsUpgradeTest {
    static func main() {
        let liveDownload = CommandLine.arguments.contains("--live-download")
        let live = liveDownload || CommandLine.arguments.contains("--live-check")
        let config = URLSessionConfiguration.ephemeral
        if !live { config.protocolClasses = [AccountsReleaseFixture.self] }
        let updater = MCGLLauncherUpdater(session: URLSession(configuration: config))
        let done = DispatchSemaphore(value: 0)
        var passed = false
        updater.check { result in
            switch result {
            case .success(.available(let release)):
                passed = ["1.6.5", "1.6.6"].contains(MCGLLauncherUpdater.currentVersion)
                    && release.version == "1.6.7"
                    && release.diskImage?.name == "Minecraft-Galaxy-ARM64-Bootstrap-1.6.7.dmg"
                    && release.diskImage?.digest?.hasPrefix("sha256:") == true
                if passed && liveDownload {
                    updater.download(release) { result in
                        switch result {
                        case .success(let file):
                            print("ACCOUNTS_UPGRADE_DOWNLOAD_PASS source=\(MCGLLauncherUpdater.currentVersion) target=1.6.7 digest=verified file=\(file.path)")
                        case .failure(let error):
                            passed = false
                            print("ACCOUNTS_UPGRADE_DOWNLOAD_FAIL \(error.localizedDescription)")
                        }
                        done.signal()
                    }
                    return
                }
            case .success(.current): passed = MCGLLauncherUpdater.currentVersion == "1.6.7"
            case .failure: break
            }
            done.signal()
        }
        precondition(done.wait(timeout: .now() + (liveDownload ? 300 : live ? 30 : 10)) == .success && passed)
        print("ACCOUNTS_UPGRADE_PASS source=\(MCGLLauncherUpdater.currentVersion) target=1.6.7 mode=\(liveDownload ? "live-download" : live ? "live-check" : "fixture")")
    }
}
