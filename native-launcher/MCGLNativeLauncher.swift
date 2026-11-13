import Cocoa
import Darwin

enum GalaxyTheme {
    static let ink = NSColor(srgbRed: 0.045, green: 0.040, blue: 0.085, alpha: 1)
    static let panel = NSColor(srgbRed: 0.14, green: 0.12, blue: 0.24, alpha: 0.57)
    static let blue = NSColor(srgbRed: 0.39, green: 0.28, blue: 0.94, alpha: 1)
    static let cyan = NSColor(srgbRed: 0.76, green: 0.75, blue: 1, alpha: 1)
    static let muted = NSColor(srgbRed: 0.71, green: 0.69, blue: 0.81, alpha: 1)
    static let line = NSColor.white.withAlphaComponent(0.10)
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

/// A layer-backed tint that leaves native labels and controls in charge of drawing and input.
private final class GalaxyHoverGlow {
    private var gradient: CAGradientLayer?
    var tint = GalaxyTheme.cyan
    var strength: CGFloat = 0.24

    func layout(in view: NSView) {
        guard let gradient else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        gradient.frame = view.bounds
        CATransaction.commit()
    }

    func update(in view: NSView, opacity: Float, animated: Bool) {
        guard let backing = view.layer, opacity > 0 || gradient != nil else { return }
        if gradient == nil {
            let layer = CAGradientLayer()
            layer.name = "galaxy-hover-glow"
            layer.startPoint = CGPoint(x: 0, y: 0.5)
            layer.endPoint = CGPoint(x: 1, y: 0.5)
            layer.cornerRadius = backing.cornerRadius > 0 ? backing.cornerRadius : 9
            layer.opacity = 0
            backing.insertSublayer(layer, at: 0)
            gradient = layer
            layout(in: view)
        }
        guard let gradient else { return }
        let previous = gradient.presentation()?.opacity ?? gradient.opacity
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        gradient.colors = [tint.withAlphaComponent(strength).cgColor, tint.withAlphaComponent(0.035).cgColor]
        gradient.opacity = opacity
        CATransaction.commit()
        if animated, previous != opacity, !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion {
            let animation = CABasicAnimation(keyPath: "opacity")
            animation.fromValue = previous
            animation.toValue = opacity
            animation.duration = 0.24
            animation.timingFunction = CAMediaTimingFunction(name: .easeOut)
            gradient.add(animation, forKey: "hover-glow")
        } else {
            gradient.removeAnimation(forKey: "hover-glow")
        }
    }
}

/// Shared feedback for the launcher’s native, keyboard-accessible buttons.
class GalaxyInteractiveButton: NSButton {
    private var pointerTracking: NSTrackingArea?
    private let hoverGlow = GalaxyHoverGlow()
    private(set) var pointerInside = false
    private(set) var pointerPressed = false
    var restingGlowOpacity: Float { 0 }

    func setGlowTint(_ tint: NSColor, strength: CGFloat) {
        hoverGlow.tint = tint
        hoverGlow.strength = strength
    }

    override func layout() {
        super.layout()
        hoverGlow.layout(in: self)
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let pointerTracking { removeTrackingArea(pointerTracking) }
        let area = NSTrackingArea(rect: .zero, options: [.inVisibleRect, .activeInKeyWindow, .mouseEnteredAndExited],
                                  owner: self)
        addTrackingArea(area)
        pointerTracking = area
    }

    override func mouseEntered(with event: NSEvent) {
        pointerInside = true
        refreshFeedback(animated: true)
    }

    override func mouseExited(with event: NSEvent) {
        pointerInside = false
        refreshFeedback(animated: true)
    }

    override func mouseDown(with event: NSEvent) {
        guard isEnabled else { return }
        pointerPressed = true
        refreshFeedback(animated: true)
        displayIfNeeded()
        super.mouseDown(with: event)
        pointerPressed = false
        if let window {
            pointerInside = visibleRect.contains(convert(window.mouseLocationOutsideOfEventStream, from: nil))
        }
        refreshFeedback(animated: true)
    }

    override var isEnabled: Bool {
        didSet { refreshFeedback(animated: false) }
    }

    func refreshFeedback(animated: Bool) {
        needsDisplay = true
        guard let layer else { return }
        let glow: Float = isEnabled && pointerInside ? (pointerPressed ? 0.63 : 1) : restingGlowOpacity
        hoverGlow.update(in: self, opacity: glow, animated: animated)
        let reduceMotion = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        let scale: CGFloat = isEnabled && pointerPressed && !reduceMotion ? 0.98 : 1
        let previous = layer.presentation()?.value(forKeyPath: "transform.scale")
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.transform = CATransform3DMakeScale(scale, scale, 1)
        CATransaction.commit()
        if animated && !reduceMotion, let previous {
            let animation = CABasicAnimation(keyPath: "transform.scale")
            animation.fromValue = previous
            animation.toValue = scale
            animation.duration = pointerPressed ? 0.085 : 0.15
            animation.timingFunction = CAMediaTimingFunction(name: .easeOut)
            layer.add(animation, forKey: "galaxy-press")
        }
    }
}

private final class GalaxyPopUpButton: NSPopUpButton {
    private let hoverGlow = GalaxyHoverGlow()
    private var hoverArea: NSTrackingArea?

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let hoverArea { removeTrackingArea(hoverArea) }
        let area = NSTrackingArea(rect: .zero, options: [.inVisibleRect, .activeInKeyWindow, .mouseEnteredAndExited],
                                  owner: self)
        addTrackingArea(area)
        hoverArea = area
    }

    override func layout() {
        super.layout()
        hoverGlow.layout(in: self)
    }

    override func mouseEntered(with event: NSEvent) {
        super.mouseEntered(with: event)
        hoverGlow.update(in: self, opacity: isEnabled ? 1 : 0, animated: true)
    }

    override func mouseExited(with event: NSEvent) {
        super.mouseExited(with: event)
        hoverGlow.update(in: self, opacity: 0, animated: true)
    }

    override var isEnabled: Bool {
        didSet { if !isEnabled { hoverGlow.update(in: self, opacity: 0, animated: false) } }
    }
}

final class GalaxyButton: GalaxyInteractiveButton {
    enum Style { case primary, secondary, navigation }
    var style: Style = .secondary
    var symbol: String?
    var selected = false { didSet { needsDisplay = true } }
    func contentLayout(textSize: NSSize) -> (icon: NSRect, text: NSPoint) {
        let iconWidth: CGFloat = symbol == nil ? 0 : 18
        // Icon-only actions have no label gap; otherwise they sit 4 pt off centre.
        let gap: CGFloat = symbol != nil && !title.isEmpty ? 8 : 0
        let contentWidth = iconWidth + gap + textSize.width
        let x = bounds.midX - contentWidth / 2
        return (NSRect(x: x, y: bounds.midY - 9, width: iconWidth, height: 18),
                NSPoint(x: x + iconWidth + gap, y: bounds.midY - textSize.height / 2))
    }

    override func draw(_ dirtyRect: NSRect) {
        let shape = NSBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1),
                                 xRadius: 10, yRadius: 10)
        let fill: NSColor = style == .primary ? GalaxyTheme.blue
            : selected ? NSColor.white.withAlphaComponent(0.13)
            : style == .navigation ? .clear : NSColor.white.withAlphaComponent(0.075)
        fill.withAlphaComponent(isEnabled ? fill.alphaComponent : fill.alphaComponent * 0.4).setFill()
        shape.fill()
        if isEnabled && (pointerInside || pointerPressed || isHighlighted) {
            let pressed = pointerPressed || isHighlighted
            let tint: NSColor = pressed ? .black : .white
            tint.withAlphaComponent(pressed ? 0.16 : 0.075).setFill()
            shape.fill()
        }
        if style == .secondary || style == .primary {
            GalaxyTheme.line.setStroke()
            shape.lineWidth = 1
            shape.stroke()
        }
        let color = (style == .navigation && !selected ? GalaxyTheme.muted : NSColor.white)
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

/// Lets a native button own clicks and focus even when it contains visual labels.
class GalaxyContentButton: GalaxyInteractiveButton {
    var restingFill = NSColor.white.withAlphaComponent(0.045)
    var restingBorder = GalaxyTheme.line
    var selectedAppearance = false { didSet { refreshFeedback(animated: false) } }

    override func refreshFeedback(animated: Bool) {
        super.refreshFeedback(animated: animated)
        guard let layer else { return }
        let fill = selectedAppearance ? NSColor.white.withAlphaComponent(0.13) : restingFill
        let opacity = pointerPressed ? -0.035 : pointerInside ? 0.055 : 0
        let border = selectedAppearance ? GalaxyTheme.cyan.withAlphaComponent(0.62)
            : isEnabled && pointerInside ? NSColor.white.withAlphaComponent(0.22) : restingBorder
        CATransaction.begin()
        let animate = animated && !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        CATransaction.setDisableActions(!animate)
        CATransaction.setAnimationDuration(0.13)
        layer.backgroundColor = fill.withAlphaComponent(max(0, fill.alphaComponent + (isEnabled ? opacity : 0))).cgColor
        layer.borderColor = border.cgColor
        CATransaction.commit()

    }

    override func hitTest(_ point: NSPoint) -> NSView? {
        super.hitTest(point) == nil ? nil : self
    }
}

private final class GalaxyEffectsOverlay: NSView {
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}

private final class GalaxyMetalButton: GalaxyContentButton {
    private var starsActive = false
    private var stars: CALayer?
    weak var effectsHost: NSView?
    override var restingGlowOpacity: Float { !isEnabled ? 0.25 : selectedAppearance ? 0.60 : 0.43 }

    func setStarsActive(_ active: Bool) {
        starsActive = active
        updateStars()
    }

    override func layout() {
        super.layout()
        updateStars()
    }

    private func updateStars() {
        guard starsActive, let effectsHost, let host = effectsHost.layer,
              let window, effectsHost.window === window, bounds.width > 0, bounds.height > 0 else {
            stars?.removeFromSuperlayer()
            stars = nil
            return
        }
        let inset: CGFloat = 32
        let frame = effectsHost.convert(bounds, from: self).insetBy(dx: -inset, dy: -inset)
        if stars?.frame == frame { return }
        stars?.removeFromSuperlayer()

        // The effect belongs to the window overlay, outside the button and stack view clips.
        let field = CALayer()
        field.name = "metal-stars"
        field.frame = frame
        let card = CGRect(origin: CGPoint(x: inset, y: inset), size: bounds.size)
        let cycle = 8.4
        let duration = 2.75
        let paths: [(origin: CGPoint, offset: CGPoint)] = [
            (CGPoint(x: card.minX + card.width * 0.37, y: card.maxY), CGPoint(x: -9, y: 27)),
            (CGPoint(x: card.maxX, y: card.minY + card.height * 0.71), CGPoint(x: 27, y: 7)),
            (CGPoint(x: card.minX + card.width * 0.74, y: card.minY), CGPoint(x: 7, y: -24)),
            (CGPoint(x: card.minX, y: card.minY + card.height * 0.31), CGPoint(x: -23, y: -8))
        ]
        let pixel: CGFloat = 2
        let shape = CGMutablePath()
        shape.addRect(CGRect(x: pixel, y: 0, width: pixel, height: pixel * 3))
        shape.addRect(CGRect(x: 0, y: pixel, width: pixel * 3, height: pixel))
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        host.addSublayer(field)
        let start = field.convertTime(CACurrentMediaTime(), from: nil)
        for (index, path) in paths.enumerated() {
            let star = CAShapeLayer()
            star.bounds = CGRect(x: 0, y: 0, width: pixel * 3, height: pixel * 3)
            star.path = shape
            star.fillColor = (index.isMultiple(of: 2) ? GalaxyTheme.cyan : NSColor.white).cgColor
            star.position = path.origin
            star.opacity = 0
            field.addSublayer(star)

            let move = CABasicAnimation(keyPath: "position")
            move.fromValue = path.origin
            move.toValue = CGPoint(x: path.origin.x + path.offset.x, y: path.origin.y + path.offset.y)
            move.duration = duration
            let fade = CAKeyframeAnimation(keyPath: "opacity")
            fade.values = [0, 0.94, 0.70, 0]
            fade.keyTimes = [0, 0.22, 0.64, 1]
            fade.duration = duration
            let drift = CAAnimationGroup()
            drift.animations = [move, fade]
            drift.beginTime = start + Double(index) * (cycle / Double(paths.count))
            drift.duration = cycle
            drift.repeatCount = .infinity
            star.add(drift, forKey: "pixel-drift")
        }
        CATransaction.commit()
        stars = field
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
            NSColor(calibratedWhite: 0.5, alpha: 0.29).setFill()
            bounds.fill(using: .saturation)
        }
        NSGradient(starting: GalaxyTheme.ink.withAlphaComponent(0.40),
                   ending: GalaxyTheme.ink.withAlphaComponent(0.23))?.draw(in: bounds, angle: 90)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, NSTextFieldDelegate {
    static let windowSize = NSSize(width: 1060, height: 662)
    private static let newsRowHeight: CGFloat = 47
    private enum HeaderPanel { case accounts, settings }
    enum UpdatePhase: CaseIterable { case idle, available, checking, downloading, verifying, restarting }
    private let preferences: MCGLLauncherPreferences
    private let resourcesOverride: URL?
    private let supportRootOverride: URL?
    private let updater = MCGLLauncherUpdater()
    private let accounts: MCGLAccountStore
    private let accountService: MCGLAccountFetching
    private let passwordStore: MCGLPasswordStore

    init(preferences: MCGLLauncherPreferences = MCGLLauncherPreferences(),
         resourcesRoot: URL? = nil,
         supportRoot: URL? = nil,
         accountService: MCGLAccountFetching = MCGLAccountService(),
         passwordStore: MCGLPasswordStore = MCGLPasswordStore()) {
        self.preferences = preferences
        self.resourcesOverride = resourcesRoot
        self.supportRootOverride = supportRoot
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
        if let supportRootOverride { return supportRootOverride }
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        return base.appendingPathComponent("Minecraft Galaxy ARM64", isDirectory: true)
    }()
    private lazy var gameDirectoryURL = supportRootURL
        .appendingPathComponent("mclient-arm64", isDirectory: true)
    private lazy var gameDirectory = gameDirectoryURL.path
    private lazy var javaVMPath = resourcesRoot
        .appendingPathComponent("java21-arm64", isDirectory: true)
        .appendingPathComponent("Home/lib/server/libjvm.dylib").path
    private lazy var javaExecutableURL = resourcesRoot
        .appendingPathComponent("java21-arm64/Home/bin/java")
    private lazy var jarExecutableURL = resourcesRoot
        .appendingPathComponent("java21-arm64/Home/bin/jar")
    private lazy var gameRuntimePath = resourcesRoot
        .appendingPathComponent("MCGL ARM64 Runtime.app", isDirectory: true).path
    private lazy var logPath: String = {
        if let supportRootOverride {
            let directory = supportRootOverride.appendingPathComponent("Logs", isDirectory: true)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            return directory.appendingPathComponent("launcher.log").path
        }
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
    private var pages: [Int: NSTabViewItem] = [:]
    private var navigationButtons: [Int: GalaxyButton] = [:]
    private var accountsPanel: NSVisualEffectView!
    private var settingsPanel: NSVisualEffectView!
    private var settingsButton: GalaxyButton!
    private var openPanel: HeaderPanel?
    private var panelEventMonitor: Any?
    private var passwordField: NSSecureTextField!
    private var rememberPasswordButton: NSButton!
    private var accountList: NSStackView!
    private var credentialsView: NSView!
    private var profileButton: GalaxyContentButton!
    private let profileArrow = NSImageView()
    private let profileAvatar = NSImageView()
    private let profileName = NSTextField(labelWithString: "")
    private let profileSubtitle = NSTextField(labelWithString: "")
    private var accountCards: [UUID: MCGLAccountCard] = [:]
    private var accountEditingButtons: [NSButton] = []
    private var accountStatus: [UUID: String] = [:]
    private var accountRequests: [UUID: MCGLAccountRequest] = [:]
    private var accountRequestTokens: [UUID: UUID] = [:]
    private var accountQueue: [UUID] = []
    private var accountAttempts: [UUID: Date] = [:]
    private var accountCountLabel: NSTextField!
    private var fpsPopUp: NSPopUpButton!
    private var metalVSyncButton: NSButton!
    private var launchButton: GalaxyButton!
    private var newsRows: NSStackView!
    private var newsItems: [MCGLProjectNews] = []
    private var newsTask: URLSessionDataTask?
    private var rendererButtons: [MCGLRenderer: GalaxyContentButton] = [:]
    private var launchState: MCGLLaunchState = .ready {
        didSet { refreshLaunchButton() }
    }
    private var initialMemoryPopUp: NSPopUpButton!
    private var maximumMemoryPopUp: NSPopUpButton!
    private var multicoreButton: NSButton!
    private var graphicsDiagnosticsButton: NSButton!
    private var chunkVboButton: NSButton!
    private var statusLabel: NSTextField!
    private var collapsedLaunchStatus: NSLayoutConstraint!
    private var updateButton: GalaxyContentButton!
    private var updateStatusLabel: NSTextField!
    private let updateTitle = NSTextField(labelWithString: "")
    private let updateGlyph = NSImageView()
    private let updateSpinner = NSProgressIndicator()
    private let updateDownloadProgress = NSProgressIndicator()
    private var availableLauncherUpdate: MCGLGitHubRelease?
    private var updateProgressTimer: Timer?
    private var installingLauncherUpdate = false
    private var logView: NSTextView!
    private var gameApplication: NSRunningApplication?
    private var installer: MCGLInstaller?
    private var logChannel: FileHandle?
    private var passwordChannel: FileHandle?
    private var transportPaths: [String] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        buildWindow()
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(accessibilityDisplayChanged(_:)),
            name: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification, object: nil)
        appendLog("Minecraft Galaxy ARM64 Bootstrap \(MCGLLauncherUpdater.currentVersion) готов к запуску.")
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        checkForLauncherUpdates(silent: true)
        newsTask = MCGLProjectNews.fetch { [weak self] items in self?.showProjectNews(items) }
        if let selected = accounts.selected { queueAccountRefresh(selected.id) }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        installingLauncherUpdate ? .terminateCancel : .terminateNow
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool { !installingLauncherUpdate }

    func windowDidBecomeKey(_ notification: Notification) { updateMetalStars() }
    func windowDidResignKey(_ notification: Notification) { updateMetalStars() }
    func windowDidChangeOcclusionState(_ notification: Notification) { updateMetalStars() }
    func windowDidResize(_ notification: Notification) {
        guard window.isKeyWindow else { return }
        window.contentView?.layoutSubtreeIfNeeded()
        updateMetalStars()
    }

    func applicationWillTerminate(_ notification: Notification) {
        updateProgressTimer?.invalidate()
        closeHeaderPanel()
        if let gameApplication, !gameApplication.isTerminated {
            gameApplication.terminate()
        }
        cleanupTransport()
        newsTask?.cancel()
        accountQueue.removeAll()
        accountRequestTokens.removeAll()
        accountRequests.values.forEach { $0.cancel() }
        accountRequests.removeAll()
    }

    func buildWindow() {
        window = NSWindow(
            contentRect: NSRect(origin: .zero, size: Self.windowSize),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered, defer: false)
        window.contentMinSize = NSSize(width: 960, height: 650)
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

        let icon = NSImageView()
        icon.image = NSImage(contentsOf: resourcesRoot.appendingPathComponent("app-icon-symbol.png"))
        icon.imageScaling = .scaleProportionallyUpOrDown
        icon.identifier = NSUserInterfaceItemIdentifier("launcher-brand-symbol")
        let unreadChanges = preferences.lastReadChangelogVersion != MCGLLauncherUpdater.currentVersion
        let pageTitles = [(0, "Главная"), (3, "Журнал"), (4, unreadChanges ? "Изменения •" : "Изменения")]
        for (index, title) in pageTitles {
            let button = actionButton(title, action: #selector(selectPage(_:)), style: .navigation)
            button.tag = index
            button.identifier = NSUserInterfaceItemIdentifier("page-\(index)")
            button.setAccessibilityLabel(title)
            button.toolTip = title
            button.keyEquivalent = "\(index + 1)"
            button.keyEquivalentModifierMask = [.command]
            navigationButtons[index] = button
        }
        let navigation = makeCardView(radius: 14)
        let tabs = NSStackView(views: [0, 3, 4].compactMap { navigationButtons[$0] })
        tabs.orientation = .horizontal
        tabs.spacing = 2
        tabs.distribution = .fillEqually
        add([tabs], to: navigation)
        let settings = actionButton("", symbol: "slider.horizontal.3", action: #selector(toggleSettings))
        settings.identifier = NSUserInterfaceItemIdentifier("page-2")
        settings.setAccessibilityLabel("Настройки")
        settings.toolTip = "Настройки"
        settings.keyEquivalent = "3"
        settings.keyEquivalentModifierMask = [.command]
        settingsButton = settings
        let profile = makeProfileButton()

        tabView = NSTabView()
        tabView.tabViewType = .noTabsNoBorder
        tabView.drawsBackground = false
        for (index, identifier, view) in [(0, "play", makePlayTab()), (3, "log", makeLogTab()), (4, "changes", makeChangelogTab())] {
            let item = NSTabViewItem(identifier: identifier)
            item.label = navigationButtons[index]?.title ?? ""
            item.view = view
            tabView.addTabViewItem(item)
            pages[index] = item
        }
        add([icon, navigation, profile, settings, tabView], to: content)
        NSLayoutConstraint.activate([
            icon.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 24),
            icon.centerYAnchor.constraint(equalTo: navigation.centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 42),
            icon.heightAnchor.constraint(equalTo: icon.widthAnchor),
            navigation.leadingAnchor.constraint(equalTo: icon.trailingAnchor, constant: 14),
            navigation.topAnchor.constraint(equalTo: content.topAnchor, constant: 40),
            navigation.widthAnchor.constraint(equalToConstant: 320),
            navigation.heightAnchor.constraint(equalToConstant: 42),
            tabs.leadingAnchor.constraint(equalTo: navigation.leadingAnchor, constant: 4),
            tabs.trailingAnchor.constraint(equalTo: navigation.trailingAnchor, constant: -4),
            tabs.topAnchor.constraint(equalTo: navigation.topAnchor, constant: 4),
            tabs.bottomAnchor.constraint(equalTo: navigation.bottomAnchor, constant: -4),
            settings.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -24),
            settings.centerYAnchor.constraint(equalTo: navigation.centerYAnchor),
            settings.widthAnchor.constraint(equalToConstant: 44),
            settings.heightAnchor.constraint(equalTo: navigation.heightAnchor),
            profile.trailingAnchor.constraint(equalTo: settings.leadingAnchor, constant: -10),
            profile.centerYAnchor.constraint(equalTo: navigation.centerYAnchor),
            profile.widthAnchor.constraint(equalToConstant: 204),
            profile.heightAnchor.constraint(equalTo: navigation.heightAnchor),

            tabView.leadingAnchor.constraint(equalTo: icon.leadingAnchor),
            tabView.trailingAnchor.constraint(equalTo: settings.trailingAnchor),
            tabView.topAnchor.constraint(equalTo: navigation.bottomAnchor, constant: 22),
            tabView.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -24)
        ])
        for button in tabs.arrangedSubviews {
            button.heightAnchor.constraint(equalTo: tabs.heightAnchor).isActive = true
        }
        let effects = GalaxyEffectsOverlay(frame: content.bounds)
        effects.identifier = NSUserInterfaceItemIdentifier("launcher-effects")
        effects.wantsLayer = true
        effects.autoresizingMask = [.width, .height]
        content.addSubview(effects, positioned: .above, relativeTo: tabView)
        (rendererButtons[.metal] as? GalaxyMetalButton)?.effectsHost = effects
        accountsPanel = makeHeaderPanel(makeAccountsPanel(), id: "header-accounts-panel",
                                        anchor: profile, size: NSSize(width: 580, height: 500))
        settingsPanel = makeHeaderPanel(makeSettingsPanel(), id: "header-settings-panel",
                                        anchor: settings, size: NSSize(width: 502, height: 456))
        refreshMemoryControls()
        rebuildAccounts()
        showPage(0)
        restoreSelectedPassword()
        window.initialFirstResponder = launchButton
    }

    private func makeProfileButton() -> NSButton {
        let button = GalaxyContentButton(title: "", target: self, action: #selector(toggleAccounts))
        button.identifier = NSUserInterfaceItemIdentifier("header-profile")
        button.isBordered = false
        button.wantsLayer = true
        button.layer?.cornerRadius = 13
        button.layer?.borderWidth = 1
        button.restingFill = GalaxyTheme.panel
        button.refreshFeedback(animated: false)
        button.toolTip = "Управление аккаунтами"
        button.keyEquivalent = "2"
        button.keyEquivalentModifierMask = [.command]
        profileAvatar.wantsLayer = true
        profileAvatar.layer?.cornerRadius = 8
        profileAvatar.layer?.masksToBounds = true
        profileAvatar.layer?.backgroundColor = GalaxyTheme.blue.withAlphaComponent(0.20).cgColor
        profileAvatar.imageScaling = .scaleProportionallyUpOrDown
        profileAvatar.contentTintColor = GalaxyTheme.cyan
        profileName.font = .systemFont(ofSize: 13, weight: .semibold)
        profileName.textColor = .white
        profileName.lineBreakMode = .byTruncatingTail
        profileSubtitle.font = .systemFont(ofSize: 10, weight: .medium)
        profileSubtitle.textColor = GalaxyTheme.muted
        profileSubtitle.lineBreakMode = .byTruncatingTail
        let arrow = profileArrow
        arrow.image = NSImage(systemSymbolName: "chevron.down", accessibilityDescription: nil)
        arrow.contentTintColor = GalaxyTheme.muted
        add([profileAvatar, profileName, profileSubtitle, arrow], to: button)
        NSLayoutConstraint.activate([
            profileAvatar.leadingAnchor.constraint(equalTo: button.leadingAnchor, constant: 9),
            profileAvatar.centerYAnchor.constraint(equalTo: button.centerYAnchor),
            profileAvatar.widthAnchor.constraint(equalToConstant: 30),
            profileAvatar.heightAnchor.constraint(equalTo: profileAvatar.widthAnchor),
            profileName.leadingAnchor.constraint(equalTo: profileAvatar.trailingAnchor, constant: 8),
            profileName.trailingAnchor.constraint(equalTo: arrow.leadingAnchor, constant: -8),
            profileName.topAnchor.constraint(equalTo: button.topAnchor, constant: 7),
            profileSubtitle.leadingAnchor.constraint(equalTo: profileName.leadingAnchor),
            profileSubtitle.trailingAnchor.constraint(equalTo: profileName.trailingAnchor),
            profileSubtitle.topAnchor.constraint(equalTo: profileName.bottomAnchor, constant: 1),
            arrow.trailingAnchor.constraint(equalTo: button.trailingAnchor, constant: -11),
            arrow.centerYAnchor.constraint(equalTo: button.centerYAnchor),
            arrow.widthAnchor.constraint(equalToConstant: 11),
            arrow.heightAnchor.constraint(equalToConstant: 11)
        ])
        profileButton = button
        return button
    }

    private func makeHeaderPanel(_ body: NSView, id: String, anchor: NSView, size: NSSize) -> NSVisualEffectView {
        let panel = NSVisualEffectView()
        panel.identifier = NSUserInterfaceItemIdentifier(id)
        panel.material = .popover
        panel.blendingMode = .withinWindow
        panel.state = .active
        panel.wantsLayer = true
        panel.layer?.cornerRadius = 16
        panel.layer?.masksToBounds = true
        panel.layer?.borderWidth = 1
        panel.layer?.borderColor = NSColor.white.withAlphaComponent(0.18).cgColor
        panel.isHidden = true
        let tint = NSView()
        tint.wantsLayer = true
        tint.layer?.backgroundColor = NSColor(srgbRed: 0.078, green: 0.071, blue: 0.13, alpha: 0.93).cgColor
        add([panel], to: window.contentView!)
        add([tint], to: panel)
        add([body], to: tint)
        NSLayoutConstraint.activate([
            panel.topAnchor.constraint(equalTo: anchor.bottomAnchor, constant: 9),
            panel.trailingAnchor.constraint(equalTo: anchor.trailingAnchor),
            panel.widthAnchor.constraint(equalToConstant: size.width),
            panel.heightAnchor.constraint(equalToConstant: size.height),
            tint.topAnchor.constraint(equalTo: panel.topAnchor),
            tint.leadingAnchor.constraint(equalTo: panel.leadingAnchor),
            tint.trailingAnchor.constraint(equalTo: panel.trailingAnchor),
            tint.bottomAnchor.constraint(equalTo: panel.bottomAnchor),
            body.topAnchor.constraint(equalTo: tint.topAnchor, constant: 16),
            body.leadingAnchor.constraint(equalTo: tint.leadingAnchor, constant: 16),
            body.trailingAnchor.constraint(equalTo: tint.trailingAnchor, constant: -16),
            body.bottomAnchor.constraint(equalTo: tint.bottomAnchor, constant: -16)
        ])
        return panel
    }

    private func toggleHeaderPanel(_ kind: HeaderPanel) {
        if openPanel == kind { closeHeaderPanel() }
        else { showHeaderPanel(kind) }
    }

    private func showHeaderPanel(_ kind: HeaderPanel) {
        guard openPanel != kind, window.attachedSheet == nil else { return }
        closeHeaderPanel()
        openPanel = kind
        let panel = kind == .accounts ? accountsPanel! : settingsPanel!
        panel.isHidden = false
        profileButton.selectedAppearance = kind == .accounts
        profileButton.setAccessibilityExpanded(kind == .accounts)
        profileArrow.image = NSImage(systemSymbolName: kind == .accounts ? "chevron.up" : "chevron.down",
                                     accessibilityDescription: nil)
        settingsButton.selected = kind == .settings
        settingsButton.setAccessibilityExpanded(kind == .settings)
        refreshLaunchButton()
        window.contentView?.layoutSubtreeIfNeeded()
        if !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion {
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 0
            fade.toValue = 1
            let slide = CABasicAnimation(keyPath: "transform.translation.y")
            slide.fromValue = 6
            slide.toValue = 0
            let reveal = CAAnimationGroup()
            reveal.animations = [fade, slide]
            reveal.duration = 0.18
            reveal.timingFunction = CAMediaTimingFunction(name: .easeOut)
            panel.layer?.add(reveal, forKey: "header-panel-reveal")
        }
        panelEventMonitor = NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .keyDown]) {
            [weak self, weak panel] event in
            guard let self, let panel, event.window === self.window, self.window.attachedSheet == nil else { return event }
            if event.type == .keyDown {
                guard event.keyCode == 53 else { return event }
                let button: NSButton = kind == .accounts ? self.profileButton : self.settingsButton
                self.closeHeaderPanel()
                self.window.makeFirstResponder(button)
                return nil
            }
            let point = event.locationInWindow
            if ![panel as NSView, self.profileButton, self.settingsButton].contains(where: {
                $0.bounds.contains($0.convert(point, from: nil))
            }) {
                self.closeHeaderPanel()
            }
            return event
        }
        if kind == .accounts {
            revealSelectedAccount()
            accounts.accounts.forEach { queueAccountRefresh($0.id) }
        }
    }

    private func closeHeaderPanel() {
        guard openPanel != nil else { return }
        window.makeFirstResponder(nil)
        openPanel = nil
        accountsPanel.isHidden = true
        settingsPanel.isHidden = true
        profileButton.selectedAppearance = false
        profileButton.setAccessibilityExpanded(false)
        profileArrow.image = NSImage(systemSymbolName: "chevron.down", accessibilityDescription: nil)
        settingsButton.selected = false
        settingsButton.setAccessibilityExpanded(false)
        if let panelEventMonitor { NSEvent.removeMonitor(panelEventMonitor) }
        panelEventMonitor = nil
        refreshLaunchButton()
    }

    @objc private func toggleAccounts() { toggleHeaderPanel(.accounts) }
    @objc private func toggleSettings() { toggleHeaderPanel(.settings) }

    @objc private func selectPage(_ sender: NSButton) { showPage(sender.tag) }

    private func showPage(_ index: Int) {
        guard let item = pages[index] else { return }
        closeHeaderPanel()
        if tabView.selectedTabViewItem !== item {
            window.makeFirstResponder(nil)
            tabView.selectTabViewItem(item)
        }
        for button in navigationButtons.values {
            button.selected = button.tag == index
            button.setAccessibilityValue(button.selected ? "Выбрано" : "")
        }
        refreshLaunchButton()
        if index == 4 {
            preferences.lastReadChangelogVersion = MCGLLauncherUpdater.currentVersion
            navigationButtons[index]?.title = "Изменения"
            navigationButtons[index]?.setAccessibilityLabel("Изменения")
        }
    }

    private func refreshLaunchButton() {
        guard let launchButton, let tabView else { return }
        launchButton.title = launchState.title
        launchButton.setAccessibilityLabel(launchState.title)
        launchButton.symbol = launchState.symbol
        launchButton.style = launchState == .running ? .secondary : .primary
        launchButton.isEnabled = launchState.isActionEnabled && !installingLauncherUpdate
        refreshRendererButtons()
        let isPlayPage = tabView.selectedTabViewItem?.identifier as? String == "play"
        updateMetalStars()
        let acceptsReturn = isPlayPage && openPanel == nil && launchState.acceptsReturn && !installingLauncherUpdate
        launchButton.keyEquivalent = acceptsReturn ? "\r" : ""
        window.defaultButtonCell = acceptsReturn ? launchButton.cell as? NSButtonCell : nil
        launchButton.needsDisplay = true
        refreshAccountControls()
    }

    @objc private func accessibilityDisplayChanged(_ notification: Notification) { updateMetalStars() }

    private func updateMetalStars() {
        guard let metal = rendererButtons[.metal] as? GalaxyMetalButton else { return }
        metal.setStarsActive(metal.isEnabled && window.isKeyWindow && window.occlusionState.contains(.visible)
                             && !window.isMiniaturized
                             && openPanel == nil && tabView.selectedTabViewItem === pages[0]
                             && !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion)
    }

#if MCGL_LAUNCHER_TEST
    // UI-state tests cannot spawn a game, authenticate or run the installer.
    func previewLaunchStateForTesting(_ state: MCGLLaunchState) {
        launchState = state
    }

    func previewProjectNewsForTesting(_ items: [MCGLProjectNews]) {
        showProjectNews(items)
    }

    func previewUpdatePhaseForTesting(_ phase: UpdatePhase) {
        showUpdatePhase(phase, status: "Проверка при запуске")
    }

    func previewUpdateProgressForTesting(received: Int64, total: Int64) {
        showLauncherDownloadProgress(received: received, total: total)
    }

    func previewMetalStarsForTesting(_ active: Bool) {
        (rendererButtons[.metal] as? GalaxyMetalButton)?.setStarsActive(active)
    }
#endif

    @objc private func openRepository() {
        NSWorkspace.shared.open(URL(string: "https://github.com/prilepv/mcgl-arm64")!)
    }

    private func makeCardView(radius: CGFloat = 16) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        view.layer?.backgroundColor = GalaxyTheme.panel.cgColor
        view.layer?.borderColor = GalaxyTheme.line.cgColor
        view.layer?.borderWidth = 1
        view.layer?.cornerRadius = radius
        return view
    }

    private func makePlayTab() -> NSView {
        let page = NSView()
        let rendererPanel = makeCardView(radius: 19)
        let panelHeading = label("Режим графики", size: 15, weight: .semibold)
        panelHeading.identifier = NSUserInterfaceItemIdentifier("renderer-heading")
        let options = NSStackView(views: MCGLRenderer.allCases.map { makeRendererButton($0) })
        options.orientation = .vertical
        options.alignment = .leading
        options.spacing = 8
        add([panelHeading, options], to: rendererPanel)
        for card in options.arrangedSubviews {
            card.widthAnchor.constraint(equalTo: options.widthAnchor).isActive = true
        }

        let edition = makeCardView(radius: 10)
        let editionLabel = label("APPLE SILICON", size: 11, weight: .semibold, color: GalaxyTheme.cyan)
        add([editionLabel], to: edition)
        let hero = label("Minecraft Galaxy", size: 42, weight: .bold)
        let description = label("Галактика на твоём Mac", size: 15, color: GalaxyTheme.muted)
        launchButton = actionButton("Запустить игру", symbol: "play.fill",
                                     action: #selector(toggleGame), style: .primary)
        launchButton.identifier = NSUserInterfaceItemIdentifier("launch")
        let versionCard = makeCardView(radius: 12)
        let versionCaption = label("ВЕРСИЯ ЛАУНЧЕРА", size: 9, weight: .medium, color: GalaxyTheme.muted)
        let version = label(MCGLLauncherUpdater.currentVersion, size: 14, weight: .semibold)
        add([versionCaption, version], to: versionCard)
        statusLabel = NSTextField(wrappingLabelWithString: "")
        statusLabel.font = .systemFont(ofSize: 12, weight: .medium)
        statusLabel.textColor = GalaxyTheme.cyan
        statusLabel.maximumNumberOfLines = 2
        statusLabel.identifier = NSUserInterfaceItemIdentifier("launch-status")
        statusLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        collapsedLaunchStatus = statusLabel.heightAnchor.constraint(equalToConstant: 0)
        setLaunchStatus("Готов к запуску")
        let news = makeNewsPanel()
        let launcherActions = makeLauncherActions()
        add([rendererPanel, edition, hero, description, launchButton, versionCard, statusLabel, news, launcherActions], to: page)
        NSLayoutConstraint.activate([
            rendererPanel.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            rendererPanel.topAnchor.constraint(equalTo: page.topAnchor, constant: 2),
            rendererPanel.bottomAnchor.constraint(equalTo: options.bottomAnchor, constant: 12),
            rendererPanel.widthAnchor.constraint(equalToConstant: 222),
            panelHeading.leadingAnchor.constraint(equalTo: rendererPanel.leadingAnchor, constant: 17),
            panelHeading.topAnchor.constraint(equalTo: rendererPanel.topAnchor, constant: 19),
            options.topAnchor.constraint(equalTo: panelHeading.bottomAnchor, constant: 14),
            options.leadingAnchor.constraint(equalTo: rendererPanel.leadingAnchor, constant: 10),
            options.trailingAnchor.constraint(equalTo: rendererPanel.trailingAnchor, constant: -10),
            edition.topAnchor.constraint(equalTo: page.topAnchor, constant: 13),
            edition.leadingAnchor.constraint(equalTo: rendererPanel.trailingAnchor, constant: 30),
            edition.heightAnchor.constraint(equalToConstant: 30),
            editionLabel.centerYAnchor.constraint(equalTo: edition.centerYAnchor),
            editionLabel.leadingAnchor.constraint(equalTo: edition.leadingAnchor, constant: 12),
            editionLabel.trailingAnchor.constraint(equalTo: edition.trailingAnchor, constant: -12),
            hero.leadingAnchor.constraint(equalTo: edition.leadingAnchor),
            hero.topAnchor.constraint(equalTo: edition.bottomAnchor, constant: 16),
            description.leadingAnchor.constraint(equalTo: edition.leadingAnchor),
            description.topAnchor.constraint(equalTo: hero.bottomAnchor, constant: 9),
            launchButton.leadingAnchor.constraint(equalTo: edition.leadingAnchor),
            launchButton.topAnchor.constraint(equalTo: description.bottomAnchor, constant: 20),
            launchButton.widthAnchor.constraint(equalToConstant: 192),
            launchButton.heightAnchor.constraint(equalToConstant: 54),
            versionCard.leadingAnchor.constraint(equalTo: launchButton.trailingAnchor, constant: 10),
            versionCard.topAnchor.constraint(equalTo: launchButton.topAnchor),
            versionCard.heightAnchor.constraint(equalTo: launchButton.heightAnchor),
            versionCard.widthAnchor.constraint(equalToConstant: 142),
            versionCaption.leadingAnchor.constraint(equalTo: versionCard.leadingAnchor, constant: 14),
            versionCaption.topAnchor.constraint(equalTo: versionCard.topAnchor, constant: 10),
            version.leadingAnchor.constraint(equalTo: versionCaption.leadingAnchor),
            version.topAnchor.constraint(equalTo: versionCaption.bottomAnchor, constant: 3),
            statusLabel.leadingAnchor.constraint(equalTo: launchButton.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            statusLabel.topAnchor.constraint(equalTo: launchButton.bottomAnchor, constant: 8),
            news.leadingAnchor.constraint(equalTo: edition.leadingAnchor),
            news.trailingAnchor.constraint(equalTo: page.trailingAnchor, constant: -1),
            news.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 12),
            news.bottomAnchor.constraint(equalTo: page.bottomAnchor),
            launcherActions.leadingAnchor.constraint(equalTo: rendererPanel.leadingAnchor),
            launcherActions.trailingAnchor.constraint(equalTo: rendererPanel.trailingAnchor),
            launcherActions.bottomAnchor.constraint(equalTo: news.bottomAnchor)
        ])
        return page
    }

    private func makeNewsPanel() -> NSView {
        let panel = makeCardView(radius: 18)
        panel.identifier = NSUserInterfaceItemIdentifier("project-news")
        panel.layer?.backgroundColor = NSColor(srgbRed: 0.10, green: 0.085, blue: 0.17, alpha: 1).cgColor
        panel.layer?.borderColor = GalaxyTheme.cyan.withAlphaComponent(0.20).cgColor
        let tile = NSView()
        tile.wantsLayer = true
        tile.layer?.cornerRadius = 8
        tile.layer?.backgroundColor = GalaxyTheme.cyan.withAlphaComponent(0.13).cgColor
        let icon = NSImageView(image: NSImage(systemSymbolName: "newspaper", accessibilityDescription: nil)!)
        icon.contentTintColor = GalaxyTheme.cyan
        let heading = label("Новости проекта", size: 18, weight: .semibold)
        let all = actionButton("Все новости", symbol: "arrow.up.right", action: #selector(openAllNews))
        all.identifier = NSUserInterfaceItemIdentifier("all-project-news")
        all.toolTip = "Открыть новости на официальном форуме MCGL"

        let well = makeCardView(radius: 12)
        well.layer?.backgroundColor = NSColor(srgbRed: 0.054, green: 0.045, blue: 0.099, alpha: 1).cgColor
        well.layer?.borderColor = GalaxyTheme.cyan.withAlphaComponent(0.11).cgColor
        let scroll = NSScrollView()
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.contentView = VerticalLogClipView()
        scroll.contentView.drawsBackground = false
        let document = MCGLAccountsDocumentView()
        document.translatesAutoresizingMaskIntoConstraints = false
        scroll.documentView = document
        newsRows = verticalStack([], spacing: 4)
        newsRows.distribution = .fillEqually
        let fitViewport = document.heightAnchor.constraint(equalTo: scroll.contentView.heightAnchor)
        fitViewport.priority = .defaultHigh
        add([tile, heading, all, well], to: panel)
        add([scroll], to: well)
        add([icon], to: tile)
        add([newsRows], to: document)
        NSLayoutConstraint.activate([
            tile.leadingAnchor.constraint(equalTo: panel.leadingAnchor, constant: 14),
            tile.centerYAnchor.constraint(equalTo: all.centerYAnchor),
            tile.widthAnchor.constraint(equalToConstant: 30),
            tile.heightAnchor.constraint(equalTo: tile.widthAnchor),
            icon.centerXAnchor.constraint(equalTo: tile.centerXAnchor),
            icon.centerYAnchor.constraint(equalTo: tile.centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 16),
            icon.heightAnchor.constraint(equalTo: icon.widthAnchor),
            heading.leadingAnchor.constraint(equalTo: tile.trailingAnchor, constant: 9),
            heading.centerYAnchor.constraint(equalTo: all.centerYAnchor),
            all.trailingAnchor.constraint(equalTo: panel.trailingAnchor, constant: -13),
            all.topAnchor.constraint(equalTo: panel.topAnchor, constant: 10),
            all.widthAnchor.constraint(equalToConstant: 127),
            all.heightAnchor.constraint(equalToConstant: 32),
            well.topAnchor.constraint(equalTo: all.bottomAnchor, constant: 8),
            well.leadingAnchor.constraint(equalTo: panel.leadingAnchor, constant: 10),
            well.trailingAnchor.constraint(equalTo: panel.trailingAnchor, constant: -10),
            well.bottomAnchor.constraint(equalTo: panel.bottomAnchor, constant: -10),
            scroll.topAnchor.constraint(equalTo: well.topAnchor, constant: 5),
            scroll.leadingAnchor.constraint(equalTo: well.leadingAnchor, constant: 5),
            scroll.trailingAnchor.constraint(equalTo: well.trailingAnchor, constant: -5),
            scroll.bottomAnchor.constraint(equalTo: well.bottomAnchor, constant: -5),
            fitViewport,
            document.widthAnchor.constraint(equalTo: scroll.contentView.widthAnchor),
            newsRows.topAnchor.constraint(equalTo: document.topAnchor),
            newsRows.leadingAnchor.constraint(equalTo: document.leadingAnchor),
            newsRows.trailingAnchor.constraint(equalTo: document.trailingAnchor),
            newsRows.bottomAnchor.constraint(equalTo: document.bottomAnchor)
        ])
        showNewsMessage("Загружаем новости MCGL…")
        return panel
    }

    private func showNewsMessage(_ text: String) {
        let row = NSView()
        let message = label(text, size: 12, color: GalaxyTheme.muted)
        message.alignment = .center
        message.lineBreakMode = .byTruncatingTail
        message.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        newsRows.addArrangedSubview(row)
        add([message], to: row)
        NSLayoutConstraint.activate([
            row.widthAnchor.constraint(equalTo: newsRows.widthAnchor),
            row.heightAnchor.constraint(greaterThanOrEqualToConstant: 29),
            message.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 8),
            message.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -8),
            message.centerYAnchor.constraint(equalTo: row.centerYAnchor)
        ])
    }

    private func showProjectNews(_ items: [MCGLProjectNews]) {
        newsItems = items
        newsRows.arrangedSubviews.forEach { newsRows.removeArrangedSubview($0); $0.removeFromSuperview() }
        if items.isEmpty {
            showNewsMessage("Новости временно недоступны. Открыть ленту можно на форуме.")
            return
        }
        for (index, item) in items.enumerated() {
            let row = GalaxyContentButton(title: "", target: self, action: #selector(openNewsItem(_:)))
            row.tag = index
            row.identifier = NSUserInterfaceItemIdentifier("project-news-item-\(index)")
            row.isBordered = false
            row.wantsLayer = true
            row.layer?.cornerRadius = 8
            row.layer?.borderWidth = 1
            row.restingFill = NSColor(srgbRed: 0.105, green: 0.09, blue: 0.16, alpha: 1)
            row.restingBorder = NSColor.white.withAlphaComponent(0.055)
            row.refreshFeedback(animated: false)
            let title = item.title ?? formattedNewsDate(item.date)
            row.setAccessibilityLabel([title, item.text].filter { !$0.isEmpty }.joined(separator: ". "))

            let symbol = NSImageView()
            symbol.image = NSImage(systemSymbolName: item.symbol, accessibilityDescription: nil)
            symbol.contentTintColor = GalaxyTheme.cyan
            let headline = label(title, size: item.title == nil ? 11 : 14, weight: .semibold,
                                 color: item.title == nil ? GalaxyTheme.cyan : .white)
            let summary = label(item.text, size: 12, color: GalaxyTheme.muted)
            for field in [headline, summary] {
                field.lineBreakMode = .byTruncatingTail
                field.maximumNumberOfLines = 1
                // Let the preview truncate before AppKit grows the window to fit it.
                field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            }
            let arrow = NSImageView()
            arrow.image = NSImage(systemSymbolName: item.link == nil ? "chevron.right" : "arrow.up.right",
                                  accessibilityDescription: nil)
            arrow.contentTintColor = GalaxyTheme.muted
            let text = verticalStack([headline, summary], spacing: 3)
            add([symbol, text, arrow], to: row)
            newsRows.addArrangedSubview(row)
            NSLayoutConstraint.activate([
                row.widthAnchor.constraint(equalTo: newsRows.widthAnchor),
                row.heightAnchor.constraint(greaterThanOrEqualToConstant: Self.newsRowHeight),
                symbol.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 12),
                symbol.centerYAnchor.constraint(equalTo: row.centerYAnchor),
                symbol.widthAnchor.constraint(equalToConstant: 20),
                symbol.heightAnchor.constraint(equalTo: symbol.widthAnchor),
                text.leadingAnchor.constraint(equalTo: symbol.trailingAnchor, constant: 10),
                text.trailingAnchor.constraint(equalTo: arrow.leadingAnchor, constant: -10),
                text.centerYAnchor.constraint(equalTo: row.centerYAnchor),
                headline.widthAnchor.constraint(equalTo: text.widthAnchor),
                summary.widthAnchor.constraint(equalTo: text.widthAnchor),
                arrow.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -11),
                arrow.centerYAnchor.constraint(equalTo: row.centerYAnchor),
                arrow.widthAnchor.constraint(equalToConstant: 10),
                arrow.heightAnchor.constraint(equalTo: arrow.widthAnchor)
            ])
        }
    }

    private func formattedNewsDate(_ date: String?) -> String {
        guard let date else { return "Объявление" }
        let parser = DateFormatter()
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd"
        guard let value = parser.date(from: date) else { return date }
        let display = DateFormatter()
        display.locale = Locale(identifier: "ru_RU")
        display.dateFormat = "d MMMM yyyy"
        return display.string(from: value)
    }

    @objc private func openAllNews() { NSWorkspace.shared.open(MCGLProjectNews.allNews) }

    @objc private func openNewsItem(_ sender: NSButton) {
        guard newsItems.indices.contains(sender.tag) else { return }
        let item = newsItems[sender.tag]
        if let link = item.link {
            NSWorkspace.shared.open(link)
            return
        }
        let alert = NSAlert()
        alert.window.appearance = NSAppearance(named: .darkAqua)
        alert.messageText = "Объявление MCGL · \(formattedNewsDate(item.date))"
        alert.informativeText = item.text
        alert.addButton(withTitle: "Закрыть")
        alert.beginSheetModal(for: window)
    }

    private func makeRendererButton(_ renderer: MCGLRenderer) -> GalaxyContentButton {
        let metal = renderer == .metal
        let accent = metal ? GalaxyTheme.cyan : NSColor(srgbRed: 0.57, green: 0.83, blue: 0.87, alpha: 1)
        let button: GalaxyContentButton = metal ? GalaxyMetalButton(frame: .zero) : GalaxyContentButton(frame: .zero)
        button.title = ""
        button.identifier = NSUserInterfaceItemIdentifier("renderer-\(renderer.rawValue)")
        button.setAccessibilityLabel(renderer.title)
        button.setButtonType(.pushOnPushOff)
        button.isBordered = false
        button.wantsLayer = true
        button.layer?.cornerRadius = 12
        button.layer?.borderWidth = 1
        if metal {
            let tint = GalaxyTheme.blue.blended(withFraction: 0.25, of: GalaxyTheme.cyan) ?? GalaxyTheme.blue
            button.setGlowTint(tint, strength: 0.48)
            button.restingBorder = tint.withAlphaComponent(0.42)
        }
        button.toolTip = metal ? "Нативная графика на Apple Silicon" : "Режим совместимости"
        button.target = self
        button.action = #selector(rendererChanged(_:))
        rendererButtons[renderer] = button

        let markTile = NSView()
        markTile.wantsLayer = true
        markTile.layer?.cornerRadius = 8
        markTile.layer?.backgroundColor = accent.withAlphaComponent(0.12).cgColor
        let mark = label(metal ? "M" : "GL", size: 12, color: accent)
        mark.font = .monospacedSystemFont(ofSize: 12, weight: .semibold)
        mark.identifier = NSUserInterfaceItemIdentifier("renderer-mark")
        mark.setAccessibilityElement(false)
        let title = label(renderer.title, size: 15, weight: .semibold)
        let detail = label(metal ? "Для Apple Silicon" : "Совместимость", size: 11, color: GalaxyTheme.muted)
        add([markTile, title, detail], to: button)
        add([mark], to: markTile)
        NSLayoutConstraint.activate([
            button.heightAnchor.constraint(equalToConstant: 61),
            markTile.leadingAnchor.constraint(equalTo: button.leadingAnchor, constant: 12),
            markTile.centerYAnchor.constraint(equalTo: button.centerYAnchor),
            markTile.widthAnchor.constraint(equalToConstant: 30),
            markTile.heightAnchor.constraint(equalTo: markTile.widthAnchor),
            mark.centerXAnchor.constraint(equalTo: markTile.centerXAnchor),
            mark.centerYAnchor.constraint(equalTo: markTile.centerYAnchor),
            title.leadingAnchor.constraint(equalTo: markTile.trailingAnchor, constant: 10),
            title.topAnchor.constraint(equalTo: button.topAnchor, constant: 13),
            detail.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            detail.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 4)
        ])
        let indicator = NSImageView()
        indicator.identifier = NSUserInterfaceItemIdentifier("renderer-check")
        indicator.image = NSImage(systemSymbolName: "checkmark.circle.fill", accessibilityDescription: nil)
        indicator.contentTintColor = GalaxyTheme.cyan
        add([indicator], to: button)
        NSLayoutConstraint.activate([
            indicator.trailingAnchor.constraint(equalTo: button.trailingAnchor, constant: -10),
            indicator.centerYAnchor.constraint(equalTo: button.centerYAnchor),
            indicator.widthAnchor.constraint(equalToConstant: 14),
            indicator.heightAnchor.constraint(equalTo: indicator.widthAnchor)
        ])
        if metal {
            let badge = NSView()
            badge.identifier = NSUserInterfaceItemIdentifier("renderer-new")
            badge.wantsLayer = true
            badge.layer?.cornerRadius = 6
            badge.layer?.backgroundColor = NSColor(srgbRed: 0.75, green: 0.64, blue: 1, alpha: 1).cgColor
            let text = label("NEW", size: 8, weight: .heavy, color: NSColor(srgbRed: 0.16, green: 0.09, blue: 0.32, alpha: 1))
            add([badge], to: button)
            add([text], to: badge)
            NSLayoutConstraint.activate([
                badge.centerYAnchor.constraint(equalTo: title.centerYAnchor),
                badge.leadingAnchor.constraint(equalTo: title.trailingAnchor, constant: 6),
                badge.widthAnchor.constraint(equalToConstant: 29),
                badge.heightAnchor.constraint(equalToConstant: 14),
                text.centerXAnchor.constraint(equalTo: badge.centerXAnchor),
                text.centerYAnchor.constraint(equalTo: badge.centerYAnchor)
            ])
        }
        return button
    }

    private var selectedRenderer: MCGLRenderer { hasMetalRenderer ? preferences.renderer : .openGL }
    private var metalLibraryURL: URL { resourcesRoot.appendingPathComponent("libmcgl-metal-world-direct.dylib") }
    private var bgfxLibraryURL: URL { resourcesRoot.appendingPathComponent("Bgfx/bgfx-window.dylib") }
    private var hasMetalRenderer: Bool { FileManager.default.isReadableFile(atPath: metalLibraryURL.path) }

    private func refreshRendererButtons() {
        let available = hasMetalRenderer
        let selected = available ? preferences.renderer : .openGL
        let editable = launchState == .ready && !installingLauncherUpdate
        metalVSyncButton.isEnabled = editable && available && FileManager.default.isReadableFile(atPath: bgfxLibraryURL.path)
        for (renderer, button) in rendererButtons {
            let supported = renderer != .metal || available
            let active = renderer == selected
            button.isEnabled = supported && editable
            button.alphaValue = supported ? 1 : 0.45
            button.state = active ? .on : .off
            button.selectedAppearance = active
            button.subviews.first { $0.identifier?.rawValue == "renderer-check" }?.isHidden = !active
        }
    }

    @objc private func rendererChanged(_ sender: GalaxyContentButton) {
        guard launchState == .ready, !installingLauncherUpdate,
              let renderer = rendererButtons.first(where: { $0.value === sender })?.key,
              renderer != .metal || hasMetalRenderer else { return }
        preferences.renderer = renderer
        refreshRendererButtons()
    }

    private func makeAccountsPanel() -> NSView {
        let page = NSView()
        let heading = label("Аккаунты", size: 21, weight: .semibold)
        let caption = note("Выбери профиль, с которым хочешь запустить игру.")
        let credentials = makeCardView(radius: 10)
        credentialsView = credentials
        let passwordCaption = label("Пароль MCGL", size: 11, weight: .semibold, color: GalaxyTheme.muted)
        passwordCaption.lineBreakMode = .byTruncatingTail
        passwordField = NSSecureTextField()
        passwordField.identifier = NSUserInterfaceItemIdentifier("password")
        passwordField.delegate = self
        passwordField.setAccessibilityLabel("Пароль MCGL")
        styleField(passwordField)
        rememberPasswordButton = GalaxyInteractiveButton(checkboxWithTitle: "Сохранить пароль на этом Mac",
                                         target: self, action: #selector(rememberPasswordChanged))
        rememberPasswordButton.identifier = NSUserInterfaceItemIdentifier("remember-password")
        styleCheckbox(rememberPasswordButton)
        add([passwordCaption, passwordField, rememberPasswordButton], to: credentials)
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
        let footer = note("Профессия и уровни загружаются с форума MCGL по нику.")
        add([heading, caption, addButton, refresh, accountCountLabel, scroll, footer], to: page)
        NSLayoutConstraint.activate([
            heading.topAnchor.constraint(equalTo: page.topAnchor, constant: 4),
            heading.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            caption.topAnchor.constraint(equalTo: heading.bottomAnchor, constant: 6),
            caption.leadingAnchor.constraint(equalTo: heading.leadingAnchor),
            addButton.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            addButton.topAnchor.constraint(equalTo: heading.topAnchor),
            addButton.widthAnchor.constraint(equalToConstant: 130), addButton.heightAnchor.constraint(equalToConstant: 38),
            passwordCaption.leadingAnchor.constraint(equalTo: credentials.leadingAnchor, constant: 16),
            passwordCaption.trailingAnchor.constraint(equalTo: credentials.trailingAnchor, constant: -16),
            passwordCaption.topAnchor.constraint(equalTo: credentials.topAnchor, constant: 9),
            passwordField.leadingAnchor.constraint(equalTo: passwordCaption.leadingAnchor),
            passwordField.topAnchor.constraint(equalTo: passwordCaption.bottomAnchor, constant: 4),
            passwordField.widthAnchor.constraint(equalToConstant: 222),
            passwordField.heightAnchor.constraint(equalToConstant: 32),
            rememberPasswordButton.leadingAnchor.constraint(equalTo: passwordField.trailingAnchor, constant: 14),
            rememberPasswordButton.centerYAnchor.constraint(equalTo: passwordField.centerYAnchor),
            rememberPasswordButton.trailingAnchor.constraint(lessThanOrEqualTo: credentials.trailingAnchor, constant: -16),
            refresh.topAnchor.constraint(equalTo: caption.bottomAnchor, constant: 14),
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
        credentialsView.removeFromSuperview()
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
                remove.topAnchor.constraint(equalTo: card.topAnchor, constant: 103),
                remove.widthAnchor.constraint(equalToConstant: 32), remove.heightAnchor.constraint(equalToConstant: 29),
                rename.trailingAnchor.constraint(equalTo: remove.leadingAnchor, constant: -6),
                rename.centerYAnchor.constraint(equalTo: remove.centerYAnchor),
                rename.widthAnchor.constraint(equalTo: remove.widthAnchor), rename.heightAnchor.constraint(equalTo: remove.heightAnchor),
                choose.trailingAnchor.constraint(equalTo: rename.leadingAnchor, constant: -8),
                choose.centerYAnchor.constraint(equalTo: remove.centerYAnchor),
                choose.widthAnchor.constraint(equalToConstant: 105), choose.heightAnchor.constraint(equalTo: remove.heightAnchor)
            ])
            card.reserveFooterSpace(before: choose)
            if accounts.selected?.id == account.id { card.attachCredentials(credentialsView) }
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
        passwordField.setAccessibilityLabel(selected.map { "Пароль MCGL, \($0.nickname)" } ?? "Пароль MCGL")
        passwordField.placeholderString = "Введи пароль"
        profileName.stringValue = selected?.nickname ?? "Выбрать аккаунт"
        profileSubtitle.stringValue = selected?.info?.professionTitle ?? "Аккаунт MCGL"
        profileAvatar.image = selected?.info?.avatar.flatMap(MCGLAccountCard.thumbnail)
            ?? NSImage(systemSymbolName: "person.fill", accessibilityDescription: nil)
        profileButton?.setAccessibilityLabel("Аккаунт: \(profileName.stringValue)")
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
        let editable = launchState == .ready && !installingLauncherUpdate
        passwordField?.isEnabled = editable && accounts.selected != nil && accounts.loadError == nil
        rememberPasswordButton?.isEnabled = editable && accounts.selected != nil && accounts.loadError == nil
        rememberPasswordButton?.toolTip = accounts.selected == nil
            ? "Для сохранения пароля добавь и выбери аккаунт."
            : "Без Связки ключей и мастер-пароля. Программа с доступом к твоим файлам сможет восстановить пароль."
        accountEditingButtons.forEach { $0.isEnabled = editable && accounts.loadError == nil }
    }

    private func applyAccountSelection() {
        passwordField.stringValue = ""
        rebuildAccounts()
        setLaunchStatus("Готов к запуску")
        restoreSelectedPassword()
        if let selected = accounts.selected {
            if openPanel == .accounts { revealSelectedAccount() }
            queueAccountRefresh(selected.id)
        }
    }

    private func revealSelectedAccount() {
        guard let selected = accounts.selected, let card = accountCards[selected.id] else { return }
        accountsPanel.layoutSubtreeIfNeeded()
        card.scrollToVisible(card.bounds.insetBy(dx: 0, dy: -2))
    }

    @objc private func selectAccount(_ sender: NSButton) {
        guard accounts.accounts.indices.contains(sender.tag) else { return }
        chooseAccount(accounts.accounts[sender.tag].id)
    }

    private func chooseAccount(_ id: UUID) {
        guard launchState == .ready, !installingLauncherUpdate, accounts.loadError == nil else { return }
        guard accounts.selected?.id != id else { return }
        window.makeFirstResponder(nil)
        do {
            try persistSelectedPassword()
            try accounts.select(id)
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
            setLaunchStatus("Сохранённый пароль недоступен. Можно убрать галочку и ввести его заново.")
        }
    }

    private func persistSelectedPassword() throws {
        guard rememberPasswordButton.state == .on, preferences.localPasswordNoticeAccepted,
              let account = accounts.selected,
              !passwordField.stringValue.isEmpty else { return }
        try passwordStore.save(passwordField.stringValue, for: account)
    }

    @objc private func rememberPasswordChanged() {
        guard launchState == .ready, !installingLauncherUpdate, let account = accounts.selected else { return }
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
        guard launchState == .ready, !installingLauncherUpdate else { return }
        window.makeFirstResponder(nil)
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
            guard let self, response == .alertFirstButtonReturn, self.launchState == .ready,
                  !self.installingLauncherUpdate else { return }
            do {
                try self.persistSelectedPassword()
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

    private func makeSettingsPanel() -> NSView {
        let page = NSView()
        let heading = label("Настройки", size: 21, weight: .semibold)
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
        let memoryHint = note("Начальный объём (Xms) и предел роста (Xmx) для Java.")
        let memoryStack = verticalStack([memoryTitle, memoryRow, memoryHint], spacing: 11)
        add([heading, memoryCard, performanceCard], to: page)
        pin(memoryStack, inside: memoryCard, inset: 16)
        memoryRow.widthAnchor.constraint(equalTo: memoryStack.widthAnchor).isActive = true
        memoryHint.widthAnchor.constraint(equalTo: memoryStack.widthAnchor).isActive = true
        for (popUp, container) in [(initialMemoryPopUp!, initial), (maximumMemoryPopUp!, maximum)] {
            popUp.widthAnchor.constraint(equalTo: container.widthAnchor).isActive = true
            popUp.heightAnchor.constraint(equalToConstant: 30).isActive = true
        }

        let performanceTitle = sectionLabel("Графика и производительность")
        let fpsLabel = valueLabel("Лимит кадров")
        fpsPopUp = GalaxyPopUpButton(frame: .zero, pullsDown: false)
        fpsPopUp.wantsLayer = true
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
        metalVSyncButton = GalaxyInteractiveButton(checkboxWithTitle: "Вертикальная синхронизация (VSync) · только Metal",
                                    target: self, action: #selector(metalVSyncChanged))
        metalVSyncButton.identifier = NSUserInterfaceItemIdentifier("metalVSync")
        metalVSyncButton.state = preferences.metalVSync ? .on : .off
        metalVSyncButton.toolTip = "Синхронизирует вывод кадров Metal с экраном. Применяется при следующем запуске игры."
        multicoreButton = GalaxyInteractiveButton(checkboxWithTitle: "Параллельная сборка мусора · G1GC",
                                   target: self, action: #selector(performanceOptionsChanged))
        multicoreButton.identifier = NSUserInterfaceItemIdentifier("multicoreMemory")
        multicoreButton.state = preferences.multicoreMemory ? .on : .off
        multicoreButton.toolTip = "Сборка мусора использует фоновые потоки; результат зависит от нагрузки."
        graphicsDiagnosticsButton = GalaxyInteractiveButton(checkboxWithTitle: "Диагностика графики в журнале",
                                             target: self, action: #selector(performanceOptionsChanged))
        graphicsDiagnosticsButton.identifier = NSUserInterfaceItemIdentifier("graphicsDiagnostics")
        graphicsDiagnosticsButton.state = preferences.graphicsDiagnostics ? .on : .off
        graphicsDiagnosticsButton.toolTip = "Техническая статистика каждые 5 секунд. Для обычной игры не требуется."
        chunkVboButton = GalaxyInteractiveButton(checkboxWithTitle: "Core-отрисовка чанков · VBO / VAO (всегда включена)",
                                  target: self, action: #selector(performanceOptionsChanged))
        chunkVboButton.identifier = NSUserInterfaceItemIdentifier("chunkVbo")
        chunkVboButton.state = .on
        chunkVboButton.isEnabled = false
        chunkVboButton.toolTip = "В этой тестовой версии игра использует Core 4.1. Прежний переключатель VBO больше не меняет способ отрисовки."
        for button in [metalVSyncButton!, multicoreButton!, graphicsDiagnosticsButton!, chunkVboButton!] {
            styleCheckbox(button)
        }
        let performanceStack = verticalStack(
            [performanceTitle, fpsRow, metalVSyncButton, multicoreButton, chunkVboButton, graphicsDiagnosticsButton], spacing: 10)
        pin(performanceStack, inside: performanceCard, inset: 16)
        let savedHint = note("Настройки сохраняются и применяются при следующем запуске игры.")
        add([savedHint], to: page)
        NSLayoutConstraint.activate([
            heading.topAnchor.constraint(equalTo: page.topAnchor, constant: 4),
            heading.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            memoryCard.topAnchor.constraint(equalTo: heading.bottomAnchor, constant: 14),
            memoryCard.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            memoryCard.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            memoryCard.bottomAnchor.constraint(equalTo: memoryStack.bottomAnchor, constant: 16),
            performanceCard.topAnchor.constraint(equalTo: memoryCard.bottomAnchor, constant: 12),
            performanceCard.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            performanceCard.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            performanceCard.bottomAnchor.constraint(equalTo: performanceStack.bottomAnchor, constant: 16),
            savedHint.topAnchor.constraint(equalTo: performanceCard.bottomAnchor, constant: 12),
            savedHint.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            savedHint.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            savedHint.bottomAnchor.constraint(lessThanOrEqualTo: page.bottomAnchor)
        ])
        return page
    }

    private func makeLauncherActions() -> NSView {
        let actions = NSView()
        updateStatusLabel = note("Проверка при запуске")
        updateStatusLabel.maximumNumberOfLines = 2
        updateStatusLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        updateButton = GalaxyContentButton(title: "", target: self, action: #selector(checkForLauncherUpdate))
        updateButton.identifier = NSUserInterfaceItemIdentifier("launcher-update")
        updateButton.isBordered = false
        updateButton.wantsLayer = true
        updateButton.layer?.cornerRadius = 10
        updateButton.layer?.borderWidth = 1
        updateButton.restingFill = GalaxyTheme.panel
        updateTitle.font = .systemFont(ofSize: 12, weight: .semibold)
        updateTitle.textColor = .white
        updateTitle.lineBreakMode = .byTruncatingTail
        updateTitle.setAccessibilityElement(false)
        updateGlyph.contentTintColor = GalaxyTheme.cyan
        updateSpinner.style = .spinning
        updateSpinner.controlSize = .small
        updateSpinner.isIndeterminate = true
        updateSpinner.identifier = NSUserInterfaceItemIdentifier("launcher-check-spinner")
        updateDownloadProgress.style = .bar
        updateDownloadProgress.controlSize = .small
        updateDownloadProgress.minValue = 0
        updateDownloadProgress.maxValue = 100
        updateDownloadProgress.identifier = NSUserInterfaceItemIdentifier("launcher-download-progress")
        let github = actionButton("GitHub", symbol: "arrow.up.right", action: #selector(openRepository))
        github.identifier = NSUserInterfaceItemIdentifier("launcher-github")
        github.toolTip = "Исходный код лаунчера на GitHub"
        add([updateStatusLabel, updateButton, github], to: actions)
        add([updateGlyph, updateTitle, updateSpinner, updateDownloadProgress], to: updateButton)
        NSLayoutConstraint.activate([
            updateStatusLabel.leadingAnchor.constraint(equalTo: actions.leadingAnchor, constant: 3),
            updateStatusLabel.topAnchor.constraint(equalTo: actions.topAnchor),
            updateStatusLabel.trailingAnchor.constraint(equalTo: actions.trailingAnchor, constant: -3),
            updateButton.topAnchor.constraint(equalTo: updateStatusLabel.bottomAnchor, constant: 6),
            updateButton.leadingAnchor.constraint(equalTo: actions.leadingAnchor),
            updateButton.trailingAnchor.constraint(equalTo: github.leadingAnchor, constant: -8),
            updateButton.bottomAnchor.constraint(equalTo: actions.bottomAnchor),
            updateButton.heightAnchor.constraint(equalToConstant: 40),
            github.trailingAnchor.constraint(equalTo: actions.trailingAnchor),
            github.centerYAnchor.constraint(equalTo: updateButton.centerYAnchor),
            github.widthAnchor.constraint(equalToConstant: 82),
            github.heightAnchor.constraint(equalTo: updateButton.heightAnchor),
            updateGlyph.leadingAnchor.constraint(equalTo: updateButton.leadingAnchor, constant: 10),
            updateGlyph.centerYAnchor.constraint(equalTo: updateButton.centerYAnchor, constant: -1),
            updateGlyph.widthAnchor.constraint(equalToConstant: 15),
            updateGlyph.heightAnchor.constraint(equalTo: updateGlyph.widthAnchor),
            updateSpinner.centerXAnchor.constraint(equalTo: updateGlyph.centerXAnchor),
            updateSpinner.centerYAnchor.constraint(equalTo: updateGlyph.centerYAnchor),
            updateSpinner.widthAnchor.constraint(equalTo: updateGlyph.widthAnchor),
            updateSpinner.heightAnchor.constraint(equalTo: updateGlyph.heightAnchor),
            updateTitle.leadingAnchor.constraint(equalTo: updateGlyph.trailingAnchor, constant: 7),
            updateTitle.trailingAnchor.constraint(equalTo: updateButton.trailingAnchor, constant: -7),
            updateTitle.centerYAnchor.constraint(equalTo: updateGlyph.centerYAnchor),
            updateDownloadProgress.leadingAnchor.constraint(equalTo: updateButton.leadingAnchor, constant: 9),
            updateDownloadProgress.trailingAnchor.constraint(equalTo: updateButton.trailingAnchor, constant: -9),
            updateDownloadProgress.bottomAnchor.constraint(equalTo: updateButton.bottomAnchor, constant: -3),
            updateDownloadProgress.heightAnchor.constraint(equalToConstant: 7)
        ])
        showUpdatePhase(.idle, status: "Проверка при запуске")
        return actions
    }

    private func showUpdatePhase(_ phase: UpdatePhase, status: String, title: String? = nil) {
        if phase != .downloading {
            updateProgressTimer?.invalidate()
            updateProgressTimer = nil
        }
        let symbol: String
        let phaseTitle: String
        switch phase {
        case .idle: symbol = "arrow.clockwise"; phaseTitle = "Обновления"
        case .available: symbol = "arrow.down.circle.fill"; phaseTitle = "Обновление"
        case .checking: symbol = "arrow.clockwise"; phaseTitle = "Проверяем…"
        case .downloading: symbol = "arrow.down"; phaseTitle = "Скачиваем…"
        case .verifying: symbol = "checkmark.shield"; phaseTitle = "Проверяем…"
        case .restarting: symbol = "arrow.clockwise"; phaseTitle = "Перезапуск…"
        }
        updateButton.isEnabled = phase == .idle || phase == .available
        updateButton.selectedAppearance = phase == .available
        setUpdateLabels(title: title ?? phaseTitle, status: status)
        updateGlyph.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
        let spinning = !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion && phase == .checking
        updateGlyph.isHidden = spinning
        if spinning { updateSpinner.startAnimation(nil) } else { updateSpinner.stopAnimation(nil) }
        updateSpinner.isHidden = !spinning
        updateDownloadProgress.stopAnimation(nil)
        updateDownloadProgress.isHidden = true
    }

    private func setUpdateLabels(title: String, status: String) {
        updateTitle.stringValue = title
        updateStatusLabel.stringValue = status
        updateButton.toolTip = status
        updateButton.setAccessibilityLabel(title)
        updateButton.setAccessibilityValue(status)
    }

    private func trackLauncherDownload(_ task: URLSessionDownloadTask?, size: Int64?) {
        guard let task else { return }
        let timer = Timer(timeInterval: 0.2, repeats: true) { [weak self] timer in
            guard let self else { timer.invalidate(); return }
            let expected = task.countOfBytesExpectedToReceive
            self.showLauncherDownloadProgress(received: task.countOfBytesReceived,
                                              total: expected > 0 ? expected : size ?? 0)
        }
        updateProgressTimer = timer
        RunLoop.main.add(timer, forMode: .common)
        timer.fire()
    }

    private func showLauncherDownloadProgress(received: Int64, total: Int64) {
        let bytes = max(0, received)
        let transferred = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
        let knownSize = total > 0
        let changeMode = knownSize == updateDownloadProgress.isIndeterminate
        if changeMode {
            updateDownloadProgress.stopAnimation(nil)
            updateDownloadProgress.isIndeterminate = !knownSize
        }
        if knownSize {
            let percent = min(100, Double(bytes) / Double(total) * 100)
            let expected = ByteCountFormatter.string(fromByteCount: total, countStyle: .file)
            updateDownloadProgress.doubleValue = percent
            updateDownloadProgress.isHidden = false
            setUpdateLabels(title: "Загрузка \(Int(percent))%", status: "\(transferred) из \(expected)")
        } else {
            let animate = !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            let shouldStart = animate && (updateDownloadProgress.isHidden || changeMode)
            updateDownloadProgress.isHidden = !animate
            if shouldStart { updateDownloadProgress.startAnimation(nil) }
            if !animate { updateDownloadProgress.stopAnimation(nil) }
            setUpdateLabels(title: "Скачиваем…", status: "Скачано \(transferred)")
        }
    }

    private func styleCheckbox(_ button: NSButton) {
        button.wantsLayer = true
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
        let copyButton = actionButton("Скопировать", symbol: "doc.on.doc",
                                      action: #selector(copyVisibleLog))
        copyButton.identifier = NSUserInterfaceItemIdentifier("copy-log")
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
        add([title, subtitle, copyButton, clearButton, scrollView], to: card)
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
            copyButton.centerYAnchor.constraint(equalTo: title.centerYAnchor),
            copyButton.trailingAnchor.constraint(equalTo: clearButton.leadingAnchor, constant: -8),
            copyButton.leadingAnchor.constraint(greaterThanOrEqualTo: title.trailingAnchor, constant: 12),
            copyButton.widthAnchor.constraint(equalToConstant: 140),
            copyButton.heightAnchor.constraint(equalTo: clearButton.heightAnchor),
            scrollView.topAnchor.constraint(equalTo: subtitle.bottomAnchor, constant: 22),
            scrollView.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: clearButton.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -24)
        ])
        return card
    }

    private func makeChangelogTab() -> NSView {
        let page = NSView()
        let heading = label("Что изменилось", size: 28, weight: .bold)
        let caption = note("Коротко о главном. История доступна без интернета.")
        let scroll = NSScrollView()
        scroll.identifier = NSUserInterfaceItemIdentifier("changelog-scroll")
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = true
        scroll.hasHorizontalScroller = false
        scroll.autohidesScrollers = true
        scroll.contentView = VerticalLogClipView()
        scroll.contentView.drawsBackground = false
        let document = MCGLAccountsDocumentView()
        document.translatesAutoresizingMaskIntoConstraints = false
        scroll.documentView = document
        let cards = MCGLChangelog.entries.enumerated().map { index, entry in
            makeChangelogCard(entry, index: index)
        }
        let list = verticalStack(cards, spacing: 14)
        add([list], to: document)
        add([heading, caption, scroll], to: page)
        NSLayoutConstraint.activate([
            heading.topAnchor.constraint(equalTo: page.topAnchor, constant: 4),
            heading.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            caption.topAnchor.constraint(equalTo: heading.bottomAnchor, constant: 6),
            caption.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            caption.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: caption.bottomAnchor, constant: 20),
            scroll.leadingAnchor.constraint(equalTo: page.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: page.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: page.bottomAnchor),
            document.widthAnchor.constraint(equalTo: scroll.contentView.widthAnchor),
            list.topAnchor.constraint(equalTo: document.topAnchor, constant: 2),
            list.leadingAnchor.constraint(equalTo: document.leadingAnchor),
            list.trailingAnchor.constraint(equalTo: document.trailingAnchor, constant: -12),
            list.bottomAnchor.constraint(equalTo: document.bottomAnchor, constant: -2)
        ])
        for card in cards { card.widthAnchor.constraint(equalTo: list.widthAnchor).isActive = true }
        return page
    }

    private func makeChangelogCard(_ entry: MCGLChangelogEntry, index: Int) -> NSView {
        let card = makeCardView()
        card.identifier = NSUserInterfaceItemIdentifier("changelog-\(entry.version)")
        let installed = entry.version == MCGLLauncherUpdater.currentVersion
        if installed { card.layer?.borderColor = GalaxyTheme.cyan.withAlphaComponent(0.45).cgColor }
        let version = label(entry.version, size: 22, weight: .bold,
                            color: installed ? GalaxyTheme.cyan : .white)
        let date = label(entry.date, size: 11, color: GalaxyTheme.muted)
        let title = sectionLabel(entry.title)
        let status = label(installed ? "УСТАНОВЛЕНА" : "ПРЕДЫДУЩИЙ РЕЛИЗ", size: 10,
                           weight: .semibold, color: installed ? GalaxyTheme.cyan : GalaxyTheme.muted)
        status.identifier = NSUserInterfaceItemIdentifier("changelog-status-\(entry.version)")
        let body = NSTextField(wrappingLabelWithString: entry.points.map { "•  " + $0 }.joined(separator: "\n"))
        body.font = .systemFont(ofSize: 13)
        body.textColor = .white
        body.isSelectable = true
        body.identifier = NSUserInterfaceItemIdentifier("changelog-body-\(entry.version)")
        let paragraph = NSMutableParagraphStyle()
        paragraph.paragraphSpacing = 8
        paragraph.lineSpacing = 2
        body.attributedStringValue = NSAttributedString(string: body.stringValue, attributes: [
            .font: body.font!, .foregroundColor: NSColor.white, .paragraphStyle: paragraph
        ])
        body.setContentCompressionResistancePriority(.required, for: .vertical)
        let details = actionButton("Подробнее на GitHub", symbol: "arrow.up.right",
                                   action: #selector(openChangelogRelease(_:)))
        details.tag = index
        details.identifier = NSUserInterfaceItemIdentifier("changelog-link-\(entry.version)")
        details.toolTip = entry.releaseURL.absoluteString
        add([version, date, title, status, body, details], to: card)
        NSLayoutConstraint.activate([
            version.topAnchor.constraint(equalTo: card.topAnchor, constant: 20),
            version.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 22),
            date.leadingAnchor.constraint(equalTo: version.trailingAnchor, constant: 12),
            date.firstBaselineAnchor.constraint(equalTo: version.firstBaselineAnchor),
            status.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -22),
            status.centerYAnchor.constraint(equalTo: version.centerYAnchor),
            date.trailingAnchor.constraint(lessThanOrEqualTo: status.leadingAnchor, constant: -12),
            title.topAnchor.constraint(equalTo: version.bottomAnchor, constant: 10),
            title.leadingAnchor.constraint(equalTo: version.leadingAnchor),
            title.trailingAnchor.constraint(equalTo: status.trailingAnchor),
            body.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 12),
            body.leadingAnchor.constraint(equalTo: version.leadingAnchor),
            body.trailingAnchor.constraint(equalTo: status.trailingAnchor),
            details.topAnchor.constraint(equalTo: body.bottomAnchor, constant: 14),
            details.leadingAnchor.constraint(equalTo: version.leadingAnchor),
            details.heightAnchor.constraint(equalToConstant: 32),
            details.widthAnchor.constraint(equalToConstant: 190),
            details.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -18)
        ])
        return card
    }

    @objc private func openChangelogRelease(_ sender: NSButton) {
        guard MCGLChangelog.entries.indices.contains(sender.tag) else { return }
        NSWorkspace.shared.open(MCGLChangelog.entries[sender.tag].releaseURL)
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
        button.wantsLayer = true
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
        let popUp = GalaxyPopUpButton(frame: .zero, pullsDown: false)
        popUp.wantsLayer = true
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

    @objc private func metalVSyncChanged() {
        preferences.metalVSync = metalVSyncButton.state == .on
    }

    @objc private func performanceOptionsChanged() {
        preferences.multicoreMemory = multicoreButton.state == .on
        preferences.graphicsDiagnostics = graphicsDiagnosticsButton.state == .on
    }

    @objc private func clearVisibleLog() {
        logView.string = ""
    }

    @objc private func copyVisibleLog() {
        let text = logView.string
        guard !text.isEmpty else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    @objc private func checkForLauncherUpdate() {
        if let availableLauncherUpdate {
            offerLauncherUpdate(availableLauncherUpdate)
            return
        }
        checkForLauncherUpdates(silent: false)
    }

    private func checkForLauncherUpdates(silent: Bool) {
        showUpdatePhase(.checking, status: "Проверяем обновления на GitHub")
        updater.check { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .success(.current):
                    self.availableLauncherUpdate = nil
                    self.showUpdatePhase(.idle, status: "Установлена актуальная версия")
                    self.appendLog("Обновление лаунчера: установлена актуальная версия \(MCGLLauncherUpdater.currentVersion).")
                    if !silent {
                        let alert = NSAlert()
                        alert.messageText = "Обновлений пока нет"
                        alert.informativeText = "Установлена актуальная версия \(MCGLLauncherUpdater.currentVersion)."
                        alert.addButton(withTitle: "Хорошо")
                        alert.beginSheetModal(for: self.window)
                    }
                case .success(.available(let release)):
                    self.showAvailableLauncherUpdate(release)
                    self.appendLog("Доступно обновление лаунчера: \(release.version).")
                    if !silent { self.offerLauncherUpdate(release) }
                case .failure(let error):
                    self.showUpdatePhase(.idle, status: "Не удалось проверить GitHub")
                    self.appendLog("Проверка обновления лаунчера не удалась: \(error.localizedDescription)")
                    if !silent { self.showUpdateError(error.localizedDescription) }
                }
            }
        }
    }

    private func showAvailableLauncherUpdate(_ release: MCGLGitHubRelease, status: String? = nil) {
        availableLauncherUpdate = release
        showUpdatePhase(.available, status: status ?? "Доступна версия \(release.version)",
                        title: "Скачать \(release.version)")
    }

    private func launcherDownloadDidFinish() {
        DispatchQueue.main.async { [weak self] in
            self?.showUpdatePhase(.verifying, status: "Проверка установочного файла…")
        }
    }

    private func offerLauncherUpdate(_ release: MCGLGitHubRelease) {
        let alert = NSAlert()
        alert.messageText = "Доступна версия \(release.version)"
        let notes = release.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let automatic = release.installableDiskImage != nil
            && MCGLPreparedLauncherUpdate.canReplace(Bundle.main.bundleURL)
        let instructions = automatic
            ? "Лаунчер скачает обновление и перезапустится. Профиль игры и аккаунты будут доступны после перезапуска."
            : "Релиз можно установить вручную из DMG."
        alert.informativeText = notes.isEmpty ? instructions : instructions + "\n\n" + String(notes.prefix(700))
        let action = automatic ? "Обновить и перезапустить" : release.diskImage == nil ? "Открыть GitHub" : "Скачать DMG"
        alert.addButton(withTitle: action)
        alert.addButton(withTitle: "Позже")
        alert.beginSheetModal(for: window) { [weak self] response in
            guard response == .alertFirstButtonReturn, let self else { return }
            if automatic {
                self.installLauncherUpdate(release)
            } else if release.diskImage == nil {
                NSWorkspace.shared.open(release.pageURL)
            } else {
                self.downloadLauncherUpdate(release)
            }
        }
    }

    private func installLauncherUpdate(_ release: MCGLGitHubRelease) {
        let installed = Bundle.main.bundleURL
        guard launchState == .ready, gameApplication == nil, !installingLauncherUpdate else {
            showUpdateError("Заверши игру перед перезапуском лаунчера.")
            return
        }
        if let id = Bundle.main.bundleIdentifier,
           NSRunningApplication.runningApplications(withBundleIdentifier: id).contains(where: {
               $0.processIdentifier != ProcessInfo.processInfo.processIdentifier
                   && $0.bundleURL?.standardizedFileURL == installed.standardizedFileURL
           }) {
            showUpdateError("Закрой другое окно этой копии лаунчера перед обновлением.")
            return
        }
        do { try persistSelectedPassword() }
        catch { showUpdateError(error.localizedDescription); return }
        installingLauncherUpdate = true
        showUpdatePhase(.downloading, status: "Загрузка версии \(release.version)…")
        refreshLaunchButton()
        let task = updater.prepareInstallation(release, installed: installed, didDownload: { [weak self] in
            self?.launcherDownloadDidFinish()
        }) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else {
                    if case .success(let update) = result { update.discard() }
                    return
                }
                switch result {
                case .failure(let error):
                    self.finishLauncherUpdate(with: error)
                case .success(let update):
                    do { try update.install() }
                    catch {
                        update.discard()
                        self.finishLauncherUpdate(with: error)
                        return
                    }
                    self.showUpdatePhase(.restarting, status: "Обновление установлено")
                    let configuration = NSWorkspace.OpenConfiguration()
                    configuration.createsNewApplicationInstance = true
                    NSWorkspace.shared.openApplication(at: installed, configuration: configuration) { app, error in
                        DispatchQueue.main.async {
                            let relaunched = error == nil && app?.isTerminated == false
                            do {
                                try update.finish(relaunched: relaunched)
                                if relaunched {
                                    self.installingLauncherUpdate = false
                                    NSApp.terminate(nil)
                                } else {
                                    let failure = error ?? NSError(domain: "MinecraftGalaxyARM64.Updater", code: 2,
                                        userInfo: [NSLocalizedDescriptionKey: "Новая версия не запустилась. Предыдущая версия восстановлена."])
                                    self.finishLauncherUpdate(with: failure)
                                }
                            } catch {
                                self.finishLauncherUpdate(with: error)
                            }
                        }
                    }
                }
            }
        }
        trackLauncherDownload(task, size: release.installableDiskImage?.size)
    }

    private func finishLauncherUpdate(with error: Error) {
        installingLauncherUpdate = false
        if let availableLauncherUpdate {
            showAvailableLauncherUpdate(availableLauncherUpdate, status: "Ошибка обновления")
        } else {
            showUpdatePhase(.idle, status: "Ошибка обновления")
        }
        refreshLaunchButton()
        appendLog("Не удалось обновить лаунчер: \(error.localizedDescription)")
        showUpdateError(error.localizedDescription)
    }

    private func downloadLauncherUpdate(_ release: MCGLGitHubRelease) {
        showUpdatePhase(.downloading, status: "Загрузка версии \(release.version)…")
        appendLog("Загрузка DMG версии \(release.version) с GitHub…")
        let task = updater.download(release, didDownload: { [weak self] in
            self?.launcherDownloadDidFinish()
        }) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .success(let diskImage):
                    self.showAvailableLauncherUpdate(release, status: "DMG сохранён в «Загрузки»")
                    self.appendLog("Обновление загружено и проверено: \(diskImage.path)")
                    NSWorkspace.shared.open(diskImage)
                    let alert = NSAlert()
                    alert.messageText = "Обновление загружено"
                    alert.informativeText = "Открылся новый DMG. Перетащи Minecraft Galaxy ARM64 в «Программы» с заменой, затем снова запусти приложение."
                    alert.addButton(withTitle: "Понятно")
                    alert.beginSheetModal(for: self.window)
                case .failure(let error):
                    self.showAvailableLauncherUpdate(release, status: "Ошибка загрузки")
                    self.appendLog("Не удалось загрузить обновление: \(error.localizedDescription)")
                    self.showUpdateError(error.localizedDescription)
                }
            }
        }
        trackLauncherDownload(task, size: release.diskImage?.size)
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
        guard launchState == .ready, !installingLauncherUpdate else { return }
        guard gameApplication == nil else {
            appendLog("Клиент уже запущен.")
            return
        }

        guard let account = accounts.selected else {
            setLaunchStatus("Добавь или выбери аккаунт через кнопку справа вверху.")
            showHeaderPanel(.accounts)
            return
        }
        let password = passwordField.stringValue
        guard !password.isEmpty else {
            setLaunchStatus("Укажи пароль для \(account.nickname).")
            showHeaderPanel(.accounts)
            window.makeFirstResponder(passwordField)
            return
        }

        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: javaVMPath) else {
            showError("Не найдена подготовленная ARM64 Java 21.")
            return
        }

        do { try persistSelectedPassword() }
        catch { showError(error.localizedDescription); return }

        prepareOfficialClientAndLaunch(login: account.nickname, password: password)
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
        setLaunchStatus(profileExists ? "Проверка обновлений…" : "Подготовка загрузки…")
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
                self.setLaunchStatus(message)
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
                if (error as NSError).userInfo["MCGLCompatibilityUnavailable"] as? Bool == true,
                   installer.canLaunchSavedClient() {
                    self.appendLog("Обновление не установлено: \(error.localizedDescription)")
                    let alert = NSAlert()
                    alert.alertStyle = .warning
                    alert.messageText = "Обновление MCGL пока недоступно"
                    alert.informativeText = "\(error.localizedDescription)\n\nСохранённый клиент прошёл проверку файлов. Можно попробовать запустить его. Сервер может потребовать новую версию клиента."
                    alert.addButton(withTitle: "Запустить сохранённый клиент")
                    alert.addButton(withTitle: "Отмена")
                    if alert.runModal() == .alertFirstButtonReturn {
                        self.appendLog("По выбору пользователя запускается сохранённый клиент без установки обновления.")
                        self.launchPreparedGame(login: login, password: password)
                    } else {
                        self.resetLaunchControls(status: "Обновление не установлено")
                    }
                    return
                }
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

        let renderer = selectedRenderer
        appendLog("Запуск Minecraft Galaxy ARM64 через \(renderer.title)…")
        setLaunchStatus("Запуск клиента…")
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
        var runtimeEnvironment = ProcessInfo.processInfo.environment
        runtimeEnvironment["MCGL_INITIAL_MEMORY_MB"] = String(initialMemoryMB)
        runtimeEnvironment["MCGL_MEMORY_MB"] = String(maximumMemoryMB)
        runtimeEnvironment.removeValue(forKey: "MCGL_PREALLOCATE_MEMORY")
        runtimeEnvironment["MCGL_MULTICORE_MEMORY"] = multicoreEnabled ? "1" : "0"
        runtimeEnvironment["MCGL_GRAPHICS_DIAGNOSTICS"] = graphicsDiagnosticsEnabled ? "1" : "0"
        runtimeEnvironment["MCGL_FPS_LIMIT"] = String(fpsLimit)
        runtimeEnvironment["MCGL_RENDERER"] = renderer.rawValue
        for variable in ["MCGL_METAL_TERRAIN_LIBRARY", "MCGL_METAL_TERRAIN_VISIBLE", "MCGL_METAL_CAPTURE_FILE"] {
            runtimeEnvironment.removeValue(forKey: variable)
        }
        runtimeEnvironment["MCGL_METAL_LIVE_LIBRARY"] = renderer == .metal ? metalLibraryURL.path : nil
        runtimeEnvironment["MCGL_METAL_FULL_SCENE"] = renderer == .metal ? "1" : nil
        let usesBgfx = renderer == .metal && FileManager.default.isReadableFile(atPath: bgfxLibraryURL.path)
        let metalVSync = preferences.metalVSync
        runtimeEnvironment["MCGL_BGFX_LIVE_LIBRARY"] = usesBgfx ? bgfxLibraryURL.path : nil
        runtimeEnvironment["MCGL_METAL_VSYNC"] = usesBgfx ? (metalVSync ? "1" : "0") : nil
        let usesIndependentMetal = usesBgfx && FileManager.default.isReadableFile(
            atPath: resourcesRoot.appendingPathComponent("Bgfx/lwjgl.jar").path)
        runtimeEnvironment["MCGL_METAL_INDEPENDENT"] = usesIndependentMetal ? "1" : nil
        if usesIndependentMetal {
            appendLog("Экспериментальный режим bgfx Metal без контекста OpenGL.")
        } else if usesBgfx {
            appendLog("Экспериментальный режим bgfx Metal в основном окне игры.")
        }
        if usesBgfx { appendLog(metalVSync ? "VSync в Metal включён." : "VSync в Metal выключен.") }
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
        appendLog("Core 4.1: исходный порядок чанков, пакетная отправка геометрии и текста, шейдеры и VBO / VAO; без общей сортировки граней.")

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
                self.setLaunchStatus("Игра запущена · остановить можно на главной")
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
        setLaunchStatus("Остановка клиента…")
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
        setLaunchStatus(status)
        launchState = .ready
        if passwordField.stringValue.isEmpty { restoreSelectedPassword() }
        initialMemoryPopUp.isEnabled = true
        maximumMemoryPopUp.isEnabled = true
        multicoreButton.isEnabled = true
        graphicsDiagnosticsButton.isEnabled = true
        chunkVboButton.isEnabled = false
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
        setLaunchStatus(message)
        appendLog("ОШИБКА: " + message)
        NSSound.beep()
        if window.isVisible, window.attachedSheet == nil,
           openPanel != nil || tabView.selectedTabViewItem !== pages[0] {
            let alert = NSAlert()
            alert.messageText = "Ошибка лаунчера"
            alert.informativeText = message
            alert.addButton(withTitle: "Закрыть")
            alert.beginSheetModal(for: window)
        }
    }

    private func setLaunchStatus(_ message: String) {
        statusLabel.stringValue = message
        let idle = message == "Готов к запуску"
        statusLabel.isHidden = idle
        collapsedLaunchStatus.isActive = idle
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
