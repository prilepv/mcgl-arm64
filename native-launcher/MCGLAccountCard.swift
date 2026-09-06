import Cocoa
import ImageIO

/// Pure AppKit presentation: no networking, authentication or server HTML.
final class MCGLAccountCard: NSView {
    private let avatar = NSImageView()
    private let title = NSTextField(labelWithString: "")
    private let nickname = NSTextField(labelWithString: "")
    private let profession = NSTextField(labelWithString: "")
    private let professionIcon = NSImageView()
    private let construction = NSTextField(labelWithString: "—")
    private let destruction = NSTextField(labelWithString: "—")
    private let freshness = NSTextField(labelWithString: "")
    private let resources: URL
    private let compact: Bool

    init(resources: URL, compact: Bool = false) {
        self.resources = resources
        self.compact = compact
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = GalaxyTheme.panel.cgColor
        layer?.cornerRadius = 16
        layer?.borderWidth = 1
        layer?.borderColor = GalaxyTheme.line.cgColor
        title.font = .systemFont(ofSize: compact ? 16 : 17, weight: .semibold)
        title.identifier = NSUserInterfaceItemIdentifier("account-title")
        title.textColor = .white
        title.lineBreakMode = .byTruncatingTail
        nickname.font = .systemFont(ofSize: 11)
        nickname.textColor = GalaxyTheme.muted
        nickname.lineBreakMode = .byTruncatingTail
        profession.font = .systemFont(ofSize: 12, weight: .medium)
        profession.textColor = GalaxyTheme.cyan
        profession.lineBreakMode = .byTruncatingTail
        freshness.font = .systemFont(ofSize: 10)
        freshness.identifier = NSUserInterfaceItemIdentifier("account-freshness")
        freshness.textColor = GalaxyTheme.muted
        freshness.lineBreakMode = .byTruncatingTail
        avatar.imageScaling = .scaleProportionallyUpOrDown
        avatar.identifier = NSUserInterfaceItemIdentifier("account-avatar")
        avatar.wantsLayer = true
        avatar.layer?.cornerRadius = 12
        avatar.layer?.masksToBounds = true
        avatar.layer?.backgroundColor = GalaxyTheme.blue.withAlphaComponent(0.14).cgColor
        professionIcon.imageScaling = .scaleProportionallyUpOrDown
        let makeBadge = badge("Строительство", value: construction, symbol: "square.stack.3d.up.fill",
                              color: NSColor(srgbRed: 0.34, green: 0.86, blue: 0.79, alpha: 1))
        let crushBadge = badge("Разрушение", value: destruction, symbol: "burst.fill",
                               color: NSColor(srgbRed: 1, green: 0.56, blue: 0.52, alpha: 1))
        for view in [avatar, title, nickname, professionIcon, profession, makeBadge, crushBadge, freshness] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        NSLayoutConstraint.activate([
            avatar.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 20),
            avatar.topAnchor.constraint(equalTo: topAnchor, constant: 20),
            avatar.widthAnchor.constraint(equalToConstant: compact ? 44 : 48),
            avatar.heightAnchor.constraint(equalTo: avatar.widthAnchor),
            title.leadingAnchor.constraint(equalTo: avatar.trailingAnchor, constant: 12),
            title.topAnchor.constraint(equalTo: avatar.topAnchor, constant: 2),
            title.trailingAnchor.constraint(equalTo: compact ? trailingAnchor : leadingAnchor, constant: compact ? -18 : 280),
            nickname.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            nickname.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 4),
            nickname.trailingAnchor.constraint(equalTo: title.trailingAnchor),
            professionIcon.widthAnchor.constraint(equalToConstant: compact ? 18 : 24),
            professionIcon.heightAnchor.constraint(equalTo: professionIcon.widthAnchor),
            profession.leadingAnchor.constraint(equalTo: professionIcon.trailingAnchor, constant: 7),
            profession.centerYAnchor.constraint(equalTo: professionIcon.centerYAnchor),
            profession.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -18),
            makeBadge.heightAnchor.constraint(equalToConstant: 58),
            crushBadge.heightAnchor.constraint(equalTo: makeBadge.heightAnchor),
            crushBadge.widthAnchor.constraint(equalTo: makeBadge.widthAnchor),
            crushBadge.leadingAnchor.constraint(equalTo: makeBadge.trailingAnchor, constant: 10),
            crushBadge.topAnchor.constraint(equalTo: makeBadge.topAnchor),
            freshness.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 20),
            freshness.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -20),
            freshness.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -15)
        ])
        if compact {
            // One small identity header; aliases, timestamps and account actions
            // stay in the manager rather than expanding the Play summary.
            [nickname, freshness].forEach { $0.isHidden = true }
            NSLayoutConstraint.activate([
                professionIcon.leadingAnchor.constraint(equalTo: title.leadingAnchor),
                professionIcon.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 4),
                makeBadge.topAnchor.constraint(equalTo: avatar.bottomAnchor, constant: 16),
                makeBadge.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 20),
                crushBadge.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -20)
            ])
        } else {
            NSLayoutConstraint.activate([
                professionIcon.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 20),
                professionIcon.topAnchor.constraint(equalTo: avatar.bottomAnchor, constant: 14),
                makeBadge.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 300),
                makeBadge.topAnchor.constraint(equalTo: topAnchor, constant: 20),
                makeBadge.widthAnchor.constraint(equalToConstant: 124),
                heightAnchor.constraint(equalToConstant: 148)
            ])
        }
    }

    required init?(coder: NSCoder) { nil }

    func reserveFooterSpace(before actions: NSView) {
        freshness.trailingAnchor.constraint(equalTo: actions.leadingAnchor, constant: -14).isActive = true
    }

    private func badge(_ text: String, value: NSTextField, symbol: String, color: NSColor) -> NSView {
        let panel = NSView()
        panel.wantsLayer = true
        panel.layer?.backgroundColor = color.withAlphaComponent(0.09).cgColor
        panel.layer?.cornerRadius = 10
        let caption = NSTextField(labelWithString: text)
        caption.font = .systemFont(ofSize: 10, weight: .medium)
        caption.textColor = color
        value.font = .monospacedDigitSystemFont(ofSize: 23, weight: .semibold)
        value.textColor = color
        let icon = NSImageView()
        icon.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
        icon.contentTintColor = color.withAlphaComponent(0.65)
        for child in [caption, value, icon] {
            child.translatesAutoresizingMaskIntoConstraints = false
            panel.addSubview(child)
        }
        NSLayoutConstraint.activate([
            caption.leadingAnchor.constraint(equalTo: panel.leadingAnchor, constant: 10),
            caption.topAnchor.constraint(equalTo: panel.topAnchor, constant: 7),
            value.leadingAnchor.constraint(equalTo: caption.leadingAnchor),
            value.topAnchor.constraint(equalTo: caption.bottomAnchor, constant: 2),
            icon.widthAnchor.constraint(equalToConstant: 16), icon.heightAnchor.constraint(equalToConstant: 16),
            icon.trailingAnchor.constraint(equalTo: panel.trailingAnchor, constant: -10),
            icon.bottomAnchor.constraint(equalTo: panel.bottomAnchor, constant: -12)
        ])
        return panel
    }

    func update(account: MCGLAccount?, selected: Bool, status: String) {
        title.stringValue = (compact ? account?.nickname : account?.title) ?? "Выберите аккаунт"
        title.toolTip = title.stringValue
        nickname.stringValue = account.map { $0.label.isEmpty ? "" : $0.nickname } ?? ""
        let info = account?.info
        profession.stringValue = info?.professionTitle ?? "Профессия —"
        professionIcon.image = info?.professionIcon.flatMap {
            NSImage(contentsOf: resources.appendingPathComponent("Professions/\($0).png"))
        } ?? NSImage(systemSymbolName: "person.crop.square", accessibilityDescription: nil)
        construction.stringValue = info.map { String($0.construction) } ?? "—"
        destruction.stringValue = info.map { String($0.destruction) } ?? "—"
        construction.setAccessibilityLabel("Строительство: \(construction.stringValue)")
        destruction.setAccessibilityLabel("Разрушение: \(destruction.stringValue)")
        freshness.stringValue = status
        freshness.toolTip = status
        toolTip = compact ? status : nil
        layer?.borderColor = (selected && !compact ? GalaxyTheme.blue : GalaxyTheme.line).cgColor
        avatar.image = info?.avatar.flatMap(Self.thumbnail)
            ?? NSImage(systemSymbolName: "person.fill", accessibilityDescription: "Аватар")
    }

    static func thumbnail(_ data: Data) -> NSImage? {
        guard data.count <= 49_152, let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 2048, height <= 2048,
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 96,
                kCGImageSourceShouldCache: false
              ] as CFDictionary) else { return nil }
        return NSImage(cgImage: image, size: NSSize(width: 48, height: 48))
    }
}
