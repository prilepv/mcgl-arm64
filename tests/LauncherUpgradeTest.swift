import Foundation

/// Compile against the unmodified updater from tag v1.6.5 to check the actual
/// old client, not just a reimplementation of its version comparison.
private final class ReleaseFixture: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let data = Data("""
        {"tag_name":"v1.6.6","name":"Minecraft Galaxy ARM64 1.6.6",
         "body":"Синяя космическая тема","html_url":"https://github.com/prilepv/mcgl-arm64/releases/tag/v1.6.6",
         "assets":[{"name":"Minecraft-Galaxy-ARM64-Bootstrap-1.6.6.dmg",
         "browser_download_url":"https://github.com/prilepv/mcgl-arm64/releases/download/v1.6.6/Minecraft-Galaxy-ARM64-Bootstrap-1.6.6.dmg",
         "digest":"sha256:0000000000000000000000000000000000000000000000000000000000000000"}]}
        """.utf8)
        let response = HTTPURLResponse(url: request.url!, statusCode: 200,
                                       httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@main
struct LauncherUpgradeTest {
    static func main() {
        let live = CommandLine.arguments.contains("--live-download")
        let configuration = URLSessionConfiguration.ephemeral
        if !live { configuration.protocolClasses = [ReleaseFixture.self] }
        let updater = MCGLLauncherUpdater(session: URLSession(configuration: configuration))
        let done = DispatchSemaphore(value: 0)
        var succeeded = false
        updater.check { result in
            switch result {
            case .success(.available(let release)):
                guard MCGLLauncherUpdater.currentVersion == "1.6.5",
                      release.version == "1.6.6",
                      release.diskImage?.name == "Minecraft-Galaxy-ARM64-Bootstrap-1.6.6.dmg",
                      release.diskImage?.digest?.hasPrefix("sha256:") == true else {
                    done.signal(); return
                }
                if live {
                    updater.download(release) { result in
                        switch result {
                        case .success(let file):
                            print("UPGRADE_LIVE_DOWNLOAD_PASS old=1.6.5 new=1.6.6 sha256=verified file=\(file.path)")
                            succeeded = true
                        case .failure(let error): print("UPGRADE_DOWNLOAD_FAIL \(error.localizedDescription)")
                        }
                        done.signal()
                    }
                } else {
                    succeeded = true
                    print("UPGRADE_FIXTURE_PASS unmodified 1.6.5 updater detects 1.6.6 and selects DMG with SHA-256")
                    done.signal()
                }
            case .success(.current):
                succeeded = !live && MCGLLauncherUpdater.currentVersion == "1.6.6"
                if succeeded { print("UPGRADE_CURRENT_PASS 1.6.6 does not offer itself again") }
                done.signal()
            case .failure(let error):
                print("UPGRADE_CHECK_FAIL \(error.localizedDescription)")
                done.signal()
            }
        }
        guard done.wait(timeout: .now() + (live ? 300 : 15)) == .success, succeeded else {
            fatalError("Launcher upgrade test failed")
        }
    }
}
