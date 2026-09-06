import Cocoa

@main
struct LauncherUITest {
    static func descendants(_ view: NSView) -> [NSView] {
        [view] + view.subviews.flatMap(descendants)
    }

    static func main() {
        let app = NSApplication.shared
        app.setActivationPolicy(.prohibited)
        let suite = "MCGLLauncherUITest.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let delegate = AppDelegate(preferences: MCGLLauncherPreferences(defaults: defaults))
        delegate.buildWindow()
        let window = app.windows.first { $0.title == "Minecraft Galaxy — ARM64" }!
        let root = window.contentView!
        func button(_ id: String) -> NSButton {
            descendants(root).compactMap { $0 as? NSButton }
                .first { $0.identifier?.rawValue == id }!
        }
        let control = button("launch")
        precondition(descendants(root).compactMap { $0 as? NSButton }
            .filter { $0.title == "Остановить" }.isEmpty)
        for state in MCGLLaunchState.allCases {
            delegate.previewLaunchStateForTesting(state)
            precondition(control.title == state.title)
            precondition(control.isEnabled == state.isActionEnabled)
            precondition(control.keyEquivalent == (state.acceptsReturn ? "\r" : ""))
            precondition((window.defaultButtonCell != nil) == state.acceptsReturn)
        }
        delegate.previewLaunchStateForTesting(.ready)
        var checkedLabels = 0
        let checkboxIDs = ["rememberLogin", "multicoreMemory", "chunkVbo", "graphicsDiagnostics"]
        for index in [0, 1, 2, 0] {
            button("page-\(index)").performClick(nil)
            root.layoutSubtreeIfNeeded()
            precondition(control.keyEquivalent == (index == 0 ? "\r" : ""))
            if index == 2 {
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
        precondition(checkedLabels == 4)
        print("LAUNCHER_UI_PASS five button states; Return never stops game; three pages; four light checkbox labels; isolated preferences, no game/network")
    }
}
