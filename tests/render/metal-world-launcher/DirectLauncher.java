package local.mcgl;

import java.nio.file.Paths;
import local.mcgl.render.backend.MetalWorldReplay;

/** Runs the saved terrain stream in the Cocoa/Java Metal runtime, without an account or game session. */
public final class DirectLauncher {
    public static void main(String[] ignored) throws Exception {
        MetalWorldReplay.show(Paths.get(System.getenv("MCGL_METAL_WORLD_INPUT")), Paths.get("direct-metal.dylib"),
                Paths.get(System.getenv("MCGL_METAL_WORLD_IMAGE")), Integer.parseInt(System.getenv("MCGL_METAL_WORLD_SECONDS")));
    }
}
