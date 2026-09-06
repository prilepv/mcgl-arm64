import Cocoa
import Darwin

enum GalaxyTheme {
    static let ink = NSColor(srgbRed: 0.025, green: 0.024, blue: 0.055, alpha: 1)
    static let panel = NSColor(srgbRed: 0.045, green: 0.041, blue: 0.085, alpha: 0.96)
    static let blue = NSColor(srgbRed: 0.32, green: 0.27, blue: 0.76, alpha: 1)
    static let cyan = NSColor(srgbRed: 0.68, green: 0.69, blue: 1, alpha: 1)
    static let muted = NSColor(srgbRed: 0.64, green: 0.64, blue: 0.77, alpha: 1)
    static let line = NSColor(srgbRed: 0.46, green: 0.43, blue: 0.70, alpha: 0.26)
}

enum MCGLLaunchState: CaseIterable {
    case ready, preparing, launching, running, stopping

    var title: String {
        switch self {
        case .ready: return "Запустить игру"
        case .preparing: return "Подготовка…"
        case .launching: return "Запуск…"
        case .running: return "Остановить игру"
        case .stopping: return "Остановка…"
        }
    }

    var symbol: String {
        switch self {
        case .ready: return "play.fill"
        case .running, .stopping: return "stop.fill"
        case .preparing, .launching: return "hourglass"
        }
    }

    var isActionEnabled: Bool { self == .ready || self == .running }
    // Return may start a ready game, but must never unexpectedly stop a live one.
    var acceptsReturn: Bool { self == .ready }
}

/// Native NSButton behavior (keyboard, accessibility and actions), custom paint only.
final class GalaxyButton: NSButton {
    enum Style { case primary, secondary, navigation }
    var style: Style = .secondary
    var symbol: String?
    var selected = false { didSet { needsDisplay = true } }
    override var isEnabled: Bool { didSet { needsDisplay = true } }

    func contentLayout(textSize: NSSize) -> (icon: NSRect, text: NSPoint) {
        let iconWidth: CGFloat = symbol == nil ? 0 : 18
        // Icon-only actions have no label gap; otherwise they sit 4 pt off centre.
        let gap: CGFloat = symbol != nil && !title.isEmpty ? 8 : 0
        let contentWidth = iconWidth + gap + textSize.width
        let x = style == .navigation ? bounds.minX + 16 : bounds.midX - contentWidth / 2
        return (NSRect(x: x, y: bounds.midY - 9, width: iconWidth, height: 18),
                NSPoint(x: x + iconWidth + gap, y: bounds.midY - textSize.height / 2))
    }

    override func draw(_ dirtyRect: NSRect) {
        let shape = NSBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1),
                                 xRadius: 10, yRadius: 10)
        let fill: NSColor = style == .primary ? GalaxyTheme.blue
            : selected ? GalaxyTheme.blue.withAlphaComponent(0.19)
            : style == .navigation ? .clear : NSColor.white.withAlphaComponent(0.055)
        fill.withAlphaComponent(isEnabled ? fill.alphaComponent : fill.alphaComponent * 0.4).setFill()
        shape.fill()
        if isHighlighted {
            NSColor.white.withAlphaComponent(0.10).setFill()
            shape.fill()
        }
        if style != .navigation || selected {
            (selected ? GalaxyTheme.blue.withAlphaComponent(0.55) : GalaxyTheme.line).setStroke()
            shape.lineWidth = 1
            shape.stroke()
        }
        let color = (selected ? GalaxyTheme.cyan : NSColor.white)
            .withAlphaComponent(isEnabled ? 1 : 0.36)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: style == .primary ? 15 : 13,
                                     weight: .semibold), .foregroundColor: color
        ]
        let text = NSAttributedString(string: title, attributes: attrs)
        let layout = contentLayout(textSize: text.size())
        if let symbol, let icon = NSImage(systemSymbolName: symbol, accessibilityDescription: nil) {
            let tinted = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { rect in
                icon.draw(in: rect)
                color.setFill()
                rect.fill(using: .sourceIn)
                return true
            }
            tinted.draw(in: layout.icon)
        }
        if !title.isEmpty { text.draw(at: layout.text) }
        if window?.firstResponder === self {
            GalaxyTheme.cyan.setStroke()
            shape.lineWidth = 2
            shape.stroke()
        }
    }
}

/// AppKit can retain a horizontal scroll offset when text arrives in a hidden tab.
/// The log wraps lines, so it only ever needs vertical scrolling.
final class VerticalLogClipView: NSClipView {
    override func constrainBoundsRect(_ proposedBounds: NSRect) -> NSRect {
        var result = super.constrainBoundsRect(proposedBounds)
        result.origin.x = 0
        return result
    }
}

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
        GalaxyTheme.ink.setFill()
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
            artwork.draw(in: bounds, from: source, operation: .sourceOver, fraction: 1)
        }
        NSGradient(starting: GalaxyTheme.ink.withAlphaComponent(0.70),
                   ending: GalaxyTheme.ink.withAlphaComponent(0.05))?.draw(in: bounds, angle: 0)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, NSTextFieldDelegate {
    private let preferences: MCGLLauncherPreferences
    private let resourcesOverride: URL?
    private let updater = MCGLLauncherUpdater()
    private let accounts: MCGLAccountStore
    private let accountService: MCGLAccountFetching
    private let passwordStore: MCGLPasswordStore

    init(preferences: MCGLLauncherPreferences = MCGLLauncherPreferences(),
         resourcesRoot: URL? = nil,
         accountService: MCGLAccountFetching = MCGLAccountService(),
         passwordStore: MCGLPasswordStore = MCGLPasswordStore()) {
        self.preferences = preferences
        self.resourcesOverride = resourcesRoot
        self.accounts = MCGLAccountStore(preferences: preferences)
        self.accountService = accountService
        self.passwordStore = passwordStore
        super.init()
    }
    private lazy var resourcesRoot: URL = {
        if let resourcesOverride { return resourcesOverride }
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
    private var tabView: NSTabView!
    private var navigationButtons: [GalaxyButton] = []
    private var loginField: NSTextField!
    private var passwordField: NSSecureTextField!
    private var rememberPasswordButton: NSButton!
    private var accountList: NSStackView!
    private var accountSummary: MCGLAccountCard!
    private var accountCards: [UUID: MCGLAccountCard] = [:]
    private var accountEditingButtons: [NSButton] = []
    private var accountStatus: [UUID: String] = [:]
    private var accountRequests: [UUID: MCGLAccountRequest] = [:]
    private var accountRequestTokens: [UUID: UUID] = [:]
    private var accountQueue: [UUID] = []
    private var accountAttempts: [UUID: Date] = [:]
    private var accountCountLabel: NSTextField!
    private var fpsPopUp: NSPopUpButton!
    private var launchButton: GalaxyButton!
    private var launchState: MCGLLaunchState = .ready {
        didSet { refreshLaunchButton() }
    }
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
        if let selected = accounts.selected { queueAccountRefresh(selected.id) }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    func applicationWillTerminate(_ notification: Notification) {
        if let gameApplication, !gameApplication.isTerminated {
            gameApplication.terminate()
        }
        cleanupTransport()
        accountQueue.removeAll()
        accountRequestTokens.removeAll()
        accountRequests.values.forEach { $0.cancel() }
        accountRequests.removeAll()
    }

    func buildWindow() {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1000, height: 720),
            styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView],
            backing: .buffered, defer: false)
        window.title = "Minecraft Galaxy — ARM64"
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.center()
        window.delegate = self
        window.isReleasedWhenClosed = false

        let content = LauncherBackgroundView(
            artwork: NSImage(contentsOf: resourcesRoot.appendingPathComponent("launcher-background.png")))
        content.appearance = NSAppearance(named: .darkAqua)
        window.contentView = content

        let sidebar = NSView()
        sidebar.wantsLayer = true
        sidebar.layer?.backgroundColor = GalaxyTheme.ink.withAlphaComponent(0.94).cgColor
        let divider = NSView()
        divider.wantsLayer = true
        divider.layer?.backgroundColor = GalaxyTheme.line.cgColor
        let icon = NSImageView()
        // The sidebar uses the transparent mark, not the white Dock/Finder tile.
        icon.identifier = NSUserInterfaceItemIdentifier("launcher-brand-symbol")
        icon.image = NSImage(contentsOf: resourcesRoot.appendingPathComponent("app-icon-symbol.png"))
        icon.imageScaling = .scaleProportionallyUpOrDown
        let brand = label("GALAXY", size: 22, weight: .bold)
        let brandDetail = label("APPLE SILICON", size: 10, weight: .medium, color: GalaxyTheme.muted)
        brandDetail.attributedStringValue = NSAttributedString(
            string: brandDetail.stringValue,
            attributes: [.kern: 2, .font: brandDetail.font!, .foregroundColor: GalaxyTheme.muted])
        let navigationTitle = label("ЛАУНЧЕР", size: 10, weight: .semibold, color: GalaxyTheme.muted)

        let titles = ["Играть", "Аккаунты", "Настройки", "Журнал"]
        let symbols = ["play.fill", "person.2.fill", "slider.horizontal.3", "text.alignleft"]
        for index in 0..<titles.count {
            let button = actionButton(titles[index], symbol: symbols[index],
                                      action: #selector(selectPage(_:)), style: .navigation)
            button.tag = index
            button.identifier = NSUserInterfaceItemIdentifier("page-\(index)")
            button.keyEquivalent = "\(index + 1)"
            button.keyEquivalentModifierMask = [.command]
            navigationButtons.append(button)
        }
        let navigation = verticalStack(navigationButtons, spacing: 8)
        let version = label("Версия \(MCGLLauncherUpdater.currentVersion)",
                            size: 12, weight: .semibold)
        let portLabel = label("Неофициальный ARM64-порт", size: 10, color: GalaxyTheme.muted)
        let repositoryButton = actionButton("Проект на GitHub", symbol: "arrow.up.right",
                                            action: #selector(openRepository))

        updateButton = actionButton("Проверить обновления", symbol: "arrow.clockwise",
                                     action: #selector(checkForLauncherUpdate))
        updateStatusLabel = label("Проверка при запуске", size: 11, color: GalaxyTheme.muted)
        updateStatusLabel.alignment = .right

        tabView = NSTabView()
        tabView.tabViewType = .noTabsNoBorder
        tabView.drawsBackground = false
        for (index, view) in [makePlayTab(), makeAccountsTab(), makeSettingsTab(), makeLogTab()].enumerated() {
            let item = NSTabViewItem(identifier: ["play", "accounts", "settings", "log"][index])
            item.label = titles[index]
            item.view = view
            tabView.addTabViewItem(item)
        }
        statusLabel = NSTextField(wrappingLabelWithString: "Готов к запуску")
        statusLabel.font = .systemFont(ofSize: 12, weight: .medium)
        statusLabel.textColor = GalaxyTheme.cyan
        statusLabel.maximumNumberOfLines = 2
        statusLabel.identifier = NSUserInterfaceItemIdentifier("launch-status")
        let statusLine = NSView()
        statusLine.wantsLayer = true
        statusLine.layer?.backgroundColor = GalaxyTheme.line.cgColor

        add([sidebar, divider, updateButton, updateStatusLabel,
             tabView, statusLine, statusLabel], to: content)
        add([icon, brand, brandDetail, navigationTitle, navigation, version,
             portLabel, repositoryButton], to: sidebar)
        NSLayoutConstraint.activate([
            sidebar.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            sidebar.topAnchor.constraint(equalTo: content.topAnchor),
            sidebar.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            sidebar.widthAnchor.constraint(equalToConstant: 196),
            divider.leadingAnchor.constraint(equalTo: sidebar.trailingAnchor),
            divider.widthAnchor.constraint(equalToConstant: 1),
            divider.topAnchor.constraint(equalTo: content.topAnchor),
            divider.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            icon.leadingAnchor.constraint(equalTo: sidebar.leadingAnchor, constant: 24),
            icon.topAnchor.constraint(equalTo: sidebar.topAnchor, constant: 55),
            icon.widthAnchor.constraint(equalToConstant: 68),
            icon.heightAnchor.constraint(equalToConstant: 68),
            brand.leadingAnchor.constraint(equalTo: icon.leadingAnchor, constant: 3),
            brand.topAnchor.constraint(equalTo: icon.bottomAnchor, constant: 12),
            brandDetail.leadingAnchor.constraint(equalTo: brand.leadingAnchor),
            brandDetail.topAnchor.constraint(equalTo: brand.bottomAnchor, constant: 4),
            navigationTitle.leadingAnchor.constraint(equalTo: brand.leadingAnchor),
            navigationTitle.topAnchor.constraint(equalTo: brandDetail.bottomAnchor, constant: 44),
            navigation.leadingAnchor.constraint(equalTo: sidebar.leadingAnchor, constant: 16),
            navigation.trailingAnchor.constraint(equalTo: sidebar.trailingAnchor, constant: -16),
            navigation.topAnchor.constraint(equalTo: navigationTitle.bottomAnchor, constant: 12),
            version.leadingAnchor.constraint(equalTo: brand.leadingAnchor),
            version.bottomAnchor.constraint(equalTo: portLabel.topAnchor, constant: -6),
            portLabel.leadingAnchor.constraint(equalTo: brand.leadingAnchor),
            portLabel.bottomAnchor.constraint(equalTo: repositoryButton.topAnchor, constant: -20),
            repositoryButton.leadingAnchor.constraint(equalTo: navigation.leadingAnchor),
            repositoryButton.trailingAnchor.constraint(equalTo: navigation.trailingAnchor),
            repositoryButton.bottomAnchor.constraint(equalTo: sidebar.bottomAnchor, constant: -24),
            repositoryButton.heightAnchor.constraint(equalToConstant: 36),

            updateButton.topAnchor.constraint(equalTo: content.topAnchor, constant: 34),
            updateButton.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -32),
            updateButton.widthAnchor.constraint(equalToConstant: 196),
            updateButton.heightAnchor.constraint(equalToConstant: 34),
            updateStatusLabel.topAnchor.constraint(equalTo: updateButton.bottomAnchor, constant: 6),
            updateStatusLabel.trailingAnchor.constraint(equalTo: updateButton.trailingAnchor),
            updateStatusLabel.widthAnchor.constraint(equalToConstant: 320),
            tabView.leadingAnchor.constraint(equalTo: sidebar.trailingAnchor, constant: 32),
            tabView.trailingAnchor.constraint(equalTo: updateButton.trailingAnchor),
            tabView.topAnchor.constraint(equalTo: content.topAnchor, constant: 110),
            tabView.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -70),
            statusLine.leadingAnchor.constraint(equalTo: tabView.leadingAnchor),
            statusLine.trailingAnchor.constraint(equalTo: tabView.trailingAnchor),
            statusLine.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -58),
            statusLine.heightAnchor.constraint(equalToConstant: 1),
            statusLabel.leadingAnchor.constraint(equalTo: tabView.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: tabView.trailingAnchor),
            statusLabel.centerYAnchor.constraint(equalTo: content.bottomAnchor, constant: -30)
        ])
        for button in navigationButtons {
            button.widthAnchor.constraint(equalTo: navigation.widthAnchor).isActive = true
            button.heightAnchor.constraint(equalToConstant: 46).isActive = true
        }
        refreshMemoryControls()
        rebuildAccounts()
        showPage(0)
        restoreSelectedPassword()
        window.initialFirstResponder = loginField.stringValue.isEmpty ? loginField : passwordField
    }

    @objc private func selectPage(_ sender: NSButton) { showPage(sender.tag) }

    private func showPage(_ index: Int) {
        guard (0..<tabView.numberOfTabViewItems).contains(index) else { return }
        tabView.selectTabViewItem(at: index)
        for button in navigationButtons {
            button.selected = button.tag == index
            button.setAccessibilityValue(button.selected ? "Выбрано" : "")
        }
        refreshLaunchButton()
        if index == 1 { accounts.accounts.forEach { queueAccountRefresh($0.id) } }
    }

    private func refreshLaunchButton() {
        guard let launchButton, let tabView else { return }
        launchButton.title = launchState.title
        launchButton.setAccessibilityLabel(launchState.title)
        launchButton.symbol = launchState.symbol
        launchButton.style = launchState == .running ? .secondary : .primary
        launchButton.isEnabled = launchState.isActionEnabled
        let isPlayPage = tabView.selectedTabViewItem?.identifier as? String == "play"
        let acceptsReturn = isPlayPage && launchState.acceptsReturn
        launchButton.keyEquivalent = acceptsReturn ? "\r" : ""
        window.defaultButtonCell = acceptsReturn ? launchButton.cell as? NSButtonCell : nil
        launchButton.needsDisplay = true
        refreshAccountControls()
    }

#if MCGL_LAUNCHER_TEST
    // UI-state tests cannot spawn a game, authenticate or run the installer.
    func previewLaunchStateForTesting(_ state: MCGLLaunchState) {
        launchState = state
    }
#endif

    @objc private func openRepository() {
        NSWorkspace.shared.open(URL(string: "https://github.com/prilepv/mcgl-arm64")!)
    }

    private func makeCardView() -> NSView {
        let view = NSView()
        view.wantsLayer = true
        view.layer?.backgroundColor = GalaxyTheme.panel.cgColor
        view.layer?.borderColor = GalaxyTheme.line.cgColor
        view.layer?.borderWidth = 1
        view.layer?.cornerRadius = 16
        return view
    }

    private func makePlayTab() -> NSView {
        let page = NSView()
        let edition = label("НОВАЯ ОРБИТА  /  \(MCGLLauncherUpdater.currentVersion)",
                            size: 10, weight: .semibold, color: GalaxyTheme.cyan)
        let hero = label("Галактика\nначинается здесь.", size: 40, weight: .bold)
        hero.maximumNumberOfLines = 2
        let description = label("Твой Minecraft Galaxy. Нативно на Mac.",
                                size: 13, color: GalaxyTheme.muted)
        let card = makeCardView()
        let welcome = label("Вход в игру", size: 19, weight: .semibold)
        let loginLabel = label("Логин", size: 11, weight: .medium, color: GalaxyTheme.muted)
        loginField = NSTextField()
        loginField.placeholderString = "Логин MCGL"
        loginField.identifier = NSUserInterfaceItemIdentifier("login")
        loginField.stringValue = accounts.selected?.nickname ?? ""
        loginField.delegate = self
        styleField(loginField)
        loginField.setAccessibilityLabel("Логин MCGL")

        let passwordLabel = label("Пароль", size: 11, weight: .medium, color: GalaxyTheme.muted)
        passwordField = NSSecureTextField()
        passwordField.placeholderString = "Пароль MCGL"
        passwordField.target = self
        passwordField.action = #selector(launchGame)
        passwordField.identifier = NSUserInterfaceItemIdentifier("password")
        passwordField.delegate = self
        styleField(passwordField)
        passwordField.setAccessibilityLabel("Пароль MCGL")
        rememberPasswordButton = NSButton(checkboxWithTitle: "Сохранить пароль на этом Mac",
                                         target: self, action: #selector(rememberPasswordChanged))
        rememberPasswordButton.identifier = NSUserInterfaceItemIdentifier("remember-password")
        styleCheckbox(rememberPasswordButton)
        accountSummary = MCGLAccountCard(resources: resourcesRoot, compact: true)
        accountSummary.identifier = NSUserInterfaceItemIdentifier("account-summary")

        launchButton = actionButton("Запустить игру", symbol: "play.fill",
                                     action: #selector(toggleGame), style: .primary)
        launchButton.identifier = NSUserInterfaceItemIdentifier("launch")
        let hint = label("Оставь лаунчер открытым, пока работает игра.",
                         size: 10, color: GalaxyTheme.muted)
        add([edition, hero, description, card, accountSummary], to: page)
        add([welcome, loginLabel, loginField, passwordLabel, passwordField,
             rememberPasswordButton, launchButton, hint], to: card)
        NSLayoutConstraint.activate([
            edition.topAnchor.constraint(equalTo: page.topAnchor, constant: 4),
            edition.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            hero.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            hero.topAnchor.constraint(equalTo: edition.bottomAnchor, constant: 10),
            description.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            description.topAnchor.constraint(equalTo: hero.bottomAnchor, constant: 10),
            card.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            card.topAnchor.constraint(equalTo: page.topAnchor, constant: 176),
            card.widthAnchor.constraint(equalToConstant: 398),
            card.bottomAnchor.constraint(equalTo: hint.bottomAnchor, constant: 24),
            card.bottomAnchor.constraint(lessThanOrEqualTo: page.bottomAnchor),
            welcome.topAnchor.constraint(equalTo: card.topAnchor, constant: 21),
            welcome.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 24),
            loginLabel.topAnchor.constraint(equalTo: welcome.bottomAnchor, constant: 16),
            loginLabel.leadingAnchor.constraint(equalTo: welcome.leadingAnchor),
            loginField.topAnchor.constraint(equalTo: loginLabel.bottomAnchor, constant: 6),
            loginField.leadingAnchor.constraint(equalTo: welcome.leadingAnchor),
            loginField.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -24),
            loginField.heightAnchor.constraint(equalToConstant: 32),
            passwordLabel.topAnchor.constraint(equalTo: loginField.bottomAnchor, constant: 12),
            passwordLabel.leadingAnchor.constraint(equalTo: welcome.leadingAnchor),
            passwordField.topAnchor.constraint(equalTo: passwordLabel.bottomAnchor, constant: 6),
            passwordField.leadingAnchor.constraint(equalTo: welcome.leadingAnchor),
            passwordField.trailingAnchor.constraint(equalTo: loginField.trailingAnchor),
            passwordField.heightAnchor.constraint(equalToConstant: 32),
            rememberPasswordButton.topAnchor.constraint(equalTo: passwordField.bottomAnchor, constant: 10),
            rememberPasswordButton.leadingAnchor.constraint(equalTo: passwordField.leadingAnchor),
            rememberPasswordButton.trailingAnchor.constraint(lessThanOrEqualTo: passwordField.trailingAnchor),
            rememberPasswordButton.heightAnchor.constraint(equalToConstant: 22),
            launchButton.topAnchor.constraint(equalTo: rememberPasswordButton.bottomAnchor, constant: 14),
            launchButton.leadingAnchor.constraint(equalTo: welcome.leadingAnchor),
            launchButton.trailingAnchor.constraint(equalTo: loginField.trailingAnchor),
            launchButton.heightAnchor.constraint(equalToConstant: 42),
            hint.topAnchor.constraint(equalTo: launchButton.bottomAnchor, constant: 10),
            hint.leadingAnchor.constraint(equalTo: welcome.leadingAnchor),
            hint.bottomAnchor.constraint(lessThanOrEqualTo: card.bottomAnchor, constant: -16),
            accountSummary.leadingAnchor.constraint(equalTo: card.trailingAnchor, constant: 16),
            accountSummary.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            accountSummary.topAnchor.constraint(equalTo: card.topAnchor),
            accountSummary.heightAnchor.constraint(equalToConstant: 158)
        ])
        return page
    }

    private func makeAccountsTab() -> NSView {
        let page = NSView()
        let heading = label("Твои аккаунты", size: 28, weight: .bold)
        let caption = note("Основной персонаж и твинки — всё под рукой.")
        let addButton = actionButton("Добавить", symbol: "plus", action: #selector(addAccount), style: .primary)
        addButton.identifier = NSUserInterfaceItemIdentifier("account-add")
        accountEditingButtons = [addButton]
        let refresh = actionButton("Обновить уровни", symbol: "arrow.clockwise", action: #selector(refreshAccounts))
        refresh.identifier = NSUserInterfaceItemIdentifier("account-refresh")
        accountCountLabel = label("", size: 11, color: GalaxyTheme.muted)
        let scroll = NSScrollView()
        scroll.identifier = NSUserInterfaceItemIdentifier("account-scroll")
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = true
        scroll.hasHorizontalScroller = false
        scroll.autohidesScrollers = true
        scroll.contentView = VerticalLogClipView()
        scroll.contentView.drawsBackground = false
        accountList = verticalStack([], spacing: 12)
        accountList.identifier = NSUserInterfaceItemIdentifier("account-list")
        let document = MCGLAccountsDocumentView()
        document.translatesAutoresizingMaskIntoConstraints = false
        scroll.documentView = document
        add([accountList], to: document)
        let footer = note("Уровни и профессия загружаются с форума MCGL по нику. Сохранение пароля — по желанию, на этом Mac.")
        add([heading, caption, addButton, refresh, accountCountLabel, scroll, footer], to: page)
        NSLayoutConstraint.activate([
            heading.topAnchor.constraint(equalTo: page.topAnchor, constant: 4),
            heading.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            caption.topAnchor.constraint(equalTo: heading.bottomAnchor, constant: 6),
            caption.leadingAnchor.constraint(equalTo: heading.leadingAnchor),
            addButton.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            addButton.topAnchor.constraint(equalTo: heading.topAnchor),
            addButton.widthAnchor.constraint(equalToConstant: 130), addButton.heightAnchor.constraint(equalToConstant: 38),
            refresh.topAnchor.constraint(equalTo: caption.bottomAnchor, constant: 16),
            refresh.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            refresh.widthAnchor.constraint(equalToConstant: 175), refresh.heightAnchor.constraint(equalToConstant: 32),
            accountCountLabel.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            accountCountLabel.centerYAnchor.constraint(equalTo: refresh.centerYAnchor),
            scroll.topAnchor.constraint(equalTo: refresh.bottomAnchor, constant: 12),
            scroll.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: footer.topAnchor, constant: -14),
            footer.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            footer.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            footer.bottomAnchor.constraint(equalTo: page.bottomAnchor, constant: -3),
            document.widthAnchor.constraint(equalTo: scroll.contentView.widthAnchor),
            accountList.topAnchor.constraint(equalTo: document.topAnchor, constant: 2),
            accountList.leadingAnchor.constraint(equalTo: document.leadingAnchor),
            accountList.trailingAnchor.constraint(equalTo: document.trailingAnchor, constant: -12),
            accountList.bottomAnchor.constraint(equalTo: document.bottomAnchor, constant: -2)
        ])
        return page
    }

    private func rebuildAccounts() {
        guard let accountList else { return }
        accountList.arrangedSubviews.forEach { accountList.removeArrangedSubview($0); $0.removeFromSuperview() }
        accountCards.removeAll()
        accountEditingButtons = accountEditingButtons.filter { $0.identifier?.rawValue == "account-add" }
        if accounts.accounts.isEmpty {
            let empty = makeCardView()
            let title = label(accounts.loadError == nil ? "Первый аккаунт на орбите" : "Список недоступен",
                              size: 21, weight: .semibold)
            let text = note(accounts.loadError?.localizedDescription
                ?? "Нажми «Добавить» и укажи игровой ник.\nЗдесь появятся профессия и уровни персонажа.")
            add([title, text], to: empty)
            NSLayoutConstraint.activate([
                title.leadingAnchor.constraint(equalTo: empty.leadingAnchor, constant: 24),
                title.topAnchor.constraint(equalTo: empty.topAnchor, constant: 27),
                text.leadingAnchor.constraint(equalTo: title.leadingAnchor),
                text.trailingAnchor.constraint(equalTo: empty.trailingAnchor, constant: -24),
                text.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 12),
                empty.heightAnchor.constraint(equalToConstant: 156)
            ])
            accountList.addArrangedSubview(empty)
            empty.widthAnchor.constraint(equalTo: accountList.widthAnchor).isActive = true
        }
        for (index, account) in accounts.accounts.enumerated() {
            let card = MCGLAccountCard(resources: resourcesRoot)
            card.identifier = NSUserInterfaceItemIdentifier("account-card-\(account.id)")
            accountCards[account.id] = card
            let choose = actionButton(accounts.selected?.id == account.id ? "Выбран" : "Выбрать",
                                      symbol: accounts.selected?.id == account.id ? "checkmark" : "person", action: #selector(selectAccount(_:)))
            let rename = actionButton("", symbol: "pencil", action: #selector(renameAccount(_:)))
            rename.setAccessibilityLabel("Переименовать \(account.nickname)")
            rename.toolTip = "Изменить название карточки"
            let remove = actionButton("", symbol: "trash", action: #selector(removeAccount(_:)))
            remove.setAccessibilityLabel("Удалить \(account.nickname) из списка")
            remove.toolTip = "Удалить из списка лаунчера"
            for (role, button) in [("select", choose), ("rename", rename), ("remove", remove)] {
                button.tag = index
                button.identifier = NSUserInterfaceItemIdentifier("account-\(role)-\(index)")
                accountEditingButtons.append(button)
            }
            add([choose, rename, remove], to: card)
            NSLayoutConstraint.activate([
                remove.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
                remove.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -16),
                remove.widthAnchor.constraint(equalToConstant: 32), remove.heightAnchor.constraint(equalToConstant: 29),
                rename.trailingAnchor.constraint(equalTo: remove.leadingAnchor, constant: -6),
                rename.centerYAnchor.constraint(equalTo: remove.centerYAnchor),
                rename.widthAnchor.constraint(equalTo: remove.widthAnchor), rename.heightAnchor.constraint(equalTo: remove.heightAnchor),
                choose.trailingAnchor.constraint(equalTo: rename.leadingAnchor, constant: -8),
                choose.centerYAnchor.constraint(equalTo: remove.centerYAnchor),
                choose.widthAnchor.constraint(equalToConstant: 105), choose.heightAnchor.constraint(equalTo: remove.heightAnchor)
            ])
            card.reserveFooterSpace(before: choose)
            accountList.addArrangedSubview(card)
            card.widthAnchor.constraint(equalTo: accountList.widthAnchor).isActive = true
        }
        refreshAccountCards()
        refreshAccountControls()
    }

    private func refreshAccountCards() {
        for account in accounts.accounts {
            accountCards[account.id]?.update(account: account, selected: accounts.selected?.id == account.id,
                                            status: accountStatusText(account))
        }
        let selected = accounts.selected
        accountSummary?.update(account: selected, selected: selected != nil,
                               status: selected.map(accountStatusText) ?? "Быстрый выбор из сохранённых логинов")
        accountSummary?.isHidden = selected == nil
        accountCountLabel?.stringValue = "Сохранено: \(accounts.accounts.count) / 30"
    }

    private func accountStatusText(_ account: MCGLAccount) -> String {
        let stamp = account.info.map { DateFormatter.localizedString(from: $0.fetchedAt, dateStyle: .short, timeStyle: .short) }
        if let status = accountStatus[account.id] {
            return status + (stamp.map { " · данные от \($0)" } ?? "")
        }
        return stamp.map { "Данные от \($0)" } ?? "Уровни ещё не загружены"
    }

    private func refreshAccountControls() {
        let editable = launchState == .ready
        loginField?.isEnabled = editable
        passwordField?.isEnabled = editable
        rememberPasswordButton?.isEnabled = editable && accounts.selected != nil && accounts.loadError == nil
        rememberPasswordButton?.toolTip = accounts.selected == nil
            ? "Для сохранения пароля добавь и выбери логин во вкладке «Аккаунты»."
            : "Без Связки ключей и мастер-пароля. Программа с доступом к твоим файлам сможет восстановить пароль."
        accountEditingButtons.forEach { $0.isEnabled = editable && accounts.loadError == nil }
    }

    private func applyAccountSelection() {
        passwordField.stringValue = ""
        loginField.stringValue = accounts.selected?.nickname ?? ""
        rebuildAccounts()
        restoreSelectedPassword()
        if let selected = accounts.selected { queueAccountRefresh(selected.id) }
    }

    @objc private func selectAccount(_ sender: NSButton) {
        guard launchState == .ready, accounts.accounts.indices.contains(sender.tag) else { return }
        do {
            try persistSelectedPassword()
            try accounts.select(accounts.accounts[sender.tag].id)
            applyAccountSelection()
        }
        catch { showError(error.localizedDescription) }
    }

    private func restoreSelectedPassword() {
        passwordField.stringValue = ""
        rememberPasswordButton.state = .off
        guard let account = accounts.selected else { return }
        do {
            if let password = try passwordStore.password(for: account) {
                passwordField.stringValue = password
                rememberPasswordButton.state = .on
            }
        } catch {
            // Allow explicitly forgetting an unreadable record, but never silently reset it.
            rememberPasswordButton.state = .on
            statusLabel.stringValue = "Сохранённый пароль недоступен. Можно убрать галочку и ввести его заново."
        }
    }

    private func persistSelectedPassword() throws {
        guard rememberPasswordButton.state == .on, preferences.localPasswordNoticeAccepted,
              let account = accounts.selected,
              account.nickname.caseInsensitiveCompare(loginField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame,
              !passwordField.stringValue.isEmpty else { return }
        try passwordStore.save(passwordField.stringValue, for: account)
    }

    @objc private func rememberPasswordChanged() {
        guard launchState == .ready, let account = accounts.selected else { return }
        if rememberPasswordButton.state == .off {
            do { try passwordStore.remove(for: account) }
            catch { rememberPasswordButton.state = .on; showError(error.localizedDescription) }
            return
        }
        if preferences.localPasswordNoticeAccepted {
            do { try persistSelectedPassword() }
            catch { showError(error.localizedDescription) }
            return
        }
        let alert = NSAlert()
        alert.window.appearance = NSAppearance(named: .darkAqua)
        alert.messageText = "Сохранить пароль на этом Mac?"
        alert.informativeText = "Лаунчер сохранит пароль локально, без Связки ключей и мастер-пароля. "
            + "Файл будет зашифрован, но ключ хранится рядом: программа с доступом к твоим файлам сможет восстановить пароль. "
            + "Снятие галочки удаляет сохранённый пароль этого аккаунта."
        alert.addButton(withTitle: "Сохранить")
        alert.addButton(withTitle: "Отмена")
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self, self.accounts.selected?.id == account.id, self.launchState == .ready else { return }
            guard response == .alertFirstButtonReturn else {
                self.rememberPasswordButton.state = .off
                return
            }
            self.preferences.localPasswordNoticeAccepted = true
            do { try self.persistSelectedPassword() }
            catch { self.showError(error.localizedDescription) }
        }
    }

    @objc private func addAccount() {
        guard launchState == .ready else { return }
        let alert = NSAlert()
        alert.messageText = "Добавить аккаунт"
        alert.window.appearance = NSAppearance(named: .darkAqua)
        alert.informativeText = "Ник будет отправлен форуму Minecraft Galaxy по HTTPS для получения профессии и уровней."
        alert.addButton(withTitle: "Добавить")
        alert.addButton(withTitle: "Отмена")
        let nick = NSTextField(frame: NSRect(x: 0, y: 48, width: 320, height: 30))
        nick.placeholderString = "Игровой ник"
        let name = NSTextField(frame: NSRect(x: 0, y: 5, width: 320, height: 30))
        name.placeholderString = "Название карточки — необязательно"
        for field in [nick, name] { styleField(field) }
        let accessory = NSView(frame: NSRect(x: 0, y: 0, width: 320, height: 85))
        accessory.addSubview(nick)
        accessory.addSubview(name)
        alert.accessoryView = accessory
        alert.window.initialFirstResponder = nick
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self, response == .alertFirstButtonReturn, self.launchState == .ready else { return }
            do {
                try self.accounts.add(nickname: nick.stringValue, label: name.stringValue)
                self.applyAccountSelection()
            } catch { self.showError(error.localizedDescription) }
        }
    }

    @objc private func renameAccount(_ sender: NSButton) {
        guard launchState == .ready, accounts.accounts.indices.contains(sender.tag) else { return }
        let account = accounts.accounts[sender.tag]
        let alert = NSAlert()
        alert.messageText = "Название карточки"
        alert.window.appearance = NSAppearance(named: .darkAqua)
        alert.informativeText = "Игровой ник \(account.nickname) не изменится."
        alert.addButton(withTitle: "Сохранить")
        alert.addButton(withTitle: "Отмена")
        let name = NSTextField(frame: NSRect(x: 0, y: 0, width: 320, height: 30))
        styleField(name)
        name.stringValue = account.label
        name.placeholderString = "Например, основной персонаж"
        alert.accessoryView = name
        alert.window.initialFirstResponder = name
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self, response == .alertFirstButtonReturn, self.launchState == .ready else { return }
            do { try self.accounts.rename(account.id, label: name.stringValue); self.rebuildAccounts() }
            catch { self.showError(error.localizedDescription) }
        }
    }

    @objc private func removeAccount(_ sender: NSButton) {
        guard launchState == .ready, accounts.accounts.indices.contains(sender.tag) else { return }
        let account = accounts.accounts[sender.tag]
        let alert = NSAlert()
        alert.messageText = "Убрать \(account.nickname) из списка?"
        alert.window.appearance = NSAppearance(named: .darkAqua)
        alert.informativeText = "Будут удалены карточка и её сохранённый пароль на этом Mac. Игровой аккаунт и файлы игры останутся."
        alert.addButton(withTitle: "Убрать из списка")
        alert.addButton(withTitle: "Отмена")
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self, response == .alertFirstButtonReturn, self.launchState == .ready else { return }
            do {
                let wasSelected = self.accounts.selected?.id == account.id
                try self.passwordStore.remove(for: account)
                try self.accounts.remove(account.id)
                self.accountQueue.removeAll { $0 == account.id }
                self.accountRequestTokens.removeValue(forKey: account.id)
                self.accountRequests.removeValue(forKey: account.id)?.cancel()
                self.accountStatus.removeValue(forKey: account.id)
                self.accountAttempts.removeValue(forKey: account.id)
                if wasSelected { self.applyAccountSelection() } else { self.rebuildAccounts() }
                self.pumpAccountRequests()
            } catch { self.showError(error.localizedDescription) }
        }
    }

    @objc private func refreshAccounts() {
        accounts.accounts.forEach { queueAccountRefresh($0.id, force: true) }
    }

    private func queueAccountRefresh(_ id: UUID, force: Bool = false) {
        guard accounts.loadError == nil, let account = accounts.accounts.first(where: { $0.id == id }),
              accountRequests[id] == nil, !accountQueue.contains(id) else { return }
        let now = Date()
        // A manual retry is allowed at most once per 15 seconds per nickname.
        guard accountAttempts[id].map({ now.timeIntervalSince($0) >= 15 }) ?? true else { return }
        if !force, let fetched = account.info?.fetchedAt, now.timeIntervalSince(fetched) < 300 { return }
        accountQueue.append(id)
        pumpAccountRequests()
    }

    private func pumpAccountRequests() {
        while accountRequests.count < 2, !accountQueue.isEmpty {
            let id = accountQueue.removeFirst()
            guard let account = accounts.accounts.first(where: { $0.id == id }) else { continue }
            let token = UUID()
            accountRequestTokens[id] = token
            accountAttempts[id] = Date()
            accountStatus[id] = "Обновление…"
            let request = accountService.fetch(nickname: account.nickname) { [weak self] result in
                guard let self, self.accountRequestTokens[id] == token else { return }
                self.accountRequests.removeValue(forKey: id)
                self.accountRequestTokens.removeValue(forKey: id)
                switch result {
                case .success(let info):
                    do {
                        try self.accounts.update(id, nickname: account.nickname, info: info)
                        self.accountStatus.removeValue(forKey: id)
                    } catch { self.accountStatus[id] = "Не удалось сохранить данные" }
                case .failure(let error):
                    self.accountStatus[id] = error is MCGLAccountError
                        ? "Профиль недоступен" : "Нет связи с форумом"
                }
                self.refreshAccountCards()
                self.pumpAccountRequests()
            }
            accountRequests[id] = request
        }
        refreshAccountCards()
    }

    private func makeSettingsTab() -> NSView {
        let page = NSView()
        let memoryCard = makeCardView()
        let performanceCard = makeCardView()
        let memoryTitle = sectionLabel("Память игры")
        let initialLabel = valueLabel("При запуске")
        let maximumLabel = valueLabel("Максимум")
        initialMemoryPopUp = memoryPopUp(values: MCGLLauncherPreferences.initialMemoryMBValues)
        initialMemoryPopUp.identifier = NSUserInterfaceItemIdentifier("initialMemory")
        initialMemoryPopUp.setAccessibilityLabel("Начальная память")
        initialMemoryPopUp.target = self
        initialMemoryPopUp.action = #selector(initialMemoryChanged)
        maximumMemoryPopUp = memoryPopUp(values: MCGLLauncherPreferences.maximumMemoryMBValues)
        maximumMemoryPopUp.identifier = NSUserInterfaceItemIdentifier("maximumMemory")
        maximumMemoryPopUp.setAccessibilityLabel("Максимальная память")
        maximumMemoryPopUp.target = self
        maximumMemoryPopUp.action = #selector(maximumMemoryChanged)
        let initial = verticalStack([initialLabel, initialMemoryPopUp], spacing: 8)
        let maximum = verticalStack([maximumLabel, maximumMemoryPopUp], spacing: 8)
        let memoryRow = NSStackView(views: [initial, maximum])
        memoryRow.orientation = .horizontal
        memoryRow.distribution = .fillEqually
        memoryRow.spacing = 24
        let memoryHint = note("«При запуске» задаёт начальный объём памяти (Xms), «Максимум» — предел роста (Xmx). Для начала: 512 МБ / 2 ГБ.")
        let memoryStack = verticalStack([memoryTitle, memoryRow, memoryHint], spacing: 16)
        add([memoryCard, performanceCard], to: page)
        pin(memoryStack, inside: memoryCard, inset: 24)
        memoryRow.widthAnchor.constraint(equalTo: memoryStack.widthAnchor).isActive = true
        memoryHint.widthAnchor.constraint(equalTo: memoryStack.widthAnchor).isActive = true
        for (popUp, container) in [(initialMemoryPopUp!, initial), (maximumMemoryPopUp!, maximum)] {
            popUp.widthAnchor.constraint(equalTo: container.widthAnchor).isActive = true
            popUp.heightAnchor.constraint(equalToConstant: 30).isActive = true
        }

        let performanceTitle = sectionLabel("Графика и производительность")
        let fpsLabel = valueLabel("Лимит кадров")
        fpsPopUp = NSPopUpButton(frame: .zero, pullsDown: false)
        fpsPopUp.controlSize = .large
        fpsPopUp.identifier = NSUserInterfaceItemIdentifier("fpsLimit")
        fpsPopUp.addItems(withTitles: MCGLLauncherPreferences.fpsLimits.map {
            $0 == 0 ? "Без ограничения" : "\($0) FPS"
        })
        fpsPopUp.selectItem(at: MCGLLauncherPreferences.fpsLimits.firstIndex(of: preferences.fpsLimit) ?? 0)
        fpsPopUp.target = self
        fpsPopUp.action = #selector(fpsLimitChanged)
        fpsPopUp.toolTip = "VSync и настройки клиента могут ограничивать FPS сильнее."
        let fpsRow = NSStackView(views: [fpsLabel, fpsPopUp])
        fpsRow.orientation = .horizontal
        fpsRow.spacing = 24
        fpsPopUp.widthAnchor.constraint(equalToConstant: 212).isActive = true
        fpsPopUp.heightAnchor.constraint(equalToConstant: 30).isActive = true
        multicoreButton = NSButton(checkboxWithTitle: "Параллельная сборка мусора · G1GC",
                                   target: self, action: #selector(performanceOptionsChanged))
        multicoreButton.identifier = NSUserInterfaceItemIdentifier("multicoreMemory")
        multicoreButton.state = preferences.multicoreMemory ? .on : .off
        multicoreButton.toolTip = "Сборка мусора использует фоновые потоки; результат зависит от нагрузки."
        graphicsDiagnosticsButton = NSButton(checkboxWithTitle: "Диагностика графики в журнале",
                                             target: self, action: #selector(performanceOptionsChanged))
        graphicsDiagnosticsButton.identifier = NSUserInterfaceItemIdentifier("graphicsDiagnostics")
        graphicsDiagnosticsButton.state = preferences.graphicsDiagnostics ? .on : .off
        graphicsDiagnosticsButton.toolTip = "Техническая статистика каждые 5 секунд. Для обычной игры не требуется."
        chunkVboButton = NSButton(checkboxWithTitle: "Ускоренная отрисовка чанков · VBO / VAO",
                                  target: self, action: #selector(performanceOptionsChanged))
        chunkVboButton.identifier = NSUserInterfaceItemIdentifier("chunkVbo")
        chunkVboButton.state = preferences.chunkVbo ? .on : .off
        chunkVboButton.toolTip = "Ускоренная передача геометрии с сохранением запасного пути отрисовки."
        for button in [multicoreButton!, graphicsDiagnosticsButton!, chunkVboButton!] {
            styleCheckbox(button)
        }
        let performanceStack = verticalStack(
            [performanceTitle, fpsRow, multicoreButton, chunkVboButton, graphicsDiagnosticsButton], spacing: 18)
        pin(performanceStack, inside: performanceCard, inset: 24)
        let savedHint = note("Настройки сохраняются автоматически и применяются при следующем запуске игры.")
        add([savedHint], to: page)
        NSLayoutConstraint.activate([
            memoryCard.topAnchor.constraint(equalTo: page.topAnchor),
            memoryCard.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            memoryCard.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            memoryCard.bottomAnchor.constraint(equalTo: memoryStack.bottomAnchor, constant: 24),
            performanceCard.topAnchor.constraint(equalTo: memoryCard.bottomAnchor, constant: 16),
            performanceCard.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            performanceCard.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            performanceCard.bottomAnchor.constraint(equalTo: performanceStack.bottomAnchor, constant: 24),
            savedHint.topAnchor.constraint(equalTo: performanceCard.bottomAnchor, constant: 16),
            savedHint.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            savedHint.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            savedHint.bottomAnchor.constraint(lessThanOrEqualTo: page.bottomAnchor)
        ])
        return page
    }

    private func styleCheckbox(_ button: NSButton) {
        button.contentTintColor = GalaxyTheme.cyan
        button.font = .systemFont(ofSize: 13)
        // AppKit can retain a black cell title despite the dark appearance.
        // Tint the native checkmark separately from the explicitly light label.
        button.attributedTitle = NSAttributedString(string: button.title, attributes: [
            .font: NSFont.systemFont(ofSize: 13),
            .foregroundColor: NSColor.white
        ])
    }

    private func makeLogTab() -> NSView {
        let card = makeCardView()
        let title = sectionLabel("Журнал запуска и игры")
        let subtitle = note("Здесь появляются сообщения о загрузке клиента и работе игры.")
        let clearButton = actionButton("Очистить", symbol: "xmark",
                                       action: #selector(clearVisibleLog))
        let scrollView = NSScrollView()
        scrollView.contentView = VerticalLogClipView()
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .noBorder
        scrollView.wantsLayer = true
        scrollView.layer?.cornerRadius = 10
        scrollView.layer?.masksToBounds = true
        scrollView.backgroundColor = GalaxyTheme.ink
        logView = NSTextView(frame: NSRect(x: 0, y: 0, width: 680, height: 400))
        logView.isEditable = false
        logView.isSelectable = true
        logView.isVerticallyResizable = true
        logView.isHorizontallyResizable = false
        logView.minSize = .zero
        logView.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        logView.autoresizingMask = [.width]
        logView.textContainerInset = NSSize(width: 12, height: 12)
        logView.textContainer?.containerSize = NSSize(width: 680, height: CGFloat.greatestFiniteMagnitude)
        logView.textContainer?.widthTracksTextView = true
        logView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        logView.backgroundColor = GalaxyTheme.ink
        logView.textColor = .white
        logView.setAccessibilityLabel("Журнал запуска и игры")
        scrollView.documentView = logView
        add([title, subtitle, clearButton, scrollView], to: card)
        NSLayoutConstraint.activate([
            title.topAnchor.constraint(equalTo: card.topAnchor, constant: 24),
            title.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 24),
            subtitle.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 8),
            subtitle.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            subtitle.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -24),
            clearButton.centerYAnchor.constraint(equalTo: title.centerYAnchor),
            clearButton.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -24),
            clearButton.widthAnchor.constraint(equalToConstant: 108),
            clearButton.heightAnchor.constraint(equalToConstant: 32),
            scrollView.topAnchor.constraint(equalTo: subtitle.bottomAnchor, constant: 22),
            scrollView.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: clearButton.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -24)
        ])
        return card
    }

    private func add(_ views: [NSView], to parent: NSView) {
        for view in views {
            view.translatesAutoresizingMaskIntoConstraints = false
            parent.addSubview(view)
        }
    }

    private func pin(_ view: NSView, inside parent: NSView, inset: CGFloat) {
        add([view], to: parent)
        NSLayoutConstraint.activate([
            view.topAnchor.constraint(equalTo: parent.topAnchor, constant: inset),
            view.leadingAnchor.constraint(equalTo: parent.leadingAnchor, constant: inset),
            view.trailingAnchor.constraint(equalTo: parent.trailingAnchor, constant: -inset)
        ])
    }

    private func verticalStack(_ views: [NSView], spacing: CGFloat) -> NSStackView {
        let stack = NSStackView(views: views)
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = spacing
        for view in views { view.translatesAutoresizingMaskIntoConstraints = false }
        return stack
    }

    private func label(_ text: String, size: CGFloat, weight: NSFont.Weight = .regular,
                       color: NSColor = .white) -> NSTextField {
        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: size, weight: weight)
        label.textColor = color
        return label
    }

    private func note(_ text: String) -> NSTextField {
        let label = NSTextField(wrappingLabelWithString: text)
        label.font = .systemFont(ofSize: 11)
        label.textColor = GalaxyTheme.muted
        return label
    }

    private func sectionLabel(_ text: String) -> NSTextField {
        label(text, size: 18, weight: .semibold)
    }

    private func valueLabel(_ text: String) -> NSTextField {
        label(text, size: 12, weight: .medium, color: GalaxyTheme.muted)
    }

    private func actionButton(_ title: String, symbol: String? = nil, action: Selector,
                              style: GalaxyButton.Style = .secondary) -> GalaxyButton {
        let button = GalaxyButton(title: title, target: self, action: action)
        button.style = style
        button.symbol = symbol
        button.isBordered = false
        button.setAccessibilityLabel(title)
        return button
    }

    private func styleField(_ field: NSTextField) {
        field.bezelStyle = .roundedBezel
        field.controlSize = .large
        field.font = .systemFont(ofSize: 14)
        field.textColor = .white
        field.backgroundColor = GalaxyTheme.ink
        field.focusRingType = .exterior
    }

    private func memoryPopUp(values: [Int]) -> NSPopUpButton {
        let popUp = NSPopUpButton(frame: .zero, pullsDown: false)
        popUp.controlSize = .large
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

    func controlTextDidChange(_ notification: Notification) {
        if notification.object as? NSTextField === loginField {
            passwordField.stringValue = ""
            rememberPasswordButton.state = .off
            if accounts.selected != nil {
                try? accounts.select(nil)
                rebuildAccounts()
            }
        }
    }

    func controlTextDidEndEditing(_ notification: Notification) {
        guard launchState == .ready, notification.object as? NSTextField === passwordField else { return }
        do { try persistSelectedPassword() }
        catch { showError(error.localizedDescription) }
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

    @objc private func toggleGame() {
        switch launchState {
        case .ready: launchGame()
        case .running: stopGame()
        case .preparing, .launching, .stopping: break
        }
    }

    @objc private func launchGame() {
        guard launchState == .ready else { return }
        guard gameApplication == nil else {
            appendLog("Клиент уже запущен.")
            return
        }

        let login = loginField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
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

        do { try persistSelectedPassword() }
        catch { showError(error.localizedDescription); return }

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
        launchState = .preparing

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
            resetLaunchControls(status: "Ошибка запуска")
            showError("Не найден скачанный Minecraft.jar.")
            return
        }
        guard fileManager.fileExists(atPath: gameDirectory + "/mcgl-nativewindow-patch.jar") else {
            resetLaunchControls(status: "Ошибка запуска")
            showError("Не найден патч отдельного игрового окна.")
            return
        }
        guard fileManager.fileExists(atPath: gameRuntimePath + "/Contents/Info.plist"),
              fileManager.isExecutableFile(atPath: gameRuntimePath + "/Contents/MacOS/MCGL ARM64 Runtime") else {
            resetLaunchControls(status: "Ошибка запуска")
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
            resetLaunchControls(status: "Ошибка запуска")
            showError("Не удалось создать защищённый канал: \(error.localizedDescription)")
            return
        }

        appendLog("Запуск Minecraft Galaxy в полностью нативном ARM64-режиме…")
        statusLabel.stringValue = "Запуск клиента…"
        launchState = .launching

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
                self.launchState = .running
                self.statusLabel.stringValue = "Игра запущена · остановить можно на вкладке «Играть»"
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
        launchState = .stopping
        statusLabel.stringValue = "Остановка клиента…"
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
        launchState = .ready
        if passwordField.stringValue.isEmpty { restoreSelectedPassword() }
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
