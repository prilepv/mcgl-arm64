import Cocoa

private final class PreviewRequest: MCGLAccountRequest {
    func cancel() {}
}

private final class PreviewAccountService: MCGLAccountFetching {
    var requests: [(String, (Result<MCGLAccountInfo, Error>) -> Void)] = []
    func fetch(nickname: String, completion: @escaping (Result<MCGLAccountInfo, Error>) -> Void) -> MCGLAccountRequest? {
        requests.append((nickname, completion))
        return PreviewRequest()
    }
}

@main
struct LauncherUITest {
    static func descendants(_ view: NSView) -> [NSView] {
        [view] + view.subviews.flatMap(descendants)
    }

    static func main() {
        let arguments = CommandLine.arguments.filter { $0 != "--forms" }
        precondition((2...3).contains(arguments.count),
                     "Usage: ui-test resources-directory [preview.png] [--forms]")
        let resources = URL(fileURLWithPath: arguments[1], isDirectory: true)
        let app = NSApplication.shared
        app.setActivationPolicy(.prohibited)
        let suite = "MCGLLauncherUITest.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = MCGLLauncherPreferences(defaults: defaults)
        preferences.localPasswordNoticeAccepted = true // The confirmation itself is tested with --forms below.
        let vaultDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("MCGLUIVault-\(UUID())")
        let vault = MCGLPasswordStore(directory: vaultDirectory)
        defer { try? FileManager.default.removeItem(at: vaultDirectory) }
        let store = MCGLAccountStore(preferences: preferences)
        let miner = try! store.add(nickname: "DemoMiner", label: "Основной персонаж")
        let builder = try! store.add(nickname: "DemoBuilder", label: "Для стройки")
        try! store.update(miner.id, nickname: miner.nickname, info: MCGLAccountInfo(profession: "Шахтер",
            construction: 14, destruction: 21, avatar: nil, fetchedAt: Date()))
        try! store.update(builder.id, nickname: builder.nickname, info: MCGLAccountInfo(profession: "Строитель",
            construction: 25, destruction: 12, avatar: nil, fetchedAt: Date()))
        try! store.select(miner.id)
        let service = PreviewAccountService()
        let delegate = AppDelegate(preferences: preferences, resourcesRoot: resources, accountService: service, passwordStore: vault)
        delegate.buildWindow()
        let window = app.windows.first { $0.title == "Minecraft Galaxy — ARM64" }!
        let root = window.contentView!
        let tabView = descendants(root).compactMap { $0 as? NSTabView }.first!
        let main = tabView.tabViewItem(at: 0).view!
        let brandMarks = descendants(root).compactMap { $0 as? NSImageView }
            .filter { $0.identifier?.rawValue == "launcher-brand-symbol" }
        precondition(brandMarks.count == 1 && brandMarks[0].superview === root)
        let mark = brandMarks[0]
        let expected = NSImage(contentsOf: resources.appendingPathComponent("app-icon-symbol.png"))!
        precondition(mark.image?.tiffRepresentation == expected.tiffRepresentation,
                     "The header must use the transparent symbol")
        let bitmap = NSBitmapImageRep(data: mark.image!.tiffRepresentation!)!
        precondition(bitmap.hasAlpha
                     && bitmap.colorAt(x: bitmap.pixelsWide / 10,
                                       y: bitmap.pixelsHigh / 10)!.alphaComponent < 0.01,
                     "White tile must not appear inside the launcher")
        func button(_ id: String) -> NSButton {
            let views = descendants(root) + tabView.tabViewItems.compactMap { $0.view }.flatMap(descendants)
            guard let found = views.compactMap({ $0 as? NSButton }).first(where: { $0.identifier?.rawValue == id }) else {
                fatalError("Button missing: \(id)")
            }
            return found
        }
        let control = button("launch")
        let launch = control as! GalaxyButton
        let openGL = button("renderer-opengl")
        let metal = button("renderer-metal")
        let metalAvailable = metal.isEnabled
        let metalVSync = button("metalVSync")
        let bgfxAvailable = metalAvailable
            && FileManager.default.isReadableFile(atPath: resources.appendingPathComponent("Bgfx/bgfx-window.dylib").path)
        let hoverControls = [launch, openGL, button("launcher-update"), button("graphicsDiagnostics"), button("fpsLimit")]
        for control in hoverControls {
            for type: NSEvent.EventType in [.mouseEntered, .mouseExited] {
                let event = NSEvent.enterExitEvent(with: type, location: .zero, modifierFlags: [],
                    timestamp: 0, windowNumber: window.windowNumber, context: nil, eventNumber: 1,
                    trackingNumber: 1, userData: nil)!
                if type == .mouseEntered { control.mouseEntered(with: event) } else { control.mouseExited(with: event) }
                let glow = control.layer?.sublayers?.first { $0.name == "galaxy-hover-glow" }
                precondition(glow?.opacity == (type == .mouseEntered ? 1 : 0))
                precondition(control.layer?.sublayers?.contains { $0.name == "metal-stars" } != true)
            }
        }
        precondition(preferences.renderer == .metal && preferences.metalVSync)
        precondition(metalVSync.state == .on && metalVSync.isEnabled == bgfxAvailable)
        precondition(metalVSync.title.contains("только Metal"))
        precondition(openGL.state == (metalAvailable ? .off : .on) && metal.state == (metalAvailable ? .on : .off))
        precondition((metal.superview as? NSStackView)?.arrangedSubviews.first === metal)
        precondition(openGL.accessibilityLabel() == "OpenGL" && metal.accessibilityLabel() == "Metal")
        for (card, expectedMark) in [(metal, "M"), (openGL, "GL")] {
            let badge = descendants(card).compactMap { $0 as? NSTextField }
                .first { $0.identifier?.rawValue == "renderer-mark" }
            precondition(badge?.stringValue == expectedMark)
        }
        precondition(descendants(metal).contains { $0.identifier?.rawValue == "renderer-new" })
        if metalAvailable {
            metal.performClick(nil)
            precondition(MCGLLauncherPreferences(defaults: defaults).renderer == .metal)
            precondition(metal.state == .on && openGL.state == .off)
            openGL.performClick(nil)
            precondition(MCGLLauncherPreferences(defaults: defaults).renderer == .openGL
                         && openGL.state == .on && metal.state == .off)
        }
        if bgfxAvailable {
            metalVSync.performClick(nil)
            precondition(!MCGLLauncherPreferences(defaults: defaults).metalVSync && metalVSync.state == .off)
            metal.performClick(nil)
            precondition(metalVSync.state == .off)
            openGL.performClick(nil)
            metalVSync.performClick(nil)
            precondition(MCGLLauncherPreferences(defaults: defaults).metalVSync && metalVSync.state == .on)
        }
        precondition(tabView.numberOfTabViewItems == 3)
        let accountsPage = descendants(root).first { $0.identifier?.rawValue == "header-accounts-panel" }!
        let settings = descendants(root).first { $0.identifier?.rawValue == "header-settings-panel" }!
        precondition(accountsPage.isHidden && settings.isHidden)
        let password = descendants(accountsPage).compactMap { $0 as? NSSecureTextField }.first!
        func card(_ id: UUID) -> MCGLAccountCard {
            descendants(accountsPage).compactMap { $0 as? MCGLAccountCard }
                .first { $0.identifier?.rawValue == "account-card-\(id)" }!
        }
        precondition(descendants(card(miner.id)).contains { $0 === password })
        precondition(!descendants(card(builder.id)).contains { $0 is NSSecureTextField })
        let profile = button("header-profile")
        let settingsButton = button("page-2")
        func showPage(_ index: Int) {
            if index == 1 {
                if accountsPage.isHidden { profile.performClick(nil) }
            } else if index == 2 {
                if settings.isHidden { settingsButton.performClick(nil) }
            } else {
                button("page-\(index)").performClick(nil)
            }
        }
        root.layoutSubtreeIfNeeded()
        precondition(profile.accessibilityLabel() == "Аккаунт: \(miner.nickname)")
        precondition(root.convert(profile.bounds, from: profile).minX > root.convert(button("page-4").bounds, from: button("page-4")).maxX)
        precondition(button("page-0").bounds.height >= 34, "The selected tab must fill the navigation bar")
        let longNotice = String(repeating: "Подробности о тестовом событии. ", count: 40)
        let fixture = """
            <div class='blog'><div class='title'><a href='http://forum.mcgl.ru/blog/15'>Итоги тестового события</a></div><div>Результаты &amp; награды</div></div>
            <table><tr><td><img src=":/new/icons/res/3.png"></td><td class="news-text"><div class="date">2026-09-21 </div><div>Объявление о тестовом событии.<br/>Подробности внутри. \(longNotice)</div></td></tr></table>
            <table><tr><td><img src=":/new/icons/res/2.png"></td><td class="news-text"><div class="date">2026-09-13</div><div>Технические работы на тестовом сервере.</div></tr></table>
            <table><tr><td><img src=':/new/icons/res/2.png'></td><td class='news-text'><div class='date'>2026-09-06</div><div>Обновление расписания.</div></td></tr></table>
            <div class='blog'><div class='title'><a href='https://example.com/blog/9'>Чужая ссылка</a></div><div>Тест</div></div>
            """
        let news = MCGLProjectNews.parse(Data(fixture.utf8))
        precondition(news.count == 4 && news[0].link?.absoluteString == "https://forum.minecraft-galaxy.ru/blog/15")
        precondition(news[0].text == "Результаты & награды" && news[1].text.contains("событии. Подробности"))
        delegate.previewProjectNewsForTesting([])
        precondition(descendants(main).compactMap { $0 as? NSTextField }
            .contains { $0.stringValue.contains("Новости временно недоступны") })
        let newsPanel = descendants(main).first { $0.identifier?.rawValue == "project-news" }!
        let update = button("launcher-update")
        let github = button("launcher-github")
        let spinner = descendants(update).first { $0.identifier?.rawValue == "launcher-check-spinner" }!
        let progress = descendants(update).compactMap { $0 as? NSProgressIndicator }
            .first { $0.identifier?.rawValue == "launcher-download-progress" }!
        let animate = !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        for phase in AppDelegate.UpdatePhase.allCases {
            delegate.previewUpdatePhaseForTesting(phase)
            precondition(update.isEnabled == (phase == .idle || phase == .available))
            precondition((update as? GalaxyContentButton)?.selectedAppearance == (phase == .available))
            precondition(spinner.isHidden == (!animate || phase != .checking))
            precondition(progress.isHidden)
        }
        delegate.previewUpdatePhaseForTesting(.downloading)
        delegate.previewUpdateProgressForTesting(received: 20_000_000, total: 80_000_000)
        precondition(!progress.isHidden && !progress.isIndeterminate && progress.doubleValue == 25)
        precondition(update.accessibilityLabel()?.contains("25%") == true)
        delegate.previewUpdateProgressForTesting(received: 80_000_000, total: 80_000_000)
        precondition(progress.doubleValue == 100)
        delegate.previewUpdateProgressForTesting(received: 20_000_000, total: -1)
        precondition(progress.isIndeterminate && progress.isHidden == !animate)
        precondition(update.toolTip?.hasPrefix("Скачано ") == true)
        delegate.previewUpdatePhaseForTesting(.verifying)
        precondition(progress.isHidden && !update.isEnabled)
        delegate.previewUpdatePhaseForTesting(.idle)
        for size in [AppDelegate.windowSize, window.contentMinSize] {
            delegate.previewProjectNewsForTesting([])
            window.setContentSize(size)
            root.layoutSubtreeIfNeeded()
            let width = window.frame.width
            let emptyFrame = newsPanel.frame
            delegate.previewProjectNewsForTesting(news)
            root.layoutSubtreeIfNeeded()
            precondition(width == size.width && window.frame.width == width,
                         "A long project news item expanded the window")
            precondition(root.bounds.contains(root.convert(profile.bounds, from: profile)))
            precondition(!newsPanel.hasAmbiguousLayout && newsPanel.frame.height > 200)
            precondition(newsPanel.frame == emptyFrame && newsPanel.frame.minY == main.bounds.minY)
            precondition(newsPanel.layer?.backgroundColor?.alpha == 1)
            precondition(main.bounds.contains(main.convert(newsPanel.bounds, from: newsPanel)))
            for action in [update, github] {
                let frame = main.convert(action.bounds, from: action)
                precondition(frame.minY == newsPanel.frame.minY && frame.maxX < newsPanel.frame.minX)
            }
            let rows = descendants(newsPanel).filter { $0.identifier?.rawValue.hasPrefix("project-news-item-") == true }
            precondition(rows.count == 4)
            precondition(rows.allSatisfy { abs($0.bounds.height - rows[0].bounds.height) <= 1 },
                         "News rows should fill evenly: \(rows.map { $0.bounds.height })")
            precondition(rows.allSatisfy { newsPanel.bounds.contains(newsPanel.convert($0.bounds, from: $0)) })
            let lastRow = newsPanel.convert(rows[3].bounds, from: rows[3])
            precondition(abs(lastRow.minY - 15) <= 1, "The news well must end just below the last entry")
            let effects = descendants(root).first { $0.identifier?.rawValue == "launcher-effects" }!
            delegate.previewMetalStarsForTesting(true)
            let card = effects.convert(metal.bounds, from: metal)
            let stars = effects.layer!.sublayers!.first { $0.name == "metal-stars" }!
            precondition(effects.hitTest(NSPoint(x: card.midX, y: card.midY)) == nil && stars.superlayer === effects.layer)
            let particles = stars.sublayers!
            precondition(particles.count == 4)
            for particle in particles {
                let flight = particle.animation(forKey: "pixel-drift") as! CAAnimationGroup
                let movement = flight.animations!.first as! CABasicAnimation
                let destination = (movement.toValue as! NSValue).pointValue
                let point = CGPoint(x: stars.frame.minX + destination.x, y: stars.frame.minY + destination.y)
                precondition(stars.bounds.contains(destination) && effects.bounds.contains(point) && !card.contains(point),
                             "Each star needs room to fly past the Metal card")
            }
            delegate.previewMetalStarsForTesting(false)
            precondition(stars.superlayer == nil)
        }
        window.setContentSize(AppDelegate.windowSize)
        precondition(!descendants(main).contains { ($0 as? NSTextField)?.isEditable == true })
        precondition(!descendants(root).contains { $0.identifier?.rawValue == "page-1" })
        control.performClick(nil)
        let status = descendants(root).compactMap { $0 as? NSTextField }
            .first { $0.identifier?.rawValue == "launch-status" }!
        precondition(status.stringValue.contains("Укажи пароль для \(miner.nickname)"))
        precondition(!status.isHidden && status.superview === main)
        precondition(tabView.selectedTabViewItem?.identifier as? String == "play" && !accountsPage.isHidden)
        precondition(control.keyEquivalent.isEmpty)
        profile.performClick(nil)
        precondition(accountsPage.isHidden)
        precondition(descendants(root).compactMap { $0 as? NSButton }
            .filter { $0.title == "Остановить" }.isEmpty)
        for state in MCGLLaunchState.allCases {
            delegate.previewLaunchStateForTesting(state)
            precondition(control.title == state.title)
            precondition(control.isEnabled == state.isActionEnabled)
            precondition(openGL.isEnabled == (state == .ready))
            precondition(metal.isEnabled == (metalAvailable && state == .ready))
            precondition(metalVSync.isEnabled == (bgfxAvailable && state == .ready))
            precondition(control.keyEquivalent == (state.acceptsReturn ? "\r" : ""))
            precondition((window.defaultButtonCell != nil) == state.acceptsReturn)
            precondition(password.isEnabled == (state == .ready))
            precondition(button("account-add").isEnabled == (state == .ready))
            precondition(button("remember-password").isEnabled == (state == .ready))
        }
        delegate.previewLaunchStateForTesting(.ready)
        var checkedLabels = 0
        let checkboxIDs = ["metalVSync", "multicoreMemory", "chunkVbo", "graphicsDiagnostics"]
        precondition(button("page-4").title == "Изменения •")
        precondition(button("page-4").keyEquivalent == "5")
        for index in [0, 1, 2, 3, 4, 0] {
            showPage(index)
            root.layoutSubtreeIfNeeded()
            precondition(control.keyEquivalent == (index == 0 ? "\r" : ""))
            precondition(accountsPage.isHidden == (index != 1) && settings.isHidden == (index != 2))
            if index == 1 || index == 2 {
                let panel = index == 1 ? accountsPage : settings
                let trigger = index == 1 ? profile : settingsButton
                let panelFrame = root.convert(panel.bounds, from: panel)
                let triggerFrame = root.convert(trigger.bounds, from: trigger)
                precondition(panelFrame.maxY < triggerFrame.minY && panelFrame.maxX == triggerFrame.maxX)
                precondition(tabView.selectedTabViewItem?.identifier as? String == "play")
            }
            if index == 2 {
                window.setContentSize(window.contentMinSize)
                root.layoutSubtreeIfNeeded()
                let hint = descendants(settings).compactMap { $0 as? NSTextField }
                    .first { $0.stringValue.hasPrefix("Настройки сохраняются") }!
                let hintBounds = settings.convert(hint.bounds, from: hint)
                precondition(window.frame.height == window.contentMinSize.height
                             && hintBounds.minY >= settings.bounds.minY && hintBounds.maxY <= settings.bounds.maxY,
                             "Settings must fit at minimum window height")
                precondition(root.bounds.contains(root.convert(settings.bounds, from: settings)))
                window.setContentSize(AppDelegate.windowSize)
                root.layoutSubtreeIfNeeded()
            }
            if index == 3 {
                let log = descendants(root).compactMap { $0 as? NSTextView }.first!
                log.textStorage?.append(NSAttributedString(string: String(repeating: "Test log line with wrapped text. ", count: 80)))
                log.scrollToEndOfDocument(nil)
                precondition(log.enclosingScrollView!.contentView.bounds.origin.x == 0,
                             "Log scrolled horizontally and clipped its first characters")
            }
            if index == 4 {
                precondition(button("page-4").title == "Изменения")
                precondition(preferences.lastReadChangelogVersion == MCGLLauncherUpdater.currentVersion)
                let scroll = descendants(root).compactMap { $0 as? NSScrollView }
                    .first { $0.identifier?.rawValue == "changelog-scroll" }!
                let document = scroll.documentView!
                precondition(document.frame.height > scroll.contentView.bounds.height)
                for entry in MCGLChangelog.entries {
                    let card = descendants(document).first { $0.identifier?.rawValue == "changelog-\(entry.version)" }!
                    precondition(!card.hasAmbiguousLayout && card.frame.width > 700 && card.frame.height > 100)
                    let body = descendants(card).compactMap { $0 as? NSTextField }
                        .first { $0.identifier?.rawValue == "changelog-body-\(entry.version)" }!
                    precondition(body.stringValue == entry.points.map { "•  " + $0 }.joined(separator: "\n"))
                    let required = body.cell!.cellSize(forBounds: NSRect(x: 0, y: 0, width: body.bounds.width, height: 10000))
                    precondition(body.bounds.height + 2 >= required.height, "Clipped changelog body: \(entry.version)")
                    precondition(button("changelog-link-\(entry.version)").toolTip == entry.releaseURL.absoluteString)
                }
                let installed = descendants(document).compactMap { $0 as? NSTextField }
                    .filter { $0.stringValue == "УСТАНОВЛЕНА" }
                precondition(installed.count == 1 && installed[0].identifier?.rawValue == "changelog-status-\(MCGLLauncherUpdater.currentVersion)")
                document.scroll(NSPoint(x: 200, y: document.bounds.height))
                precondition(scroll.contentView.bounds.origin.x == 0 && scroll.contentView.bounds.origin.y > 500)
                document.scroll(.zero)
                precondition(scroll.contentView.bounds.origin.y == 0)
            }
            if index != 2 { continue }
            for checkbox in descendants(root).compactMap({ $0 as? NSButton })
                where checkboxIDs.contains(checkbox.identifier?.rawValue ?? "") {
                let color = checkbox.attributedTitle.attribute(.foregroundColor,
                    at: 0, effectiveRange: nil) as? NSColor
                precondition(color == NSColor.white, "Dark checkbox label: \(checkbox.title)")
                checkedLabels += 1
            }
        }
        precondition(checkedLabels == checkboxIDs.count)
        precondition(service.requests.isEmpty, "Fresh cached profiles should not trigger requests")
        showPage(1)
        password.stringValue = "synthetic-password-never-persisted"
        button("account-select-1").performClick(nil)
        precondition(password.stringValue.isEmpty)
        precondition(descendants(card(builder.id)).contains { $0 === password })
        precondition(!descendants(card(miner.id)).contains { $0 is NSSecureTextField })
        precondition(profile.accessibilityLabel() == "Аккаунт: \(builder.nickname)")
        password.stringValue = "synthetic-password-never-persisted"
        button("account-select-1").performClick(nil)
        precondition(password.stringValue == "synthetic-password-never-persisted")
        showPage(0)
        precondition(password.isHiddenOrHasHiddenAncestor)
        showPage(1)
        precondition(password.stringValue == "synthetic-password-never-persisted")
        button("account-select-0").performClick(nil)
        button("account-refresh").performClick(nil)
        precondition(service.requests.count == 2)
        button("account-refresh").performClick(nil)
        precondition(service.requests.count == 2, "Refresh button caused duplicate requests")
        button("account-select-1").performClick(nil)
        service.requests[0].1(.success(MCGLAccountInfo(profession: "Шахтер", construction: 15,
            destruction: 22, avatar: nil, fetchedAt: Date())))
        service.requests[1].1(.failure(URLError(.notConnectedToInternet)))
        let cached = MCGLAccountStore(preferences: preferences)
        precondition(cached.accounts.first?.info?.construction == 15)
        precondition(cached.selected?.id == builder.id && cached.selected?.info?.construction == 25,
                     "Late response changed the wrong profile or network error discarded cache")
        precondition(!String(data: preferences.accountData!, encoding: .utf8)!.contains("synthetic-password"))
        button("account-select-0").performClick(nil)
        precondition(!FileManager.default.fileExists(atPath: vaultDirectory.path), "No opt-in must mean no password files")
        let remember = button("remember-password")
        precondition(remember.state == .off)
        remember.performClick(nil) // Opt in before typing; do not save an empty password.
        precondition(!FileManager.default.fileExists(atPath: vaultDirectory.path))
        password.stringValue = "synthetic-opt-in-miner"
        delegate.controlTextDidEndEditing(Notification(name: NSControl.textDidEndEditingNotification, object: password))
        precondition(try! vault.password(for: miner) == "synthetic-opt-in-miner")
        button("account-select-1").performClick(nil)
        precondition(password.stringValue.isEmpty && remember.state == .off)
        password.stringValue = "synthetic-not-opted-in-builder"
        delegate.controlTextDidEndEditing(Notification(name: NSControl.textDidEndEditingNotification, object: password))
        precondition(try! vault.password(for: builder) == nil)
        button("account-select-0").performClick(nil)
        precondition(password.stringValue == "synthetic-opt-in-miner" && remember.state == .on)
        remember.performClick(nil)
        precondition(try! vault.password(for: miner) == nil)
        button("account-select-1").performClick(nil)
        button("account-select-0").performClick(nil)
        precondition(password.stringValue.isEmpty && remember.state == .off)
        precondition(!String(describing: defaults.dictionaryRepresentation()).contains("synthetic-"))
        window.setContentSize(window.contentMinSize)
        root.layoutSubtreeIfNeeded()
        precondition(root.convert(profile.bounds, from: profile).minX > root.convert(button("page-4").bounds, from: button("page-4")).maxX)
        button("page-2").performClick(nil)
        showPage(0)
        precondition(control.keyEquivalent == "\r")
        window.setContentSize(AppDelegate.windowSize)
        print("PASSWORD_UI_PASS opt-in default off; per-account loading; manual password never carried across profiles; opt-out removes; no secrets in preferences")
        if arguments.count == 3 {
            if metalAvailable { metal.performClick(nil) }
            // Real AppKit views with isolated DEMO profiles; not a game/FPS screenshot.
            // No user credentials, foreground screenshot, game or network access.
            root.layoutSubtreeIfNeeded()
            let preview = root.bitmapImageRepForCachingDisplay(in: root.bounds)!
            root.cacheDisplay(in: root.bounds, to: preview)
            let png = preview.representation(using: .png, properties: [:])!
            try! png.write(to: URL(fileURLWithPath: arguments[2]), options: .atomic)
            let folder = URL(fileURLWithPath: arguments[2]).deletingLastPathComponent()
            for (index, name) in [(1, "accounts"), (2, "settings"), (3, "log"), (4, "changes")] {
                showPage(index)
                root.layoutSubtreeIfNeeded()
                let bitmap = root.bitmapImageRepForCachingDisplay(in: root.bounds)!
                root.cacheDisplay(in: root.bounds, to: bitmap)
                try! bitmap.representation(using: .png, properties: [:])!.write(
                    to: folder.appendingPathComponent("launcher-\(name).png"), options: .atomic)
            }
        }
        print("LAUNCHER_UI_PASS three tabs and two anchored panels; readable offline release cards; light labels; password clearing; launch locks; isolated preferences, no network/game")
        guard CommandLine.arguments.contains("--forms") else { return }
        showPage(1)
        // AppKit defers sheet attachment for a never-ordered window. Keep this
        // owned test window off-screen and non-activating while exercising forms.
        app.setActivationPolicy(.accessory)
        window.setFrameOrigin(NSPoint(x: -10000, y: -10000))
        window.orderBack(nil)
        defer { window.orderOut(nil) }
        func sheet() -> NSWindow {
            let deadline = Date().addingTimeInterval(2)
            while window.attachedSheet == nil && Date() < deadline {
                RunLoop.current.run(until: Date().addingTimeInterval(0.01))
            }
            guard let sheet = window.attachedSheet else { fatalError("Account editor did not open") }
            precondition(sheet.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua)
            return sheet
        }
        func finishSheet(_ response: NSApplication.ModalResponse = .alertFirstButtonReturn) {
            window.endSheet(sheet(), returnCode: response)
            let deadline = Date().addingTimeInterval(0.1)
            RunLoop.current.run(until: deadline)
        }
        preferences.localPasswordNoticeAccepted = false
        password.stringValue = "synthetic-consent-test"
        remember.performClick(nil)
        precondition(try! vault.password(for: miner) == nil)
        finishSheet(.alertSecondButtonReturn)
        precondition(remember.state == .off && !preferences.localPasswordNoticeAccepted)
        remember.performClick(nil)
        finishSheet()
        precondition(preferences.localPasswordNoticeAccepted && remember.state == .on)
        precondition(try! vault.password(for: miner) == "synthetic-consent-test")
        remember.performClick(nil)
        showPage(1)
        button("account-add").performClick(nil)
        let inputs = descendants(sheet().contentView!).compactMap { $0 as? NSTextField }.filter { $0.isEditable }
        precondition(inputs.count == 2)
        inputs.first { $0.placeholderString == "Игровой ник" }!.stringValue = "DemoHunter"
        inputs.first { $0.placeholderString != "Игровой ник" }!.stringValue = "Охотник"
        finishSheet()
        precondition(MCGLAccountStore(preferences: preferences).selected?.nickname == "DemoHunter")
        let hunter = MCGLAccountStore(preferences: preferences).selected!
        password.stringValue = "synthetic-hunter-for-delete-test"
        remember.performClick(nil)
        precondition(try! vault.password(for: hunter) != nil)
        button("account-rename-2").performClick(nil)
        descendants(sheet().contentView!).compactMap { $0 as? NSTextField }.first { $0.isEditable }!.stringValue = "Твинк"
        finishSheet()
        precondition(MCGLAccountStore(preferences: preferences).selected?.label == "Твинк")
        button("account-remove-2").performClick(nil)
        finishSheet(.alertSecondButtonReturn)
        precondition(MCGLAccountStore(preferences: preferences).accounts.count == 3)
        precondition(try! vault.password(for: hunter) != nil)
        button("account-remove-2").performClick(nil)
        finishSheet()
        precondition(MCGLAccountStore(preferences: preferences).accounts.count == 2)
        precondition(try! vault.password(for: hunter) == nil)
        service.requests.last!.1(.success(MCGLAccountInfo(profession: "Охотник", construction: 5,
            destruction: 7, avatar: nil, fetchedAt: Date())))
        precondition(MCGLAccountStore(preferences: preferences).accounts.count == 2,
                     "Deleted account came back after its request completed")
        print("ACCOUNT_FORMS_PASS dark add/rename/delete sheets; consent accepted/cancelled; password removed with account; deleted request routing; isolated demo preferences, no game/network")
    }
}
