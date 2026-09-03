import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Specializes one known lightmap caller; never exempts arbitrary atlas writes. */
public final class PatchMCGLLightmap implements Opcodes {
    static final String HELPER = "local/mcgl/perf/LightmapCache";
    static final String ENGINE = "net/A/U/Oooo", RENDERER = "net/A/U/oooO";
    static final String SPECIAL = "mcglUploadLightmap";
    static final String UPLOAD = "([IIII)V";

    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException("Unsupported MCGL lightmap: " + reason);
    }
    private static MethodNode method(ClassNode c, String name, String desc, long hash) {
        require(c != null, "missing class");
        for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) {
            require(PatchMCGLChunkVbo.fingerprint(m) == hash, c.name + "." + name + " changed");
            return m;
        }
        throw new IllegalStateException("Missing lightmap method " + name + desc);
    }
    static void patch(Map<String, ClassNode> classes, Set<String> changed) {
        ClassNode renderer = classes.get(RENDERER + ".class"), engine = classes.get(ENGINE + ".class");
        require(!classes.containsKey(HELPER + ".class"), "already patched");
        method(renderer, "<init>", "(Lnet/minecraft/client/Minecraft;)V", 2782049942L);
        MethodNode light = method(renderer, "Ö00000", "()V", 1608031326L);
        method(renderer, "o00000", "(D)V", 1944618560L);
        MethodNode upload = method(engine, "o00000", UPLOAD, 1491027154L);
        method(engine, "Ò00000", "(I)V", 254283601L);
        method(engine, "o00000", "(Ljava/awt/image/BufferedImage;)I", 3414622904L);
        method(classes.get("net/A/for/oOO0.class"), "o00000", "(Ljava/nio/IntBuffer;)V", 1816918290L);
        for (MethodNode m : engine.methods) require(!m.name.equals(SPECIAL), "specialized method exists");

        // The known client changes only PACK/UNPACK_ALIGNMENT to 1 when taking a
        // screenshot. RGBA 16-wide rows remain aligned for every legal alignment.
        // Do not silently cache a different pixel-transfer/packing/PBO protocol.
        int lightingNames = 0;
        for (ClassNode c : classes.values()) for (MethodNode m : c.methods)
            for (AbstractInsnNode n : m.instructions.toArray()) {
                if (n instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode)n;
                    if (f.owner.equals(RENDERER) && f.name.equals("OÔ0000")) {
                        boolean creation = m.name.equals("<init>") && f.getOpcode() == PUTFIELD;
                        boolean reading = (m == light || m.name.equals("o00000") && m.desc.equals("(D)V")) &&
                            f.getOpcode() == GETFIELD;
                        require(c == renderer && f.desc.equals("I") && (creation || reading),
                            "lighting texture identity escapes reviewed methods");
                        lightingNames++;
                    }
                }
                if (!(n instanceof MethodInsnNode)) continue;
                MethodInsnNode i = (MethodInsnNode)n;
                if (!i.owner.startsWith("org/lwjgl/opengl/")) continue;
                if (i.name.startsWith("glPixelStore")) {
                    AbstractInsnNode value = i.getPrevious(), parameter = value.getPrevious();
                    require(i.name.equals("glPixelStorei") && value.getOpcode() == ICONST_1 &&
                        parameter instanceof IntInsnNode &&
                        (((IntInsnNode)parameter).operand == 3317 || ((IntInsnNode)parameter).operand == 3333),
                        "changed pixel-store state in " + c.name);
                }
                require(!i.name.startsWith("glPixelTransfer") && !i.name.startsWith("glPixelMap") &&
                        !i.name.startsWith("glCompressedTex"),
                        "unreviewed texture transfer operation " + i.name);
                if (i.name.equals("glBindBuffer") || i.name.equals("glBindBufferARB")) {
                    // The fingerprinted legacy tessellator binds ARRAY_BUFFER,
                    // never PIXEL_UNPACK_BUFFER. Other binding protocols need review.
                    require(i.owner.equals("org/lwjgl/opengl/ARBVertexBufferObject") &&
                        m.name.equals("new") && m.desc.equals("()I") &&
                        PatchMCGLChunkVbo.fingerprint(m) == 3166733268L,
                        "unreviewed buffer binding in " + c.name);
                }
            }
        require(lightingNames == 3, "lighting texture identity access count");
        // Copy the original uploader, preserving all binding/filtering/conversion
        // effects. Only its final upload is replaced. Generic uploads remain intact.
        // ASM 3 MethodNode.accept(otherNode) can reuse Label.info nodes from the
        // source method. Round-trip a tiny class to give the copy independent
        // labels, frames and instruction nodes before changing either method.
        ClassWriter copyWriter = new ClassWriter(0);
        copyWriter.visit(engine.version, ACC_PUBLIC, engine.name, null, "java/lang/Object", null);
        upload.accept(copyWriter); copyWriter.visitEnd(); upload.instructions.resetLabels();
        ClassNode copy = new ClassNode(); new ClassReader(copyWriter.toByteArray()).accept(copy, 0);
        MethodNode special = copy.methods.get(0);
        special.name = SPECIAL;
        int replacements = 0;
        for (AbstractInsnNode n : special.instructions.toArray()) if (n instanceof MethodInsnNode) {
            MethodInsnNode i = (MethodInsnNode)n;
            if (i.owner.equals("org/lwjgl/opengl/GL11") && i.name.equals("glTexSubImage2D")) {
                InsnList args = new InsnList();
                args.add(new VarInsnNode(ALOAD, 0)); args.add(new VarInsnNode(ILOAD, 4));
                special.instructions.insertBefore(i, args);
                special.instructions.set(i, new MethodInsnNode(INVOKESTATIC, HELPER, "upload",
                    "(IIIIIIIILjava/nio/ByteBuffer;Ljava/lang/Object;I)V"));
                replacements++;
            }
        }
        require(replacements == 1, "single lighting upload");
        int callers = 0;
        for (AbstractInsnNode n : light.instructions.toArray()) if (n instanceof MethodInsnNode) {
            MethodInsnNode i = (MethodInsnNode)n;
            if (i.owner.equals(ENGINE) && i.name.equals("o00000") && i.desc.equals(UPLOAD)) {
                i.name = SPECIAL; callers++;
            }
        }
        require(callers == 1, "single lighting caller");
        engine.methods.add(special);
        changed.add(engine.name); changed.add(renderer.name);
    }
}
