import Cocoa

@main
struct ImageAssetTest {
    static func main() {
        guard (2...3).contains(CommandLine.arguments.count),
              let data = try? Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])),
              let image = NSBitmapImageRep(data: data) else {
            fatalError("Usage: image-test app-icon.png [--white-tile]")
        }
        precondition(image.hasAlpha, "Icon must have an alpha channel")
        let w = image.pixelsWide, h = image.pixelsHigh
        let points = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
        for (x, y) in points {
            // Allow one 8-bit alpha quantum from raster mask quantization.
            precondition(image.colorAt(x: x, y: y)!.alphaComponent <= 1.0 / 255.0 + 0.00001,
                         "Icon corner is not transparent")
        }
        var transparent = 0
        var sampled = 0
        for y in stride(from: 0, to: h, by: 4) {
            for x in stride(from: 0, to: w, by: 4) {
                sampled += 1
                if image.colorAt(x: x, y: y)!.alphaComponent < 0.01 { transparent += 1 }
            }
        }
        let fraction = Double(transparent) / Double(sampled)
        precondition(fraction > 0.18, "Expected cutout silhouette, not an opaque tile")
        precondition(image.colorAt(x: w / 2, y: h / 2)!.alphaComponent > 0.9,
                     "The center of the cube must be opaque")
        if CommandLine.arguments.count == 3 {
            precondition(CommandLine.arguments[2] == "--white-tile")
            // Margins must contain no opaque square, checkerboard or flecks.
            for y in 0..<h {
                for x in 0..<w where x < w / 16 || x >= w - w / 16
                    || y < h / 16 || y >= h - h / 16 {
                    precondition(image.colorAt(x: x, y: y)!.alphaComponent == 0,
                                 "White tile must have completely transparent margins")
                }
            }
            let insetX = max(Int(ceil(Double(w) * 0.08)), w / 10)
            let insetY = max(Int(ceil(Double(h) * 0.08)), h / 10)
            for (x, y) in [(w / 2, insetY), (insetX, h / 2), (w - insetX - 1, h / 2)] {
                let color = image.colorAt(x: x, y: y)!.usingColorSpace(.deviceRGB)!
                precondition(color.alphaComponent > 0.99 && color.redComponent > 0.97
                             && color.greenComponent > 0.97 && color.blueComponent > 0.97,
                             "Expected an opaque white plate behind the symbol")
            }
            // Empty quarter-points in the corners, not merely the four edge pixels.
            for (x, y) in [(w / 10, h / 10), (w * 9 / 10, h / 10),
                           (w / 10, h * 9 / 10), (w * 9 / 10, h * 9 / 10)] {
                precondition(image.colorAt(x: x, y: y)!.alphaComponent == 0,
                             "Rounded corner must remain transparent")
            }
        }
        print("IMAGE_ASSET_PASS \(w)x\(h) cornerAlpha<=1/255 transparentFraction=\(fraction)")
    }
}
