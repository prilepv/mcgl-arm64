package local.mcgl;

/** Runs the final NSApplication/NSWindow activation inside the Java process. */
public final class CocoaWindowBridge {
    static {
        System.loadLibrary("mcglcocoa");
    }

    private CocoaWindowBridge() {}

    public static native int activateAndRaise();
}
