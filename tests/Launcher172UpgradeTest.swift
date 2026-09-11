import Foundation

/// Compile with current or unmodified historical updater source. No installation.
private final class Release172Fixture: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let data = Data("""
        {"tag_name":"v1.7.2","name":"Minecraft Galaxy ARM64 1.7.2",
         "body":"Краткие изменения","html_url":"https://github.com/prilepv/mcgl-arm64/releases/tag/v1.7.2",
         "assets":[{"name":"Minecraft-Galaxy-ARM64-Bootstrap-1.7.2.dmg",
         "browser_download_url":"https://github.com/prilepv/mcgl-arm64/releases/download/v1.7.2/Minecraft-Galaxy-ARM64-Bootstrap-1.7.2.dmg",
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
struct Launcher172UpgradeTest {
    static func main() {
        let live = CommandLine.arguments.contains("--live-check")
        let config = URLSessionConfiguration.ephemeral
        if !live { config.protocolClasses = [Release172Fixture.self] }
        let done = DispatchSemaphore(value: 0)
        var passed = false
        let old = MCGLLauncherUpdater.isNewer("1.7.2", than: MCGLLauncherUpdater.currentVersion)
        MCGLLauncherUpdater(session: URLSession(configuration: config)).check { result in
            switch result {
            case .success(.available(let release)):
                passed = old && release.version == "1.7.2"
                    && release.diskImage?.name == "Minecraft-Galaxy-ARM64-Bootstrap-1.7.2.dmg"
                    && release.diskImage?.digest?.hasPrefix("sha256:") == true
                    && release.pageURL.absoluteString == "https://github.com/prilepv/mcgl-arm64/releases/tag/v1.7.2"
            case .success(.current): passed = !old
            case .failure: break
            }
            done.signal()
        }
        precondition(done.wait(timeout: .now() + 30) == .success && passed)
        print("RELEASE_172_UPGRADE_PASS source=\(MCGLLauncherUpdater.currentVersion) target=1.7.2 mode=\(live ? "live-check" : "fixture") installation=false")
    }
}
