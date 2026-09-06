import Cocoa

@main
struct ImageAssetTest {
    static func main() {
        guard CommandLine.arguments.count == 2,
              let data = try? Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])),
              let image = NSBitmapImageRep(data: data) else {
            fatalError("Usage: image-test app-icon.png")
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
        print("IMAGE_ASSET_PASS \(w)x\(h) cornerAlpha<=1/255 transparentFraction=\(fraction)")
    }
}
