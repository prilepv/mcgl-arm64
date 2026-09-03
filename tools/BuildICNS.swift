import Foundation

private struct IconEntry {
    let type: String
    let filename: String
    let pixels: Int
}

private struct IconError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private func appendBigEndian(_ value: Int, to data: inout Data) throws {
    guard value >= 0, value <= Int(UInt32.max) else {
        throw IconError(message: "ICNS chunk is too large")
    }
    var encoded = UInt32(value).bigEndian
    withUnsafeBytes(of: &encoded) { data.append(contentsOf: $0) }
}

private func pngSize(_ data: Data) throws -> (Int, Int) {
    let signature: [UInt8] = [137, 80, 78, 71, 13, 10, 26, 10]
    guard data.count >= 24, Array(data.prefix(8)) == signature,
          String(data: data[12..<16], encoding: .ascii) == "IHDR" else {
        throw IconError(message: "Icon representation is not a valid PNG")
    }
    func integer(at offset: Int) -> Int {
        data[offset..<(offset + 4)].reduce(0) { ($0 << 8) | Int($1) }
    }
    return (integer(at: 16), integer(at: 20))
}

@main
struct BuildICNS {
    static func main() throws {
        guard CommandLine.arguments.count == 3 else {
            throw IconError(message: "usage: BuildICNS iconset-directory output.icns")
        }
        let directory = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let outputURL = URL(fileURLWithPath: CommandLine.arguments[2])
        let entries = [
            IconEntry(type: "icp4", filename: "icon_16x16.png", pixels: 16),
            IconEntry(type: "icp5", filename: "icon_32x32.png", pixels: 32),
            IconEntry(type: "icp6", filename: "icon_32x32@2x.png", pixels: 64),
            IconEntry(type: "ic07", filename: "icon_128x128.png", pixels: 128),
            IconEntry(type: "ic08", filename: "icon_256x256.png", pixels: 256),
            IconEntry(type: "ic09", filename: "icon_512x512.png", pixels: 512),
            IconEntry(type: "ic10", filename: "icon_512x512@2x.png", pixels: 1024),
            IconEntry(type: "ic11", filename: "icon_16x16@2x.png", pixels: 32),
            IconEntry(type: "ic12", filename: "icon_32x32@2x.png", pixels: 64),
            IconEntry(type: "ic13", filename: "icon_128x128@2x.png", pixels: 256),
            IconEntry(type: "ic14", filename: "icon_256x256@2x.png", pixels: 512)
        ]

        var chunks = Data()
        for entry in entries {
            let png = try Data(contentsOf: directory.appendingPathComponent(entry.filename))
            let size = try pngSize(png)
            guard size.0 == entry.pixels, size.1 == entry.pixels else {
                throw IconError(message: "\(entry.filename) has unexpected dimensions")
            }
            chunks.append(contentsOf: entry.type.utf8)
            try appendBigEndian(png.count + 8, to: &chunks)
            chunks.append(png)
        }

        var result = Data("icns".utf8)
        try appendBigEndian(chunks.count + 8, to: &result)
        result.append(chunks)
        try result.write(to: outputURL, options: .atomic)
        print("ICNS_CREATED \(outputURL.path)")
    }
}
