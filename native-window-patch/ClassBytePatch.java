import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Keeps the old game class structurally intact while disabling two AWT-only
 * operations that cannot work when LWJGL owns a separate Cocoa window.
 */
public final class ClassBytePatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("Expected Minecraft.class path");

        Path path = Paths.get(args[0]);
        byte[] data = Files.readAllBytes(path);
        byte[] parentNeedle = new byte[] {
            0x2a, (byte)0xb4, 0x01, 0x03,
            (byte)0xb8, 0x01, (byte)0xef
        };

        int match = -1;
        int count = 0;
        for (int i = 0; i <= data.length - parentNeedle.length; i++) {
            boolean same = true;
            for (int j = 0; j < parentNeedle.length; j++) {
                if (data[i + j] != parentNeedle[j]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                match = i;
                count++;
            }
        }

        if (count != 1)
            throw new IllegalStateException("Expected one Display.setParent sequence, found " + count);

        data[match + 4] = 0x57; // pop Canvas
        data[match + 5] = 0x00; // nop
        data[match + 6] = 0x00; // nop
        System.out.println("Patched Display.setParent at class offset " + (match + 4));

        // The following null check originally chooses between embedded-Canvas
        // mode and standalone-window mode.  Make its input null while keeping
        // the same byte length and branch instruction, so the existing
        // DisplayMode(width, height) path is taken with the constructor's
        // 900x700 dimensions.
        byte[] modeBranchNeedle = new byte[] {
            0x2a, (byte)0xb4, 0x01, 0x03,
            (byte)0xc6, 0x00, 0x23
        };
        int modeBranchMatch = -1;
        int modeBranchCount = 0;
        for (int i = 0; i <= data.length - modeBranchNeedle.length; i++) {
            boolean same = true;
            for (int j = 0; j < modeBranchNeedle.length; j++) {
                if (data[i + j] != modeBranchNeedle[j]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                modeBranchMatch = i;
                modeBranchCount++;
            }
        }
        if (modeBranchCount != 1)
            throw new IllegalStateException("Expected one embedded-window branch, found " + modeBranchCount);

        data[modeBranchMatch] = 0x01;     // aconst_null
        data[modeBranchMatch + 1] = 0x00; // nop
        data[modeBranchMatch + 2] = 0x00; // nop
        data[modeBranchMatch + 3] = 0x00; // nop
        System.out.println("Forced standalone DisplayMode branch at class offset " + modeBranchMatch);

        // invokevirtual #316 is net.A.for.G.super(String), the decorative AWT
        // loading-screen update.  The receiver and String are both category-1
        // values, so pop2 + two nops is a same-length, stack-neutral replacement.
        byte[] loadingNeedle = new byte[] {(byte)0xb6, 0x01, 0x3c};
        int loadingCount = 0;
        for (int i = 0; i <= data.length - loadingNeedle.length; i++) {
            if (data[i] == loadingNeedle[0] &&
                    data[i + 1] == loadingNeedle[1] &&
                    data[i + 2] == loadingNeedle[2]) {
                data[i] = 0x58;     // pop2: discard loading Canvas and message
                data[i + 1] = 0x00; // nop
                data[i + 2] = 0x00; // nop
                loadingCount++;
            }
        }
        if (loadingCount != 12)
            throw new IllegalStateException("Expected 12 AWT loading-screen calls, found " + loadingCount);

        // DirectLauncher now creates the Display and raises its NSWindow on
        // the JVM main thread.  Suppress only the startup setDisplayMode call,
        // which would otherwise destroy and recreate that already-raised
        // window.  Other in-game display-mode changes remain untouched.
        byte[] startupModeNeedle = new byte[] {
            (byte)0xbb, 0x01, (byte)0xff, 0x59,
            0x2a, (byte)0xb4, 0x01, 0x05,
            0x2a, (byte)0xb4, 0x01, 0x07,
            (byte)0xb7, 0x02, 0x04,
            (byte)0xb8, 0x02, 0x08
        };
        int startupModeCount = 0;
        for (int i = 0; i <= data.length - startupModeNeedle.length; i++) {
            boolean same = true;
            for (int j = 0; j < startupModeNeedle.length; j++) {
                if (data[i + j] != startupModeNeedle[j]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                int call = i + startupModeNeedle.length - 3;
                data[call] = 0x57;     // pop DisplayMode
                data[call + 1] = 0x00; // nop
                data[call + 2] = 0x00; // nop
                startupModeCount++;
            }
        }
        if (startupModeCount != 1)
            throw new IllegalStateException("Expected one startup DisplayMode call, found " + startupModeCount);

        // Likewise suppress the one startup Display.create(PixelFormat) call;
        // the matching context is already current on this same Java thread.
        byte[] startupCreateNeedle = new byte[] {
            0x2b, (byte)0xb8, 0x02, 0x1f,
            (byte)0xa7, 0x00, 0x15
        };
        int startupCreateCount = 0;
        for (int i = 0; i <= data.length - startupCreateNeedle.length; i++) {
            boolean same = true;
            for (int j = 0; j < startupCreateNeedle.length; j++) {
                if (data[i + j] != startupCreateNeedle[j]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                data[i + 1] = 0x57; // pop PixelFormat
                data[i + 2] = 0x00;
                data[i + 3] = 0x00;
                startupCreateCount++;
            }
        }
        if (startupCreateCount != 1)
            throw new IllegalStateException("Expected one startup Display.create call, found " + startupCreateCount);

        Files.write(path, data);
        System.out.println("Patched " + loadingCount + " AWT loading-screen calls.");
        System.out.println("Patched startup DisplayMode and Display.create calls for direct Cocoa ownership.");
    }
}
