import Cocoa
import Darwin

final class LauncherBackgroundView: NSView {
    private let artwork: NSImage?

    init(artwork: NSImage?) {
        self.artwork = artwork
        super.init(frame: .zero)
        wantsLayer = true
    }

    required init?(coder: NSCoder) { nil }

    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        NSColor(calibratedRed: 0.055, green: 0.045, blue: 0.035, alpha: 1).setFill()
        bounds.fill()
        if let artwork, artwork.size.width > 0, artwork.size.height > 0 {
            let imageRatio = artwork.size.width / artwork.size.height
            let viewRatio = bounds.width / bounds.height
            let source: NSRect
            if imageRatio > viewRatio {
                let width = artwork.size.height * viewRatio
                source = NSRect(x: (artwork.size.width - width) / 2, y: 0,
                                width: width, height: artwork.size.height)
            } else {
                let height = artwork.size.width / viewRatio
                source = NSRect(x: 0, y: (artwork.size.height - height) / 2,
                                width: artwork.size.width, height: height)
            }
            artwork.draw(in: bounds, from: source, operation: .sourceOver, fraction: 0.78)
        }
        NSColor(calibratedRed: 0.045, green: 0.035, blue: 0.025, alpha: 0.28).setFill()
        bounds.fill(using: .sourceOver)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, NSTextFieldDelegate {
    private let preferences: MCGLLauncherPreferences
    private let updater = MCGLLauncherUpdater()

    init(preferences: MCGLLauncherPreferences = MCGLLauncherPreferences()) {
        self.preferences = preferences
        super.init()
    }
    private lazy var resourcesRoot: URL = {
        guard let resourceURL = Bundle.main.resourceURL else {
            fatalError("Application resources are unavailable")
        }
        return resourceURL
    }()
    private lazy var portSupportURL = resourcesRoot
        .appendingPathComponent("PortSupport", isDirectory: true)
    private lazy var patchToolsURL = resourcesRoot
        .appendingPathComponent("PatchTools", isDirectory: true)
    private lazy var supportRootURL: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        return base.appendingPathComponent("Minecraft Galaxy ARM64", isDirectory: true)
    }()
    private lazy var gameDirectoryURL = supportRootURL
        .appendingPathComponent("mclient-arm64", isDirectory: true)
    private lazy var gameDirectory = gameDirectoryURL.path
    private lazy var javaVMPath = resourcesRoot
        .appendingPathComponent("java8-arm64", isDirectory: true)
        .appendingPathComponent("Home/jre/lib/server/libjvm.dylib").path
    private lazy var javaExecutableURL = resourcesRoot
        .appendingPathComponent("java8-arm64/Home/bin/java")
    private lazy var jarExecutableURL = resourcesRoot
        .appendingPathComponent("java8-arm64/Home/bin/jar")
    private lazy var gameRuntimePath = resourcesRoot
        .appendingPathComponent("MCGL ARM64 Runtime.app", isDirectory: true).path
    private lazy var logPath: String = {
        let base = FileManager.default.urls(for: .libraryDirectory,
                                            in: .userDomainMask).first!
        let directory = base
            .appendingPathComponent("Logs", isDirectory: true)
            .appendingPathComponent("Minecraft Galaxy ARM64", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory,
                                                 withIntermediateDirectories: true)
        return directory.appendingPathComponent("launcher.log").path
    }()

    private var window: NSWindow!
    private var loginField: NSTextField!
    private var passwordField: NSSecureTextField!
    private var rememberLoginButton: NSButton!
    private var fpsPopUp: NSPopUpButton!
    private var launchButton: NSButton!
    private var stopButton: NSButton!
    private var initialMemoryPopUp: NSPopUpButton!
    private var maximumMemoryPopUp: NSPopUpButton!
    private var multicoreButton: NSButton!
    private var graphicsDiagnosticsButton: NSButton!
    private var chunkVboButton: NSButton!
    private var statusLabel: NSTextField!
    private var updateButton: NSButton!
    private var updateStatusLabel: NSTextField!
    private var logView: NSTextView!
    private var gameApplication: NSRunningApplication?
    private var installer: MCGLInstaller?
    private var logChannel: FileHandle?
    private var passwordChannel: FileHandle?
    private var transportPaths: [String] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        buildWindow()
        appendLog("Minecraft Galaxy ARM64 Bootstrap \(MCGLLauncherUpdater.currentVersion) готов к запуску.")
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        checkForLauncherUpdates(silent: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    func applicationWillTerminate(_ notification: Notification) {
        if let gameApplication, !gameApplication.isTerminated {
            gameApplication.terminate()
        }
        cleanupTransport()
    }

    func buildWindow() {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 860, height: 660),
            styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = "Minecraft Galaxy — ARM64"
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.center()
        window.delegate = self
        window.isReleasedWhenClosed = false

        let artworkURL = resourcesRoot.appendingPathComponent("launcher-background.png")
        let content = LauncherBackgroundView(artwork: NSImage(contentsOf: artworkURL))
        content.translatesAutoresizingMaskIntoConstraints = false
        content.appearance = NSAppearance(named: .darkAqua)
        window.contentView = content

        let title = NSTextField(labelWithString: "Minecraft Galaxy")
        title.font = .systemFont(ofSize: 30, weight: .bold)
        title.textColor = .white

        let subtitle = NSTextField(labelWithString:
            "Неофициальный нативный порт для Apple Silicon  ·  ARM64  ·  версия \(MCGLLauncherUpdater.currentVersion)")
        subtitle.textColor = NSColor.white.withAlphaComponent(0.72)

        updateButton = NSButton(title: "Проверить обновление", target: self,
                                action: #selector(checkForLauncherUpdate))
        updateButton.bezelStyle = .rounded
        updateStatusLabel = NSTextField(labelWithString: "GitHub: проверка при запуске")
        updateStatusLabel.font = .systemFont(ofSize: 11)
        updateStatusLabel.textColor = NSColor.white.withAlphaComponent(0.65)
        updateStatusLabel.alignment = .right

        let tabView = NSTabView()
        tabView.controlSize = .large
        tabView.font = .systemFont(ofSize: 13, weight: .medium)

        let playTab = NSTabViewItem(identifier: "play")
        playTab.label = "Играть"
        playTab.view = makePlayTab()
        tabView.addTabViewItem(playTab)

        let settingsTab = NSTabViewItem(identifier: "settings")
        settingsTab.label = "Настройки"
        settingsTab.view = makeSettingsTab()
        tabView.addTabViewItem(settingsTab)

        let logTab = NSTabViewItem(identifier: "log")
        logTab.label = "Журнал"
        logTab.view = makeLogTab()
        tabView.addTabViewItem(logTab)

        let views: [NSView] = [title, subtitle, updateButton,
                               updateStatusLabel, tabView]
        for view in views {
            view.translatesAutoresizingMaskIntoConstraints = false
            content.addSubview(view)
        }

        NSLayoutConstraint.activate([
            title.topAnchor.constraint(equalTo: content.topAnchor, constant: 42),
            title.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 28),
            subtitle.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 2),
            subtitle.leadingAnchor.constraint(equalTo: title.leadingAnchor),

            updateButton.topAnchor.constraint(equalTo: content.topAnchor, constant: 43),
            updateButton.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -28),
            updateButton.widthAnchor.constraint(equalToConstant: 170),
            updateStatusLabel.topAnchor.constraint(equalTo: updateButton.bottomAnchor, constant: 3),
            updateStatusLabel.trailingAnchor.constraint(equalTo: updateButton.trailingAnchor),
            updateStatusLabel.widthAnchor.constraint(equalToConstant: 260),

            tabView.topAnchor.constraint(equalTo: subtitle.bottomAnchor, constant: 18),
            tabView.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 24),
            tabView.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -24),
            tabView.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -22)
        ])

        refreshMemoryControls()
        window.initialFirstResponder = loginField.stringValue.isEmpty ? loginField : passwordField
    }

    private func makeCardView() -> NSView {
        let view = NSView()
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor(calibratedRed: 0.10, green: 0.075,
                                              blue: 0.05, alpha: 0.88).cgColor
        view.layer?.borderColor = NSColor.white.withAlphaComponent(0.13).cgColor
        view.layer?.borderWidth = 1
        view.layer?.cornerRadius = 14
        return view
    }

    private func makePlayTab() -> NSView {
        let card = makeCardView()

        let loginLabel = NSTextField(labelWithString: "Логин")
        loginLabel.textColor = .white
        loginField = NSTextField()
        loginField.placeholderString = "Логин MCGL"
        loginField.bezelStyle = .roundedBezel
        loginField.identifier = NSUserInterfaceItemIdentifier("login")
        loginField.stringValue = preferences.savedLogin
        loginField.delegate = self

        let passwordLabel = NSTextField(labelWithString: "Пароль")
        passwordLabel.textColor = .white
        passwordField = NSSecureTextField()
        passwordField.placeholderString = "Пароль MCGL"
        passwordField.bezelStyle = .roundedBezel
        passwordField.target = self
        passwordField.action = #selector(launchGame)
        passwordField.identifier = NSUserInterfaceItemIdentifier("password")

        rememberLoginButton = NSButton(checkboxWithTitle: "Сохранять логин",
                                      target: self, action: #selector(rememberLoginChanged))
        rememberLoginButton.identifier = NSUserInterfaceItemIdentifier("rememberLogin")
        rememberLoginButton.state = preferences.remembersLogin ? .on : .off
        rememberLoginButton.toolTip = "Сохраняется только логин на этом Mac. При снятии галочки он удаляется из настроек. Пароль не сохраняется."

        launchButton = NSButton(title: "Запустить", target: self, action: #selector(launchGame))
        launchButton.bezelStyle = .rounded
        launchButton.keyEquivalent = "\r"

        stopButton = NSButton(title: "Остановить", target: self, action: #selector(stopGame))
        stopButton.bezelStyle = .rounded
        stopButton.isEnabled = false

        statusLabel = NSTextField(labelWithString: "Готов к запуску")
        statusLabel.textColor = NSColor(calibratedRed: 0.72, green: 0.86, blue: 0.45, alpha: 1)

        let hint = NSTextField(labelWithString: "Оставь лаунчер открытым, пока работает игра.")
        hint.textColor = NSColor.white.withAlphaComponent(0.62)
        hint.font = .systemFont(ofSize: 11)
        hint.maximumNumberOfLines = 2

        let welcome = NSTextField(labelWithString: "Вход в Minecraft Galaxy")
        welcome.font = .systemFont(ofSize: 20, weight: .semibold)
        welcome.textColor = .white

        let views: [NSView] = [welcome, loginLabel, loginField, passwordLabel,
                               passwordField, rememberLoginButton, launchButton,
                               stopButton, statusLabel, hint]
        for view in views {
            view.translatesAutoresizingMaskIntoConstraints = false
            card.addSubview(view)
        }

        NSLayoutConstraint.activate([
            welcome.topAnchor.constraint(equalTo: card.topAnchor, constant: 34),
            welcome.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 42),

            loginLabel.topAnchor.constraint(equalTo: welcome.bottomAnchor, constant: 30),
            loginLabel.leadingAnchor.constraint(equalTo: welcome.leadingAnchor),
            loginField.centerYAnchor.constraint(equalTo: loginLabel.centerYAnchor),
            loginField.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 150),
            loginField.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -42),
            loginField.heightAnchor.constraint(equalToConstant: 28),

            passwordLabel.topAnchor.constraint(equalTo: loginLabel.bottomAnchor, constant: 18),
            passwordLabel.leadingAnchor.constraint(equalTo: welcome.leadingAnchor),
            passwordField.centerYAnchor.constraint(equalTo: passwordLabel.centerYAnchor),
            passwordField.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),
            passwordField.trailingAnchor.constraint(equalTo: loginField.trailingAnchor),
            passwordField.heightAnchor.constraint(equalToConstant: 28),

            rememberLoginButton.topAnchor.constraint(equalTo: passwordField.bottomAnchor, constant: 8),
            rememberLoginButton.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),
            rememberLoginButton.trailingAnchor.constraint(lessThanOrEqualTo: loginField.trailingAnchor),

            launchButton.topAnchor.constraint(equalTo: rememberLoginButton.bottomAnchor, constant: 24),
            launchButton.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),
            launchButton.widthAnchor.constraint(equalToConstant: 145),
            launchButton.heightAnchor.constraint(equalToConstant: 34),
            stopButton.centerYAnchor.constraint(equalTo: launchButton.centerYAnchor),
            stopButton.leadingAnchor.constraint(equalTo: launchButton.trailingAnchor, constant: 10),
            stopButton.widthAnchor.constraint(equalToConstant: 145),
            stopButton.heightAnchor.constraint(equalToConstant: 34),
            statusLabel.centerYAnchor.constraint(equalTo: launchButton.centerYAnchor),
            statusLabel.leadingAnchor.constraint(equalTo: stopButton.trailingAnchor, constant: 15),
            statusLabel.trailingAnchor.constraint(lessThanOrEqualTo: card.trailingAnchor, constant: -36),

            hint.leadingAnchor.constraint(equalTo: welcome.leadingAnchor),
            hint.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -42),
            hint.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -26)
        ])
        return card
    }

    private func makeSettingsTab() -> NSView {
        let card = makeCardView()
        let memoryTitle = sectionLabel("Память Java")
        let initialLabel = valueLabel("Начальная память")
        let maximumLabel = valueLabel("Максимальная память")

        initialMemoryPopUp = memoryPopUp(values: MCGLLauncherPreferences.initialMemoryMBValues)
        initialMemoryPopUp.target = self
        initialMemoryPopUp.action = #selector(initialMemoryChanged)
        maximumMemoryPopUp = memoryPopUp(values: MCGLLauncherPreferences.maximumMemoryMBValues)
        maximumMemoryPopUp.target = self
        maximumMemoryPopUp.action = #selector(maximumMemoryChanged)

        let memoryHint = NSTextField(wrappingLabelWithString:
            "Начальная память резервируется сразу (Xms), максимальная задаёт предел роста (Xmx). Для обычной игры рекомендуются 512 МБ / 2048 МБ.")
        memoryHint.textColor = NSColor.white.withAlphaComponent(0.58)
        memoryHint.font = .systemFont(ofSize: 11)

        let performanceTitle = sectionLabel("Графика и производительность")
        let fpsLabel = valueLabel("Лимит кадров")
        fpsPopUp = NSPopUpButton(frame: .zero, pullsDown: false)
        fpsPopUp.identifier = NSUserInterfaceItemIdentifier("fpsLimit")
        fpsPopUp.addItems(withTitles: MCGLLauncherPreferences.fpsLimits.map {
            $0 == 0 ? "Без ограничения" : "\($0) FPS"
        })
        fpsPopUp.selectItem(at: MCGLLauncherPreferences.fpsLimits.firstIndex(
            of: preferences.fpsLimit) ?? 0)
        fpsPopUp.target = self
        fpsPopUp.action = #selector(fpsLimitChanged)
        fpsPopUp.toolTip = "VSync и настройки клиента могут ограничивать FPS сильнее."

        multicoreButton = NSButton(
            checkboxWithTitle: "Многопоточная оптимизация памяти (G1GC)",
            target: self, action: #selector(performanceOptionsChanged))
        multicoreButton.state = preferences.multicoreMemory ? .on : .off
        multicoreButton.toolTip = "Фоновая сборка мусора использует несколько ядер и уменьшает длинные паузы."

        graphicsDiagnosticsButton = NSButton(
            checkboxWithTitle: "Диагностика графики в журнале",
            target: self, action: #selector(performanceOptionsChanged))
        graphicsDiagnosticsButton.state = preferences.graphicsDiagnostics ? .on : .off
        graphicsDiagnosticsButton.toolTip = "Добавляет техническую статистику каждые 5 секунд. Для обычной игры не требуется."

        chunkVboButton = NSButton(
            checkboxWithTitle: "VBO/VAO-отрисовка чанков",
            target: self, action: #selector(performanceOptionsChanged))
        chunkVboButton.identifier = NSUserInterfaceItemIdentifier("chunkVbo")
        chunkVboButton.state = preferences.chunkVbo ? .on : .off
        chunkVboButton.toolTip = "Ускоренная передача геометрии без снижения качества. Оригинальный рендер сохраняется как запасной путь."

        let transparencyNotice = NSTextField(wrappingLabelWithString:
            "✓ Корректная сортировка прозрачных блоков включена для 1.6.5. Быстрый QuadSort и VBO сохраняются.")
        transparencyNotice.textColor = NSColor(calibratedRed: 0.72, green: 0.86,
                                               blue: 0.45, alpha: 1)
        transparencyNotice.font = .systemFont(ofSize: 12, weight: .medium)

        let views: [NSView] = [memoryTitle, initialLabel, initialMemoryPopUp,
                               maximumLabel, maximumMemoryPopUp, memoryHint,
                               performanceTitle, fpsLabel, fpsPopUp, multicoreButton,
                               graphicsDiagnosticsButton, chunkVboButton,
                               transparencyNotice]
        for view in views { view.translatesAutoresizingMaskIntoConstraints = false; card.addSubview(view) }

        NSLayoutConstraint.activate([
            memoryTitle.topAnchor.constraint(equalTo: card.topAnchor, constant: 24),
            memoryTitle.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 34),
            initialLabel.topAnchor.constraint(equalTo: memoryTitle.bottomAnchor, constant: 18),
            initialLabel.leadingAnchor.constraint(equalTo: memoryTitle.leadingAnchor),
            initialMemoryPopUp.centerYAnchor.constraint(equalTo: initialLabel.centerYAnchor),
            initialMemoryPopUp.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 200),
            initialMemoryPopUp.widthAnchor.constraint(equalToConstant: 190),
            maximumLabel.centerYAnchor.constraint(equalTo: initialLabel.centerYAnchor),
            maximumLabel.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 420),
            maximumMemoryPopUp.centerYAnchor.constraint(equalTo: maximumLabel.centerYAnchor),
            maximumMemoryPopUp.leadingAnchor.constraint(equalTo: maximumLabel.trailingAnchor, constant: 12),
            maximumMemoryPopUp.widthAnchor.constraint(equalToConstant: 180),

            memoryHint.topAnchor.constraint(equalTo: initialMemoryPopUp.bottomAnchor, constant: 10),
            memoryHint.leadingAnchor.constraint(equalTo: memoryTitle.leadingAnchor),
            memoryHint.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -34),

            performanceTitle.topAnchor.constraint(equalTo: memoryHint.bottomAnchor, constant: 26),
            performanceTitle.leadingAnchor.constraint(equalTo: memoryTitle.leadingAnchor),
            fpsLabel.topAnchor.constraint(equalTo: performanceTitle.bottomAnchor, constant: 18),
            fpsLabel.leadingAnchor.constraint(equalTo: performanceTitle.leadingAnchor),
            fpsPopUp.centerYAnchor.constraint(equalTo: fpsLabel.centerYAnchor),
            fpsPopUp.leadingAnchor.constraint(equalTo: initialMemoryPopUp.leadingAnchor),
            fpsPopUp.widthAnchor.constraint(equalToConstant: 190),

            multicoreButton.topAnchor.constraint(equalTo: fpsPopUp.bottomAnchor, constant: 18),
            multicoreButton.leadingAnchor.constraint(equalTo: performanceTitle.leadingAnchor),
            graphicsDiagnosticsButton.topAnchor.constraint(equalTo: multicoreButton.bottomAnchor, constant: 12),
            graphicsDiagnosticsButton.leadingAnchor.constraint(equalTo: multicoreButton.leadingAnchor),
            chunkVboButton.topAnchor.constraint(equalTo: graphicsDiagnosticsButton.bottomAnchor, constant: 12),
            chunkVboButton.leadingAnchor.constraint(equalTo: multicoreButton.leadingAnchor),

            transparencyNotice.leadingAnchor.constraint(equalTo: memoryTitle.leadingAnchor),
            transparencyNotice.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -34),
            transparencyNotice.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -24)
        ])
        return card
    }

    private func makeLogTab() -> NSView {
        let card = makeCardView()
        let title = sectionLabel("Журнал запуска и игры")
        let clearButton = NSButton(title: "Очистить окно", target: self,
                                   action: #selector(clearVisibleLog))
        clearButton.bezelStyle = .rounded
        let scrollView = NSScrollView()
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .noBorder
        scrollView.wantsLayer = true
        scrollView.layer?.cornerRadius = 8
        logView = NSTextView(frame: NSRect(x: 0, y: 0, width: 700, height: 400))
        logView.isEditable = false
        logView.isSelectable = true
        logView.isVerticallyResizable = true
        logView.isHorizontallyResizable = false
        logView.autoresizingMask = [.width]
        logView.textContainer?.containerSize = NSSize(width: 700,
                                                     height: CGFloat.greatestFiniteMagnitude)
        logView.textContainer?.widthTracksTextView = true
        logView.font = .monospacedSystemFont(ofSize: 11.5, weight: .regular)
        logView.backgroundColor = NSColor(calibratedWhite: 0.035, alpha: 0.90)
        logView.textColor = NSColor(calibratedWhite: 0.9, alpha: 1)
        scrollView.documentView = logView
        for view in [title, clearButton, scrollView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            card.addSubview(view)
        }
        NSLayoutConstraint.activate([
            title.topAnchor.constraint(equalTo: card.topAnchor, constant: 22),
            title.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 28),
            clearButton.centerYAnchor.constraint(equalTo: title.centerYAnchor),
            clearButton.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -28),
            scrollView.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 16),
            scrollView.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: clearButton.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -24)
        ])
        return card
    }

    private func sectionLabel(_ text: String) -> NSTextField {
        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: 18, weight: .semibold)
        label.textColor = .white
        return label
    }

    private func valueLabel(_ text: String) -> NSTextField {
        let label = NSTextField(labelWithString: text)
        label.textColor = NSColor.white.withAlphaComponent(0.86)
        return label
    }

    private func memoryPopUp(values: [Int]) -> NSPopUpButton {
        let popUp = NSPopUpButton(frame: .zero, pullsDown: false)
        popUp.addItems(withTitles: values.map { memoryTitle($0) })
        return popUp
    }

    private func memoryTitle(_ memory: Int) -> String {
        memory < 1024 ? "\(memory) МБ" : "\(memory) МБ (\(memory / 1024) ГБ)"
    }

    private func refreshMemoryControls() {
        let maximum = preferences.maximumMemoryMB
        let initial = preferences.initialMemoryMB
        maximumMemoryPopUp.selectItem(at:
            MCGLLauncherPreferences.maximumMemoryMBValues.firstIndex(of: maximum) ?? 1)
        for (index, value) in MCGLLauncherPreferences.initialMemoryMBValues.enumerated() {
            initialMemoryPopUp.item(at: index)?.isEnabled = value <= maximum
        }
        initialMemoryPopUp.selectItem(at:
            MCGLLauncherPreferences.initialMemoryMBValues.firstIndex(of: initial) ?? 0)
    }

    @objc private func initialMemoryChanged() {
        let index = initialMemoryPopUp.indexOfSelectedItem
        guard MCGLLauncherPreferences.initialMemoryMBValues.indices.contains(index) else { return }
        preferences.initialMemoryMB = MCGLLauncherPreferences.initialMemoryMBValues[index]
        refreshMemoryControls()
    }

    @objc private func maximumMemoryChanged() {
        let index = maximumMemoryPopUp.indexOfSelectedItem
        guard MCGLLauncherPreferences.maximumMemoryMBValues.indices.contains(index) else { return }
        preferences.maximumMemoryMB = MCGLLauncherPreferences.maximumMemoryMBValues[index]
        refreshMemoryControls()
    }

    @objc private func rememberLoginChanged() {
        preferences.rememberLogin(rememberLoginButton.state == .on,
                                  login: loginField.stringValue)
    }

    func controlTextDidChange(_ notification: Notification) {
        if notification.object as? NSTextField === loginField {
            preferences.updateLogin(loginField.stringValue)
        }
    }

    @objc private func fpsLimitChanged() {
        let index = fpsPopUp.indexOfSelectedItem
        preferences.fpsLimit = MCGLLauncherPreferences.fpsLimits.indices.contains(index)
            ? MCGLLauncherPreferences.fpsLimits[index] : 0
    }

    @objc private func performanceOptionsChanged() {
        preferences.multicoreMemory = multicoreButton.state == .on
        preferences.graphicsDiagnostics = graphicsDiagnosticsButton.state == .on
        preferences.chunkVbo = chunkVboButton.state == .on
    }

    @objc private func clearVisibleLog() {
        logView.string = ""
    }

    @objc private func checkForLauncherUpdate() {
        checkForLauncherUpdates(silent: false)
    }

    private func checkForLauncherUpdates(silent: Bool) {
        updateButton.isEnabled = false
        updateStatusLabel.stringValue = "GitHub: проверяем релизы…"
        updater.check { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                self.updateButton.isEnabled = true
                switch result {
                case .success(.current):
                    self.updateStatusLabel.stringValue = "Установлена актуальная версия"
                    self.appendLog("Обновление лаунчера: установлена актуальная версия \(MCGLLauncherUpdater.currentVersion).")
                    if !silent {
                        let alert = NSAlert()
                        alert.messageText = "Обновлений пока нет"
                        alert.informativeText = "Установлена актуальная версия \(MCGLLauncherUpdater.currentVersion)."
                        alert.addButton(withTitle: "Хорошо")
                        alert.beginSheetModal(for: self.window)
                    }
                case .success(.available(let release)):
                    self.updateStatusLabel.stringValue = "Доступна версия \(release.version)"
                    self.updateButton.title = "Скачать \(release.version)"
                    self.appendLog("Доступно обновление лаунчера: \(release.version).")
                    self.offerLauncherUpdate(release)
                case .failure(let error):
                    self.updateStatusLabel.stringValue = "Не удалось проверить GitHub"
                    self.appendLog("Проверка обновления лаунчера не удалась: \(error.localizedDescription)")
                    if !silent { self.showUpdateError(error.localizedDescription) }
                }
            }
        }
    }

    private func offerLauncherUpdate(_ release: MCGLGitHubRelease) {
        let alert = NSAlert()
        alert.messageText = "Доступна версия \(release.version)"
        let notes = release.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        alert.informativeText = notes.isEmpty
            ? "Можно скачать новый DMG с официальной страницы порта на GitHub."
            : String(notes.prefix(700))
        alert.addButton(withTitle: release.diskImage == nil ? "Открыть GitHub" : "Скачать DMG")
        alert.addButton(withTitle: "Позже")
        alert.beginSheetModal(for: window) { [weak self] response in
            guard response == .alertFirstButtonReturn, let self else { return }
            if release.diskImage == nil {
                NSWorkspace.shared.open(release.pageURL)
            } else {
                self.downloadLauncherUpdate(release)
            }
        }
    }

    private func downloadLauncherUpdate(_ release: MCGLGitHubRelease) {
        updateButton.isEnabled = false
        updateStatusLabel.stringValue = "Загрузка версии \(release.version)…"
        appendLog("Загрузка DMG версии \(release.version) с GitHub…")
        updater.download(release) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                self.updateButton.isEnabled = true
                switch result {
                case .success(let diskImage):
                    self.updateStatusLabel.stringValue = "DMG сохранён в «Загрузки»"
                    self.appendLog("Обновление загружено и проверено: \(diskImage.path)")
                    NSWorkspace.shared.open(diskImage)
                    let alert = NSAlert()
                    alert.messageText = "Обновление загружено"
                    alert.informativeText = "Открылся новый DMG. Перетащи Minecraft Galaxy ARM64 в «Программы» с заменой, затем снова запусти приложение."
                    alert.addButton(withTitle: "Понятно")
                    alert.beginSheetModal(for: self.window)
                case .failure(let error):
                    self.updateStatusLabel.stringValue = "Ошибка загрузки"
                    self.appendLog("Не удалось загрузить обновление: \(error.localizedDescription)")
                    self.showUpdateError(error.localizedDescription)
                }
            }
        }
    }

    private func showUpdateError(_ message: String) {
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "Не удалось обновить лаунчер"
        alert.informativeText = message
        alert.addButton(withTitle: "Открыть GitHub")
        alert.addButton(withTitle: "Закрыть")
        alert.beginSheetModal(for: window) { response in
            if response == .alertFirstButtonReturn {
                NSWorkspace.shared.open(MCGLLauncherUpdater.releasesPage)
            }
        }
    }

    @objc private func launchGame() {
        guard gameApplication == nil else {
            appendLog("Клиент уже запущен.")
            return
        }

        let login = loginField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        preferences.updateLogin(login)
        let password = passwordField.stringValue
        guard !login.isEmpty, !password.isEmpty else {
            showError("Введи логин и пароль MCGL.")
            return
        }

        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: javaVMPath) else {
            showError("Не найдена подготовленная ARM64 Java 8.")
            return
        }

        prepareOfficialClientAndLaunch(login: login, password: password)
    }

    private func prepareOfficialClientAndLaunch(login: String, password: String) {
        guard installer == nil else {
            appendLog("Установка клиента уже выполняется.")
            return
        }

        let profileExists = FileManager.default.fileExists(atPath: gameDirectory)
        appendLog(profileExists
            ? "Проверка обновлений официального клиента MCGL…"
            : "Первый запуск: оригинальные файлы будут загружены с официального зеркала MCGL.")
        statusLabel.stringValue = profileExists ? "Проверка обновлений…" : "Подготовка загрузки…"
        launchButton.isEnabled = false
        stopButton.isEnabled = false

        let installer = MCGLInstaller(
            supportRootURL: supportRootURL,
            gameDirectoryURL: gameDirectoryURL,
            portSupportURL: portSupportURL,
            patchToolsURL: patchToolsURL,
            javaExecutableURL: javaExecutableURL,
            jarExecutableURL: jarExecutableURL)
        self.installer = installer
        installer.install(progress: { [weak self] message in
            DispatchQueue.main.async {
                guard let self else { return }
                self.statusLabel.stringValue = message
                self.appendLog(message)
            }
        }, completion: { [weak self] result in
            guard let self else { return }
            self.installer = nil
            switch result {
            case .success:
                self.appendLog("Официальные файлы проверены; ARM64-патчи готовы.")
                self.launchPreparedGame(login: login, password: password)
            case .failure(let error):
                self.resetLaunchControls(status: "Ошибка обновления")
                self.showError("Не удалось подготовить официальный клиент: \(error.localizedDescription)")
            }
        })
    }

    private func launchPreparedGame(login: String, password: String) {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: gameDirectory + "/Minecraft.jar") else {
            showError("Не найден скачанный Minecraft.jar.")
            return
        }
        guard fileManager.fileExists(atPath: gameDirectory + "/mcgl-nativewindow-patch.jar") else {
            showError("Не найден патч отдельного игрового окна.")
            return
        }
        guard fileManager.fileExists(atPath: gameRuntimePath + "/Contents/Info.plist"),
              fileManager.isExecutableFile(atPath: gameRuntimePath + "/Contents/MacOS/MCGL ARM64 Runtime") else {
            showError("Не найдено macOS-приложение игрового клиента.")
            return
        }

        stopStaleClients()

        let token = UUID().uuidString.lowercased()
        let passwordFIFO = "/private/tmp/mcgl-\(token)-password.fifo"
        let logFIFO = "/private/tmp/mcgl-\(token)-log.fifo"
        do {
            try prepareTransport(password: password, passwordPath: passwordFIFO, logPath: logFIFO)
        } catch {
            showError("Не удалось создать защищённый канал: \(error.localizedDescription)")
            return
        }

        appendLog("Запуск Minecraft Galaxy в полностью нативном ARM64-режиме…")
        statusLabel.stringValue = "Запуск клиента…"
        launchButton.isEnabled = false
        stopButton.isEnabled = true

        let configuration = NSWorkspace.OpenConfiguration()
        configuration.arguments = [login, passwordFIFO, logFIFO, gameDirectory]
        let initialMemoryMB = preferences.initialMemoryMB
        let maximumMemoryMB = preferences.maximumMemoryMB
        let multicoreEnabled = multicoreButton.state == .on
        let graphicsDiagnosticsEnabled = graphicsDiagnosticsButton.state == .on
        let fpsLimit = preferences.fpsLimit
        preferences.multicoreMemory = multicoreEnabled
        preferences.graphicsDiagnostics = graphicsDiagnosticsEnabled
        preferences.chunkVbo = chunkVboButton.state == .on
        var runtimeEnvironment = ProcessInfo.processInfo.environment
        runtimeEnvironment["MCGL_INITIAL_MEMORY_MB"] = String(initialMemoryMB)
        runtimeEnvironment["MCGL_MEMORY_MB"] = String(maximumMemoryMB)
        runtimeEnvironment.removeValue(forKey: "MCGL_PREALLOCATE_MEMORY")
        runtimeEnvironment["MCGL_MULTICORE_MEMORY"] = multicoreEnabled ? "1" : "0"
        runtimeEnvironment["MCGL_GRAPHICS_DIAGNOSTICS"] = graphicsDiagnosticsEnabled ? "1" : "0"
        runtimeEnvironment["MCGL_FPS_LIMIT"] = String(fpsLimit)
        runtimeEnvironment["MCGL_CHUNK_VBO"] = preferences.chunkVbo ? "1" : "0"
        configuration.environment = runtimeEnvironment
        configuration.activates = true
        configuration.createsNewApplicationInstance = true
        configuration.addsToRecentItems = false
        initialMemoryPopUp.isEnabled = false
        maximumMemoryPopUp.isEnabled = false
        multicoreButton.isEnabled = false
        graphicsDiagnosticsButton.isEnabled = false
        chunkVboButton.isEnabled = false
        fpsPopUp.isEnabled = false
        appendLog(fpsLimit == 0 ? "Дополнительный лимит FPS выключен."
            : "Лимит FPS: \(fpsLimit). VSync и настройки игры могут ограничивать FPS сильнее.")
        appendLog("Память Java: начальная \(initialMemoryMB) МБ; максимальная \(maximumMemoryMB) МБ.")
        appendLog(multicoreEnabled
            ? "Экспериментальный профиль памяти включён: G1GC использует фоновые ядра."
            : "Экспериментальный профиль памяти выключен: используется стандартный ParallelGC.")
        appendLog(graphicsDiagnosticsEnabled
            ? "Диагностика графики включена: статистика кадра будет выводиться каждые 5 секунд."
            : "Диагностика графики выключена.")
        appendLog(preferences.chunkVbo
            ? "VBO-отрисовка включена: ускорение VAO включится при поддержке драйвером; запасные пути сохранены."
            : "Используется прежняя отрисовка чанков (VBO выключен).")

        NSWorkspace.shared.openApplication(
            at: URL(fileURLWithPath: gameRuntimePath),
            configuration: configuration
        ) { [weak self] application, error in
            DispatchQueue.main.async {
                guard let self else { return }
                guard let application, error == nil else {
                    self.cleanupTransport()
                    self.resetLaunchControls(status: "Ошибка запуска")
                    self.showError("Не удалось открыть игровое macOS-приложение: \(error?.localizedDescription ?? "неизвестная ошибка")")
                    return
                }

                guard application.processIdentifier > 0, !application.isTerminated else {
                    self.cleanupTransport()
                    self.resetLaunchControls(status: "Ошибка запуска")
                    self.showError("Игровой runtime завершился до получения PID.")
                    return
                }

                self.gameApplication = application
                self.passwordField.stringValue = ""
                self.appendLog("Игровое приложение запущено, PID: \(application.processIdentifier).")
                self.appendLog("Пароль передан по одноразовому каналу; в журнал он не записан.")
                self.releasePasswordChannel(afterRuntimeOpens: passwordFIFO)
                self.monitorTermination(of: application)

                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self, weak application] in
                    guard let self, let application,
                          self.gameApplication?.processIdentifier == application.processIdentifier,
                          !application.isTerminated else { return }
                    NSApp.deactivate()
                    self.appendLog("Лаунчер освободил фокус для игрового окна.")
                }

                DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self, weak application] in
                    guard let self, let application,
                          self.gameApplication?.processIdentifier == application.processIdentifier,
                          !application.isTerminated else { return }
                    let activated = application.activate(options: [.activateAllWindows])
                    self.appendLog(activated
                        ? "Окна игрового macOS-приложения выведены на передний план."
                        : "macOS не подтвердила вывод игровых окон на передний план.")
                }
            }
        }
    }

    @objc private func stopGame() {
        guard let application = gameApplication, !application.isTerminated else { return }
        appendLog("Остановка клиента…")
        application.terminate()
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak application] in
            guard let application, !application.isTerminated else { return }
            application.forceTerminate()
        }
    }

    private func prepareTransport(password: String, passwordPath: String, logPath: String) throws {
        cleanupTransport()

        let permissions = mode_t(S_IRUSR | S_IWUSR)
        var createdPaths: [String] = []
        for path in [passwordPath, logPath] {
            guard Darwin.mkfifo(path, permissions) == 0 else {
                for createdPath in createdPaths { Darwin.unlink(createdPath) }
                throw posixError()
            }
            createdPaths.append(path)
        }

        let logDescriptor = Darwin.open(logPath, O_RDWR | O_NONBLOCK | O_NOFOLLOW)
        guard logDescriptor >= 0 else {
            for path in createdPaths { Darwin.unlink(path) }
            throw posixError()
        }

        let passwordDescriptor = Darwin.open(passwordPath, O_RDWR | O_NONBLOCK | O_NOFOLLOW)
        guard passwordDescriptor >= 0 else {
            Darwin.close(logDescriptor)
            for path in createdPaths { Darwin.unlink(path) }
            throw posixError()
        }

        transportPaths = createdPaths
        let logHandle = FileHandle(fileDescriptor: logDescriptor, closeOnDealloc: true)
        let passwordHandle = FileHandle(fileDescriptor: passwordDescriptor, closeOnDealloc: true)
        logChannel = logHandle
        passwordChannel = passwordHandle

        logHandle.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            DispatchQueue.main.async {
                self?.appendLog(text.trimmingCharacters(in: .newlines))
            }
        }

        do {
            try passwordHandle.write(contentsOf: Data((password + "\n").utf8))
        } catch {
            cleanupTransport()
            throw error
        }
    }

    private func releasePasswordChannel(afterRuntimeOpens path: String) {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            for _ in 0..<100 {
                if Darwin.access(path, F_OK) != 0 { break }
                usleep(100_000)
            }
            DispatchQueue.main.async {
                guard let self, self.transportPaths.contains(path) else { return }
                try? self.passwordChannel?.close()
                self.passwordChannel = nil
            }
        }
    }

    private func monitorTermination(of application: NSRunningApplication) {
        DispatchQueue.global(qos: .utility).async { [weak self, weak application] in
            guard let application else { return }
            while !application.isTerminated {
                usleep(200_000)
            }
            DispatchQueue.main.async {
                guard let self,
                      self.gameApplication?.processIdentifier == application.processIdentifier else { return }
                self.appendLog("Игровой процесс завершён.")
                self.cleanupTransport()
                self.gameApplication = nil
                self.resetLaunchControls(status: "Клиент остановлен")
            }
        }
    }

    private func cleanupTransport() {
        logChannel?.readabilityHandler = nil
        try? logChannel?.close()
        try? passwordChannel?.close()
        logChannel = nil
        passwordChannel = nil
        for path in transportPaths { Darwin.unlink(path) }
        transportPaths.removeAll()
    }

    private func resetLaunchControls(status: String) {
        statusLabel.stringValue = status
        launchButton.isEnabled = true
        stopButton.isEnabled = false
        initialMemoryPopUp.isEnabled = true
        maximumMemoryPopUp.isEnabled = true
        multicoreButton.isEnabled = true
        graphicsDiagnosticsButton.isEnabled = true
        chunkVboButton.isEnabled = true
        fpsPopUp.isEnabled = true
    }

    private func posixError() -> NSError {
        NSError(domain: NSPOSIXErrorDomain, code: Int(errno), userInfo: nil)
    }

    private func stopStaleClients() {
        let staleApplications = NSRunningApplication.runningApplications(
            withBundleIdentifier: "community.mcgl.arm64-port.runtime")
        guard !staleApplications.isEmpty else { return }

        for application in staleApplications where !application.isTerminated {
            application.terminate()
        }
        for _ in 0..<20 where staleApplications.contains(where: { !$0.isTerminated }) {
            usleep(100_000)
        }
        for application in staleApplications where !application.isTerminated {
            application.forceTerminate()
        }
        appendLog("Остановлен зависший процесс из предыдущего запуска.")
    }

    private func showError(_ message: String) {
        statusLabel.stringValue = message
        appendLog("ОШИБКА: " + message)
        NSSound.beep()
    }

    private func appendLog(_ message: String) {
        guard !message.isEmpty else { return }
        let line = message + "\n"
        logView.textStorage?.append(NSAttributedString(
            string: line,
            attributes: [.foregroundColor: NSColor(calibratedWhite: 0.9, alpha: 1),
                         .font: NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)]
        ))
        logView.scrollToEndOfDocument(nil)

        let timestamp = ISO8601DateFormatter().string(from: Date())
        let record = Data((timestamp + " " + line).utf8)
        if !FileManager.default.fileExists(atPath: logPath) {
            FileManager.default.createFile(atPath: logPath, contents: nil)
        }
        if let handle = try? FileHandle(forWritingTo: URL(fileURLWithPath: logPath)) {
            do {
                try handle.seekToEnd()
                try handle.write(contentsOf: record)
                try handle.close()
            } catch {
                try? handle.close()
            }
        }
    }
}

#if !MCGL_LAUNCHER_TEST
@main
struct MCGLLauncherMain {
    static func main() {
        let application = NSApplication.shared
        let delegate = AppDelegate()
        application.delegate = delegate
        application.setActivationPolicy(.regular)
        application.run()
    }
}
#endif
