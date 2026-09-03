import Foundation

@main
struct InstallerMigrationTest {
    static func main() {
        let unix = MCGLInstaller.enablingAlphaSort(
            in: "music:1.0\nalphaSort:false\nviewDistance:0\n")
        precondition(unix.changed)
        precondition(unix.text == "music:1.0\nalphaSort:true\nviewDistance:0\n")

        let windows = MCGLInstaller.enablingAlphaSort(
            in: "music:1.0\r\nalphaSort:false\r\n")
        precondition(windows.changed)
        precondition(windows.text == "music:1.0\r\nalphaSort:true\r\n")

        let enabled = MCGLInstaller.enablingAlphaSort(in: "alphaSort:true\n")
        precondition(!enabled.changed)
        precondition(enabled.text == "alphaSort:true\n")
        print("INSTALLER_MIGRATION_PASS alphaSort is changed once and line endings survive")
    }
}
