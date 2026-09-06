import Cocoa
import QuartzCore

private final class NoPreviewNetwork: MCGLAccountFetching {
    func fetch(nickname: String, completion: @escaping (Result<MCGLAccountInfo, Error>) -> Void) -> MCGLAccountRequest? {
        preconditionFailure("Screenshot capture must not request network access")
    }
}

/// Render the actual launcher views with isolated demo data, not a drawn mockup.
/// No application delegate startup, network requests, passwords or game launch.
@main
struct CaptureLauncherScreenshots {
    static func descendants(_ view: NSView) -> [NSView] { [view] + view.subviews.flatMap(descendants) }

    static func main() throws {
        precondition(CommandLine.arguments.count == 3, "Usage: capture-launcher resources-directory output-directory")
        let resources = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let output = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
        let app = NSApplication.shared
        precondition(CGPreflightScreenCaptureAccess(), "Screen capture permission is required; no permission prompt is requested automatically")
        app.setActivationPolicy(.accessory)
        let suite = "MCGLReleaseScreenshots.\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = MCGLLauncherPreferences(defaults: defaults)
        let store = MCGLAccountStore(preferences: preferences)
        let miner = try store.add(nickname: "DemoMiner", label: "Основной персонаж")
        let builder = try store.add(nickname: "DemoBuilder", label: "Для стройки")
        try store.update(miner.id, nickname: miner.nickname, info: MCGLAccountInfo(
            profession: "Шахтёр", construction: 14, destruction: 21, avatar: nil, fetchedAt: Date()))
        try store.update(builder.id, nickname: builder.nickname, info: MCGLAccountInfo(
            profession: "Строитель", construction: 25, destruction: 12, avatar: nil, fetchedAt: Date()))
        try store.select(miner.id)
        let vault = FileManager.default.temporaryDirectory.appendingPathComponent("MCGLScreenshotVault-\(UUID())")
        let delegate = AppDelegate(preferences: preferences, resourcesRoot: resources,
                                   accountService: NoPreviewNetwork(), passwordStore: MCGLPasswordStore(directory: vault))
        delegate.buildWindow()
        let window = app.windows.first { $0.title == "Minecraft Galaxy — ARM64" }!
        let root = window.contentView!
        // Capture only our isolated window through WindowServer. NSView bitmap
        // caching can leave artifacts in native text fields and checked controls.
        // Never include another window, request permissions, or activate the app.
        window.setFrameOrigin(NSPoint(x: -10000, y: -10000))
        window.orderBack(nil)
        defer { window.orderOut(nil) }
        try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
        for (index, name) in ["play", "accounts", "settings", "log"].enumerated() {
            let button = descendants(root).compactMap { $0 as? NSButton }
                .first { $0.identifier?.rawValue == "page-\(index)" }!
            button.performClick(nil)
            if name == "log" {
                let log = descendants(root).compactMap { $0 as? NSTextView }.first!
                log.string = """
                Предпросмотр журнала — демонстрационные данные.

                Версия лаунчера: \(MCGLLauncherUpdater.currentVersion)
                Здесь отображаются сообщения загрузки клиента, запуска и работы игры.
                Текст можно выделить и скопировать, а журнал — очистить кнопкой справа.

                Игра для этих скриншотов не запускалась.
                Личные аккаунты, пароли и данные игровой сессии не использовались.
                """
                log.scroll(.zero)
            }
            root.layoutSubtreeIfNeeded()
            window.displayIfNeeded()
            CATransaction.flush()
            RunLoop.current.run(until: Date().addingTimeInterval(0.15))
            precondition(!descendants(root).compactMap { $0 as? NSSecureTextField }.contains { !$0.stringValue.isEmpty })
            guard let snapshot = CGWindowListCreateImage(.null, .optionIncludingWindow,
                CGWindowID(window.windowNumber), [.boundsIgnoreFraming, .bestResolution]) else {
                preconditionFailure("Unable to capture isolated launcher window")
            }
            let bitmap = NSBitmapImageRep(cgImage: snapshot)
            let file = output.appendingPathComponent("launcher-\(name).png")
            try bitmap.representation(using: .png, properties: [:])!.write(to: file, options: .atomic)
            print("SCREENSHOT_PASS \(name) \(bitmap.pixelsWide)x\(bitmap.pixelsHigh) demo-data")
        }
        precondition(!FileManager.default.fileExists(atPath: vault.path))
    }
}
