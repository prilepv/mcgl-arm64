package local.mcgl.render;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Immutable driver facts. No native handles, window objects or binding classes escape. */
public final class RenderCapabilities {
    public final String vendor, renderer, version;
    public final RenderProfile profile;
    /** Zero means not queried (the historical compatibility snapshot). */
    public final int majorVersion, minorVersion;
    public final String shadingLanguageVersion;
    private final Set<String> features;

    public RenderCapabilities(String vendor, String renderer, String version, Set<String> features) {
        this(vendor, renderer, version, features, RenderProfile.COMPATIBILITY_21, 0, 0, "");
    }
    public RenderCapabilities(String vendor, String renderer, String version, Set<String> features,
                              RenderProfile profile, int majorVersion, int minorVersion, String shadingLanguageVersion) {
        if (vendor == null || renderer == null || version == null || features == null)
            throw new NullPointerException("render capabilities");
        if (profile == null || shadingLanguageVersion == null) throw new NullPointerException("render profile facts");
        if (majorVersion < 0 || minorVersion < 0) throw new IllegalArgumentException("Negative GL version");
        HashSet<String> copy = new HashSet<String>(features);
        if (copy.contains(null)) throw new NullPointerException("render feature");
        this.vendor = vendor; this.renderer = renderer; this.version = version;
        this.profile = profile; this.majorVersion = majorVersion; this.minorVersion = minorVersion;
        this.shadingLanguageVersion = shadingLanguageVersion;
        this.features = Collections.unmodifiableSet(copy);
    }
    public boolean supports(String feature) { return features.contains(feature); }
    public Set<String> features() { return features; }
}
