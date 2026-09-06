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
        let mark = descendants(root).compactMap { $0 as? NSImageView }
            .first { $0.identifier?.rawValue == "launcher-brand-symbol" }!
        let expected = NSImage(contentsOf: resources.appendingPathComponent("app-icon-symbol.png"))!
        precondition(mark.image?.tiffRepresentation == expected.tiffRepresentation,
                     "Sidebar must load the separate transparent symbol, not app.icns")
        let bitmap = NSBitmapImageRep(data: mark.image!.tiffRepresentation!)!
        precondition(bitmap.hasAlpha
                     && bitmap.colorAt(x: bitmap.pixelsWide / 10,
                                       y: bitmap.pixelsHigh / 2)!.alphaComponent < 0.01,
                     "White tile must not appear inside the launcher")
        func button(_ id: String) -> NSButton {
            let tabs = descendants(root).compactMap { $0 as? NSTabView }.first!
            let views = descendants(root) + tabs.tabViewItems.compactMap { $0.view }.flatMap(descendants)
            guard let found = views.compactMap({ $0 as? NSButton }).first(where: { $0.identifier?.rawValue == id }) else {
                fatalError("Button missing: \(id)")
            }
            return found
        }
        let control = button("launch")
        let password = descendants(root).compactMap { $0 as? NSSecureTextField }.first!
        let login = descendants(root).compactMap { $0 as? NSTextField }
            .first { $0.identifier?.rawValue == "login" }!
        root.layoutSubtreeIfNeeded()
        precondition(!descendants(root).contains { ["account-picker", "manage-accounts"].contains($0.identifier?.rawValue ?? "") },
                     "Account manager entry points must not be duplicated on Play")
        let summary = descendants(root).first { $0.identifier?.rawValue == "account-summary" }!
        precondition(summary.frame.height == 158)
        let summaryTitle = descendants(summary).compactMap { $0 as? NSTextField }
            .first { $0.identifier?.rawValue == "account-title" }!
        let summaryAvatar = descendants(summary).compactMap { $0 as? NSImageView }
            .first { $0.identifier?.rawValue == "account-avatar" }!
        precondition(!summaryTitle.isHiddenOrHasHiddenAncestor && summaryTitle.stringValue == miner.nickname,
                     "Play summary should show the actual nickname, not its local alias")
        precondition(!summaryAvatar.isHiddenOrHasHiddenAncestor && summaryAvatar.image != nil)
        // NSImageView adds native frame insets around its Auto Layout alignment rect.
        let avatarAlignment = summaryAvatar.alignmentRect(forFrame: summaryAvatar.frame)
        precondition(avatarAlignment.size == NSSize(width: 44, height: 44), "Avatar alignment: \(avatarAlignment)")
        precondition(!descendants(summary).contains { $0 is NSButton }, "Account actions belong only in the manager")
        precondition(descendants(root).compactMap { $0 as? NSButton }
            .filter { $0.title == "Остановить" }.isEmpty)
        for state in MCGLLaunchState.allCases {
            delegate.previewLaunchStateForTesting(state)
            precondition(control.title == state.title)
            precondition(control.isEnabled == state.isActionEnabled)
            precondition(control.keyEquivalent == (state.acceptsReturn ? "\r" : ""))
            precondition((window.defaultButtonCell != nil) == state.acceptsReturn)
            precondition(login.isEnabled == (state == .ready))
            precondition(password.isEnabled == (state == .ready))
            precondition(button("account-add").isEnabled == (state == .ready))
            precondition(button("remember-password").isEnabled == (state == .ready))
        }
        delegate.previewLaunchStateForTesting(.ready)
        var checkedLabels = 0
        let checkboxIDs = ["multicoreMemory", "chunkVbo", "graphicsDiagnostics"]
        for index in [0, 1, 2, 3, 0] {
            button("page-\(index)").performClick(nil)
            root.layoutSubtreeIfNeeded()
            precondition(control.keyEquivalent == (index == 0 ? "\r" : ""))
            if index == 3 {
                let log = descendants(root).compactMap { $0 as? NSTextView }.first!
                log.textStorage?.append(NSAttributedString(string: String(repeating: "Test log line with wrapped text. ", count: 80)))
                log.scrollToEndOfDocument(nil)
                precondition(log.enclosingScrollView!.contentView.bounds.origin.x == 0,
                             "Log scrolled horizontally and clipped its first characters")
            }
            if index == 0 && checkedLabels > 0 { continue }
            for checkbox in descendants(root).compactMap({ $0 as? NSButton })
                where checkboxIDs.contains(checkbox.identifier?.rawValue ?? "") {
                let color = checkbox.attributedTitle.attribute(.foregroundColor,
                    at: 0, effectiveRange: nil) as? NSColor
                precondition(color == NSColor.white, "Dark checkbox label: \(checkbox.title)")
                checkedLabels += 1
            }
        }
        precondition(checkedLabels == 3)
        precondition(service.requests.isEmpty, "Fresh cached profiles should not trigger requests")
        button("page-1").performClick(nil)
        password.stringValue = "synthetic-password-never-persisted"
        button("account-select-1").performClick(nil)
        precondition(login.stringValue == builder.nickname && password.stringValue.isEmpty)
        precondition(summaryTitle.stringValue == builder.nickname)
        password.stringValue = "synthetic-password-never-persisted"
        login.stringValue = "ManualPlayer"
        delegate.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification, object: login))
        precondition(password.stringValue.isEmpty && MCGLAccountStore(preferences: preferences).selected == nil)
        precondition(summary.isHidden)
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
        button("page-0").performClick(nil)
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
        button("account-select-0").performClick(nil)
        precondition(password.stringValue.isEmpty && remember.state == .off)
        precondition(!String(describing: defaults.dictionaryRepresentation()).contains("synthetic-"))
        print("PASSWORD_UI_PASS opt-in default off; per-account loading; manual password never carried across profiles; opt-out removes; no secrets in preferences")
        if arguments.count == 3 {
            // Real AppKit views with isolated DEMO profiles; not a game/FPS screenshot.
            // No user credentials, foreground screenshot, game or network access.
            root.layoutSubtreeIfNeeded()
            let preview = root.bitmapImageRepForCachingDisplay(in: root.bounds)!
            root.cacheDisplay(in: root.bounds, to: preview)
            let png = preview.representation(using: .png, properties: [:])!
            try! png.write(to: URL(fileURLWithPath: arguments[2]), options: .atomic)
            let folder = URL(fileURLWithPath: arguments[2]).deletingLastPathComponent()
            for (index, name) in [(1, "accounts"), (2, "settings"), (3, "log")] {
                button("page-\(index)").performClick(nil)
                root.layoutSubtreeIfNeeded()
                let bitmap = root.bitmapImageRepForCachingDisplay(in: root.bounds)!
                root.cacheDisplay(in: root.bounds, to: bitmap)
                try! bitmap.representation(using: .png, properties: [:])!.write(
                    to: folder.appendingPathComponent("launcher-\(name).png"), options: .atomic)
            }
        }
        print("LAUNCHER_UI_PASS four pages; light labels; password clearing; launch locks; late response routing; offline cache; isolated preferences, no network/game")
        guard CommandLine.arguments.contains("--forms") else { return }
        button("page-1").performClick(nil)
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
        button("page-0").performClick(nil)
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
        button("page-1").performClick(nil)
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
