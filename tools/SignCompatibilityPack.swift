import CryptoKit
import Darwin
import Foundation

/// Publisher-side utility. The private key belongs outside the repository and APP.
@main
struct SignCompatibilityPack {
    static func main() throws {
        let a = CommandLine.arguments
        if a.count == 4 && a[1] == "generate" {
            let key = Curve25519.Signing.PrivateKey()
            let fd = open(a[2], O_WRONLY | O_CREAT | O_EXCL, 0o600)
            guard fd >= 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
            let file = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
            try file.write(contentsOf: key.rawRepresentation); try file.close()
            try key.publicKey.rawRepresentation.write(to: URL(fileURLWithPath: a[3]), options: .withoutOverwriting)
            print("Publisher public key: " + key.publicKey.rawRepresentation.map { String(format: "%02x", $0) }.joined())
        } else if a.count == 5 && a[1] == "sign" {
            let key = try Curve25519.Signing.PrivateKey(rawRepresentation: Data(contentsOf: URL(fileURLWithPath: a[2])))
            let payload = try Data(contentsOf: URL(fileURLWithPath: a[3]))
            guard payload.count <= 8 * 1024 * 1024 - 72 else { fatalError("Pack payload too large") }
            let output = Data("MCGLPK1\n".utf8) + (try key.signature(for: payload)) + payload
            try output.write(to: URL(fileURLWithPath: a[4]), options: .withoutOverwriting)
            print("SIGNED_COMPATIBILITY_PACK bytes=\(output.count)")
        } else {
            fatalError("generate PRIVATE_KEY PUBLIC_KEY | sign PRIVATE_KEY PAYLOAD.zip OUTPUT.pack")
        }
    }
}
