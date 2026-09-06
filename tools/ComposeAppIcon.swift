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

        // An opaque white plate with truly empty margins; no generated mask.
        // Render at the requested size so the contour is antialiased natively.
        let tile = canvas.insetBy(dx: side * 0.08, dy: side * 0.08)
        let outline = NSBezierPath(roundedRect: tile,
                                   xRadius: side * 0.185, yRadius: side * 0.185)
        NSColor.white.setFill()
        outline.fill()
        outline.addClip()
        source.draw(in: canvas.insetBy(dx: side * 0.12, dy: side * 0.12),
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
