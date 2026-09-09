package org.lwjgl.openal;

import java.io.File;
import java.nio.ByteBuffer;
import local.mcgl.platform.NativeLibraries;
import org.lwjgl.LWJGLException;
import org.lwjgl.system.Configuration;

/** PaulsCode's legacy process-wide context lifecycle, using LWJGL 3 bindings. */
public final class MCGLAL {
    private static long device, context;
    private MCGLAL() {}
    public static synchronized boolean isCreated() { return context != 0; }
    public static synchronized void create() throws LWJGLException {
        if (isCreated()) throw new IllegalStateException("Only one OpenAL context is supported");
        File library;
        try { library = NativeLibraries.resolve(NativeLibraries.Library.OPENAL_SOFT); }
        catch (IllegalStateException failure) { throw new LWJGLException(failure.getMessage(), failure); }
        Configuration.OPENAL_LIBRARY_NAME.set(library.getAbsolutePath());
        try {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0) throw new LWJGLException("Could not open the default audio device");
            ALCCapabilities caps = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, new int[] {
                    ALC10.ALC_FREQUENCY, 44100, ALC10.ALC_REFRESH, 60,
                    ALC10.ALC_SYNC, ALC10.ALC_FALSE, 0 });
            if (context == 0 || !ALC10.alcMakeContextCurrent(context))
                throw new LWJGLException("Could not make the audio context current");
            AL.createCapabilities(caps);
            System.out.println("[MCGL LWJGL3] OpenAL=" + AL10.alGetString(AL10.AL_VERSION));
        } catch (Throwable failure) {
            destroy();
            if (failure instanceof LWJGLException) throw (LWJGLException) failure;
            throw new LWJGLException("LWJGL 3 audio initialization failed", failure);
        }
    }
    public static synchronized void destroy() {
        if (context != 0) {
            ALC10.alcMakeContextCurrent(0);
            AL.setCurrentProcess(null);
            ALC10.alcDestroyContext(context);
            context = 0;
        }
        if (device != 0) { ALC10.alcCloseDevice(device); device = 0; }
    }
}
