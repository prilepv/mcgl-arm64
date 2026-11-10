import Cocoa

/// Build-time artwork only. The launcher and game never execute this renderer.
/// Preserve the original transparent symbol; AppKit supplies the white tile.
@main
struct ComposeAppIcon {
    static func main() throws {
        let arguments = CommandLine.arguments
        guard arguments.count == 3 || arguments.count == 4,
              let source = NSImage(contentsOfFile: arguments[1]),
              source.isValid else {
            throw failure("usage: ComposeAppIcon transparent-symbol.png output.png [pixels]")
        }
        let pixels = arguments.count == 4 ? Int(arguments[3]) : 1024
        guard let pixels, (16...2048).contains(pixels),
              source.size.width == source.size.height,
              let bitmap = NSBitmapImageRep(
                bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels,
                bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                isPlanar: false, colorSpaceName: .deviceRGB,
                bytesPerRow: 0, bitsPerPixel: 0),
              let context = NSGraphicsContext(bitmapImageRep: bitmap) else {
            throw failure("Expected a square symbol and an output size between 16 and 2048")
        }

        let side = CGFloat(pixels)
        let canvas = NSRect(x: 0, y: 0, width: side, height: side)
        bitmap.size = canvas.size
        NSGraphicsContext.saveGraphicsState()
        defer { NSGraphicsContext.restoreGraphicsState() }
        NSGraphicsContext.current = context
        context.imageInterpolation = .high
        context.shouldAntialias = true
        context.cgContext.clear(canvas)

        // The plate and its lighting are rendered at each native icon size.
        let tile = canvas.insetBy(dx: side * 0.08, dy: side * 0.08)
        let outline = NSBezierPath(roundedRect: tile,
                                   xRadius: side * 0.185, yRadius: side * 0.185)
        let porcelain = NSGradient(colorsAndLocations:
            (.white, 0), (.white, 0.26),
            (NSColor(srgbRed: 0.98, green: 0.985, blue: 0.995, alpha: 1), 0.64),
            (NSColor(srgbRed: 0.91, green: 0.92, blue: 0.95, alpha: 1), 1))!
        porcelain.draw(in: outline, angle: -90)
        outline.addClip()

        outline.lineWidth = side * 0.006
        NSColor.white.withAlphaComponent(0.63).setStroke()
        outline.stroke()

        let shadow = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: [
            NSColor(srgbRed: 0.12, green: 0.10, blue: 0.20, alpha: 0.36).cgColor,
            NSColor(srgbRed: 0.12, green: 0.10, blue: 0.20, alpha: 0.17).cgColor,
            NSColor(srgbRed: 0.12, green: 0.10, blue: 0.20, alpha: 0).cgColor
        ] as CFArray, locations: [0, 0.43, 1])!
        let drawing = context.cgContext
        drawing.saveGState()
        drawing.translateBy(x: side * 0.50, y: side * 0.175)
        drawing.scaleBy(x: side * 0.27, y: side * 0.058)
        drawing.drawRadialGradient(shadow, startCenter: .zero, startRadius: 0,
                                   endCenter: .zero, endRadius: 1, options: [])
        drawing.restoreGState()

        let symbol = NSRect(x: side * 0.14, y: side * 0.172,
                            width: side * 0.72, height: side * 0.72)
        source.draw(in: symbol,
                    from: .zero, operation: .sourceOver, fraction: 1)
        context.flushGraphics()

        guard let png = bitmap.representation(using: .png, properties: [:]) else {
            throw failure("Could not encode the composed icon as PNG")
        }
        try png.write(to: URL(fileURLWithPath: arguments[2]), options: .atomic)
        print("COMPOSED_APP_ICON \(pixels)x\(pixels) \(arguments[2])")
    }

    private static func failure(_ message: String) -> NSError {
        NSError(domain: "MCGL.Icon", code: 1,
                userInfo: [NSLocalizedDescriptionKey: message])
    }
}
