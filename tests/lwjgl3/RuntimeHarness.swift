import Foundation
import Darwin

// Bounded GPU test through the production native runtime; no real credentials.
let arguments = CommandLine.arguments
guard arguments.count == 4 else {
    fatalError("RuntimeHarness RUNTIME_EXECUTABLE ISOLATED_PROFILE NEW_LOG_FILE")
}
let temporary = "/private/tmp/mcgl-binding-probe-" + UUID().uuidString
let logFIFO = temporary + "-log", passwordFIFO = temporary + "-input"
guard mkfifo(logFIFO, 0o600) == 0, mkfifo(passwordFIFO, 0o600) == 0 else { fatalError("mkfifo") }
defer { unlink(logFIFO); unlink(passwordFIFO) }
let logFD = open(logFIFO, O_RDWR | O_NONBLOCK | O_NOFOLLOW)
let passwordFD = open(passwordFIFO, O_RDWR | O_NONBLOCK | O_NOFOLLOW)
guard logFD >= 0, passwordFD >= 0 else { fatalError("open fifo") }
defer { close(logFD); close(passwordFD) }
guard !FileManager.default.fileExists(atPath: arguments[3]),
      FileManager.default.createFile(atPath: arguments[3], contents: nil) else { fatalError("new log required") }
let output = try FileHandle(forWritingTo: URL(fileURLWithPath: arguments[3]))
defer { try? output.close() }
let process = Process()
process.executableURL = URL(fileURLWithPath: arguments[1])
process.arguments = ["BindingProbe", passwordFIFO, logFIFO, arguments[2]]
var environment = ProcessInfo.processInfo.environment
environment["MCGL_INITIAL_MEMORY_MB"] = "512"
environment["MCGL_MEMORY_MB"] = "1024"
environment["MCGL_FPS_LIMIT"] = "120"
process.environment = environment
try process.run()
let synthetic = Array("unused-synthetic-test-input\n".utf8)
_ = synthetic.withUnsafeBytes { write(passwordFD, $0.baseAddress, $0.count) }
var buffer = [UInt8](repeating: 0, count: 8192)
func drain() {
    while true {
        let count = read(logFD, &buffer, buffer.count)
        guard count > 0 else { return }
        let data = Data(buffer.prefix(count))
        output.write(data)
        FileHandle.standardOutput.write(data)
    }
}
let deadline = Date().addingTimeInterval(40)
while process.isRunning && Date() < deadline { drain(); Thread.sleep(forTimeInterval: 0.02) }
let timedOut = process.isRunning
if timedOut {
    process.terminate()
    let cleanupDeadline = Date().addingTimeInterval(2)
    while process.isRunning && Date() < cleanupDeadline { drain(); Thread.sleep(forTimeInterval: 0.02) }
    if process.isRunning { kill(process.processIdentifier, SIGKILL) }
}
process.waitUntilExit()
drain()
print("BINDING_RUNTIME_EXIT status=\(process.terminationStatus) timeout=\(timedOut)")
exit(timedOut ? 124 : process.terminationStatus)
