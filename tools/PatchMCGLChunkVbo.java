import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.zip.CRC32;

/** Opt-in terrain VBOs. Keep native lists as the fallback for unknown GL commands. */
public final class PatchMCGLChunkVbo implements Opcodes {
    static final String HELPER = "local/mcgl/perf/ChunkVbo";
    private static final String GL = "org/lwjgl/opengl/GL11";
    private static void require(boolean ok, String why) {
        if (!ok) throw new IllegalStateException("Unsupported MCGL VBO bytecode: " + why);
    }
    static long fingerprint(MethodNode method) {
        ClassWriter w = new ClassWriter(0);
        w.visit(50, 1, "Fingerprint", null, "java/lang/Object", null);
        method.accept(w); w.visitEnd();
        CRC32 crc = new CRC32(); crc.update(w.toByteArray());
        method.instructions.resetLabels();
        return crc.getValue();
    }
    private static void validate(ClassNode c, String name, String desc, long expected) {
        for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) {
            require(fingerprint(m) == expected, "changed vertex format/list semantics: " + c.name + "." + name);
            return;
        }
        require(false, "missing method " + name);
    }
    public static void patch(Map<String, ClassNode> classes, Set<String> changed) {
        require(!classes.containsKey(HELPER + ".class"), "already patched");
        ClassNode chunk = classes.get("net/A/U/H.class");
        require(chunk != null, "chunk renderer");
        String tess = null;
        for (FieldNode f : chunk.fields) if ((f.access & ACC_STATIC) != 0 && f.desc.startsWith("Lnet/A/for/")) {
            require(tess == null, "ambiguous tessellator");
            tess = f.desc.substring(1, f.desc.length() - 1);
        }
        require(tess != null && classes.containsKey(tess + ".class"), "tessellator");
        ClassNode t = classes.get(tess + ".class");
        // These three methods define byte layout, transforms and depth semantics.
        // Asset/other client updates are allowed; new renderer bytecode requires
        // review rather than interpreting a different layout as 32-byte vertices.
        validate(t, "new", "()I", 3166733268L);
        validate(chunk, "Ö00000", "()V", 165820837L);
        validate(chunk, "Õ00000", "()V", 95148349L);
        PatchMCGLQuadSort.patch(t);
        changed.add(t.name);
        String[] fields = {"ô00000:Ljava/nio/ByteBuffer;", "õO0000:Z", "o00000:Z", "Ô00000:Z", "Stringsuper:Z", "ôO0000:Z"};
        for (String key : fields) {
            boolean found = false;
            for (FieldNode f : t.fields) found |= key.equals(f.name + ":" + f.desc);
            require(found, "tessellator field " + key);
        }
        int captures = 0, terrainBegins = 0, batchCalls = 0;
        for (ClassNode c : classes.values()) for (MethodNode m : c.methods) {
            boolean draw = c.name.equals(tess) && m.name.equals("new") && m.desc.equals("()I");
            for (AbstractInsnNode n : m.instructions.toArray()) if (n instanceof MethodInsnNode) {
                MethodInsnNode i = (MethodInsnNode)n;
                if (!draw && i.owner.equals("net/A/U/Objectnew") && i.name.equals("o00000") && i.desc.equals("(I)V")) {
                    InsnList guard = new InsnList();
                    guard.add(new LdcInsnNode("client texture selector"));
                    guard.add(new MethodInsnNode(INVOKESTATIC, HELPER, "unsupported", "(Ljava/lang/String;)V"));
                    m.instructions.insertBefore(i, guard);
                    changed.add(c.name);
                }
                if (!i.owner.startsWith("org/lwjgl/opengl/") || !i.name.startsWith("gl")) continue;
                if (draw && i.owner.equals(GL) && i.name.equals("glDrawArrays")) {
                    require(i.desc.equals("(III)V"), "draw descriptor");
                    InsnList args = new InsnList();
                    for (String key : fields) {
                        int sep = key.indexOf(':');
                        args.add(new VarInsnNode(ALOAD, 0));
                        args.add(new FieldInsnNode(GETFIELD, tess, key.substring(0, sep), key.substring(sep + 1)));
                    }
                    m.instructions.insertBefore(i, args);
                    i.owner = HELPER;
                    i.desc = "(IIILjava/nio/ByteBuffer;ZZZZZ)V";
                    captures++;
                } else if (i.owner.equals(GL) && i.name.equals("glNewList")) {
                    require(i.desc.equals("(II)V"), "list descriptor");
                    boolean terrain = c == chunk && m.desc.equals("()V") &&
                        (m.name.equals("Ö00000") || m.name.equals("Õ00000"));
                    if (terrain) terrainBegins++;
                    m.instructions.insertBefore(i, new InsnNode(terrain ? ICONST_1 : ICONST_0));
                    i.owner = HELPER; i.desc = "(IIZ)V";
                } else if (i.owner.equals(GL) && (
                        i.name.equals("glEndList") || i.name.equals("glDeleteLists") ||
                        i.name.equals("glCallList") ||
                        (i.name.equals("glCallLists") && i.desc.equals("(Ljava/nio/IntBuffer;)V")) ||
                        i.name.equals("glPushMatrix") || i.name.equals("glPopMatrix") ||
                        i.name.equals("glTranslatef") || i.name.equals("glScalef") || i.name.equals("glDepthMask") ||
                        (i.name.equals("glBindTexture") && i.desc.equals("(II)V")))) {
                    if (c.name.equals("net/A/U/thisclass") && i.name.equals("glCallLists")) batchCalls++;
                    i.owner = HELPER;
                } else if (draw && i.owner.equals(GL) && (
                        i.name.endsWith("Pointer") || i.name.equals("glEnableClientState") || i.name.equals("glDisableClientState"))) {
                    // These are immediate client state, not list commands. The
                    // draw snapshot below carries the validated 32-byte format.
                    continue;
                } else if (c.name.equals("net/A/U/Objectnew") && m.name.equals("o00000") && m.desc.equals("(I)V") &&
                        (i.name.equals("glClientActiveTexture") || i.name.equals("glClientActiveTextureARB"))) {
                    continue;
                } else {
                    // Conservative guard for *all* other GL calls in the jar,
                    // including special block renderers. Never partially replay
                    // an unknown list: keep its complete native fallback.
                    InsnList guard = new InsnList();
                    guard.add(new LdcInsnNode(i.owner.substring(i.owner.lastIndexOf('/') + 1) + "." + i.name));
                    guard.add(new MethodInsnNode(INVOKESTATIC, HELPER, "unsupported", "(Ljava/lang/String;)V"));
                    m.instructions.insertBefore(i, guard);
                }
                changed.add(c.name);
            }
        }
        require(captures == 2 && terrainBegins == 2 && batchCalls == 1,
                "capture/list/batch counts " + captures + "/" + terrainBegins + "/" + batchCalls);
    }
}
