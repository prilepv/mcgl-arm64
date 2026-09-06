import Cocoa

private final class NoAccountNetwork: MCGLAccountFetching {
    func fetch(nickname: String, completion: @escaping (Result<MCGLAccountInfo, Error>) -> Void) -> MCGLAccountRequest? {
        preconditionFailure("Layout test unexpectedly requested network access")
    }
}

@main
struct AccountsLayoutTest {
    static func descendants(_ view: NSView) -> [NSView] { [view] + view.subviews.flatMap(descendants) }

    static func main() throws {
        let resources = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let app = NSApplication.shared
        app.setActivationPolicy(.prohibited)
        let suite = "MCGLAccountsLayout.\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = MCGLLauncherPreferences(defaults: defaults)
        let store = MCGLAccountStore(preferences: preferences)
        let professions = ["Чернорабочий", "Шахтёр", "Охотник", "Медик", "Строитель", "Кузнец", "Инженер"]
        for i in 0..<30 {
            let account = try store.add(nickname: "Demo\(i)", label: i == 0 ? String(repeating: "Длинное имя ", count: 10) : "Аккаунт \(i + 1)")
            try store.update(account.id, nickname: account.nickname, info: MCGLAccountInfo(
                profession: professions[i % 7], construction: i, destruction: i * 2, avatar: nil, fetchedAt: Date()))
        }
        let vaultDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("MCGLLayoutVault-\(UUID())")
        let delegate = AppDelegate(preferences: preferences, resourcesRoot: resources, accountService: NoAccountNetwork(),
                                   passwordStore: MCGLPasswordStore(directory: vaultDirectory))
        delegate.buildWindow()
        let window = app.windows.first { $0.title == "Minecraft Galaxy — ARM64" }!
        let root = window.contentView!
        let accountButton = descendants(root).compactMap { $0 as? NSButton }.first { $0.identifier?.rawValue == "page-1" }!
        accountButton.performClick(nil)
        root.layoutSubtreeIfNeeded()
        let scroll = descendants(root).compactMap { $0 as? NSScrollView }.first { $0.identifier?.rawValue == "account-scroll" }!
        let document = scroll.documentView!
        let cards = descendants(document).compactMap { $0 as? MCGLAccountCard }
        precondition(cards.count == 30)
        precondition(document.frame.height > 4500 && scroll.contentView.bounds.height > 300,
                     "Account scroll document did not expand: \(document.frame), \(scroll.contentView.bounds)")
        precondition(!scroll.contentView.drawsBackground)
        for card in cards {
            precondition(card.frame.height == 148 && card.frame.width > 700)
            precondition(!card.hasAmbiguousLayout)
            let footer = descendants(card).first { $0.identifier?.rawValue == "account-freshness" }!
            let actions = descendants(card).compactMap { $0 as? NSButton }
            for action in actions {
                precondition(!card.convert(footer.bounds, from: footer).intersects(card.convert(action.bounds, from: action)),
                             "Account timestamp overlaps its action buttons")
                let button = action as! GalaxyButton
                let textSize = NSAttributedString(string: button.title, attributes: [
                    .font: NSFont.systemFont(ofSize: 13, weight: .semibold)
                ]).size()
                let layout = button.contentLayout(textSize: textSize)
                precondition(layout.icon.midY == button.bounds.midY)
                if button.title.isEmpty {
                    precondition(layout.icon.midX == button.bounds.midX,
                                 "Edit/delete symbol is not centred inside its button")
                } else {
                    precondition(layout.text.x - layout.icon.maxX == 8,
                                 "Labelled buttons must retain their icon-to-text spacing")
                }
            }
        }
        document.scroll(NSPoint(x: 200, y: document.bounds.height))
        precondition(scroll.contentView.bounds.origin.x == 0)
        precondition(scroll.contentView.bounds.origin.y > 4000)
        document.scroll(.zero)
        precondition(scroll.contentView.bounds.origin.y == 0)
        for name in ["laborer", "miner", "hunter", "medic", "builder", "smith", "engineer"] {
            let icon = NSImage(contentsOf: resources.appendingPathComponent("Professions/\(name).png"))!
            let bitmap = NSBitmapImageRep(data: icon.tiffRepresentation!)!
            precondition(bitmap.pixelsWide == 32 && bitmap.pixelsHigh == 32)
        }
        if CommandLine.arguments.count == 3 {
            // Optional local response previously fetched with explicit user consent.
            // Nothing is saved, rendered into screenshots or sent over the network.
            let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))
            let info = try MCGLAccountInfoParser.parse(data)
            precondition(info.avatar.flatMap(MCGLAccountCard.thumbnail) != nil)
            print("ACCOUNT_AVATAR_PASS real inline response decodes to bounded static thumbnail")
        }
        print("ACCOUNTS_LAYOUT_PASS 30 scrollable cards, seven original icons, no ambiguous card frames, transparent viewport, no network/game")
        precondition(!FileManager.default.fileExists(atPath: vaultDirectory.path))
    }
}
