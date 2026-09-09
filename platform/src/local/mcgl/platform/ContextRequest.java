package local.mcgl.platform;

/** Context creation policy supplied by the client adapter, not chosen by the window backend. */
public final class ContextRequest {
    public final int major, minor, redBits, greenBits, blueBits, alphaBits, depthBits, stencilBits, samples;
    public final boolean coreProfile, forwardCompatible, scaleFramebuffer;
    public static ContextRequest compatibility21(int alphaBits, int depthBits, int stencilBits, int samples) {
        return new ContextRequest(2, 1, false, false, false, 10, 10, 10,
                alphaBits, depthBits, stencilBits, samples);
    }
    public static ContextRequest core41(int alphaBits, int depthBits, int stencilBits, int samples) {
        return new ContextRequest(4, 1, true, true, false, 10, 10, 10,
                alphaBits, depthBits, stencilBits, samples);
    }
    public ContextRequest(int major, int minor, boolean coreProfile, boolean forwardCompatible,
                          boolean scaleFramebuffer, int redBits, int greenBits, int blueBits,
                          int alphaBits, int depthBits, int stencilBits, int samples) {
        this.major = major; this.minor = minor; this.coreProfile = coreProfile;
        this.forwardCompatible = forwardCompatible; this.scaleFramebuffer = scaleFramebuffer;
        this.redBits = redBits; this.greenBits = greenBits; this.blueBits = blueBits;
        this.alphaBits = alphaBits; this.depthBits = depthBits;
        this.stencilBits = stencilBits; this.samples = samples;
    }
}
