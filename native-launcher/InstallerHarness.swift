import Foundation

@main
struct InstallerHarness {
    static func main() {
        let arguments = CommandLine.arguments
        guard arguments.count == 2 || arguments.count == 3 else {
            FileHandle.standardError.write(Data("usage: InstallerHarness <destination> [app-resources]\n".utf8))
            exit(64)
        }

        let destination = URL(fileURLWithPath: arguments[1], isDirectory: true)
        let workspace = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        let analysis = workspace.appendingPathComponent("work/mcgl-analysis", isDirectory: true)
        let resources = arguments.count == 3
            ? URL(fileURLWithPath: arguments[2], isDirectory: true) : nil
        let installer = MCGLInstaller(
            supportRootURL: destination,
            gameDirectoryURL: destination.appendingPathComponent("mclient-arm64", isDirectory: true),
            portSupportURL: resources?.appendingPathComponent("PortSupport") ?? analysis.appendingPathComponent("bootstrap-build/PortSupport", isDirectory: true),
            patchToolsURL: resources?.appendingPathComponent("PatchTools") ?? analysis.appendingPathComponent("bootstrap-build/PatchTools", isDirectory: true),
            javaExecutableURL: resources?.appendingPathComponent("java8-arm64/Home/bin/java") ?? analysis.appendingPathComponent("zulu8-arm64/Contents/Home/bin/java"),
            jarExecutableURL: resources?.appendingPathComponent("java8-arm64/Home/bin/jar") ?? analysis.appendingPathComponent("zulu8-arm64/Contents/Home/bin/jar"))

        var finished = false
        var exitCode: Int32 = 1
        installer.install(progress: { message in
            print(message)
            fflush(stdout)
        }, completion: { result in
            switch result {
            case .success:
                print("INSTALLER_E2E_OK")
                exitCode = 0
            case .failure(let error):
                FileHandle.standardError.write(
                    Data("INSTALLER_E2E_FAILED: \(error.localizedDescription)\n".utf8))
            }
            finished = true
        })

        while !finished {
            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.1))
        }
        exit(exitCode)
    }
}
