package local.mcgl.render;

/** Explicit renderer contract; a Core request must never silently become compatibility GL. */
public enum RenderProfile {
    COMPATIBILITY_21, CORE_41
}
