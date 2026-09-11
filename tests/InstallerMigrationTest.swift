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
        precondition(MCGLInstaller.needsAlphaSortMigration(installedMarker: nil))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap 1.6.5\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-3.4.3-1\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-glfw-3.5.1-1\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-glfw-3.5.1-2\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-platform-1\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-render-architecture-1\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-core41-foundation-1\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-indexed-geometry-1\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-chunk-renderer-1\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-core-1\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-core-2\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-core-3\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-core-4\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-core-5\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-1\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-2\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-3\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-4\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-5\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-6\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-7\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-8\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-9\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-10\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-11\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-12\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-13\n"))
        precondition(!MCGLInstaller.needsAlphaSortMigration(
            installedMarker: "Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-14\n"))
        print("INSTALLER_MIGRATION_PASS alphaSort is changed once and line endings survive")
    }
}
