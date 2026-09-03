import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Version-checked transforms applied to a staged official jar, never in place. */
public final class PatchMCGLPerformance implements Opcodes {
    private static final String CACHE = "local/mcgl/perf/AnimationCache";
    private static final String DIAGNOSTICS = "local/mcgl/perf/RenderDiagnostics";
    private static final String LIST = "local/mcgl/perf/ParticleList";
    private static final String GL = "org/lwjgl/opengl/GL11";
    private static final String DISPLAY = "org/lwjgl/opengl/Display";
    private static final String FX = "net/A/U/A/J";
    private static final String ENGINE = "net/A/U/Oooo";
    private static int particleEdits, animationEdits, windowEdits, renderProbes, tickProbes;
    private static final Set<String> changed = new HashSet<String>();
    private static final List<InsnList> atlasKeys = new ArrayList<InsnList>();

    private static void readAtlasKeys(ClassNode effect) {
        for (MethodNode m : effect.methods) if (m.name.equals("o00000") && m.desc.equals("(L" + ENGINE + ";)V")) {
            for (AbstractInsnNode ins : m.instructions.toArray()) if (ins instanceof MethodInsnNode) {
                MethodInsnNode i = (MethodInsnNode)ins;
                if (!i.owner.equals(ENGINE) || !i.desc.equals("(Ljava/lang/String;)I")) continue;
                AbstractInsnNode start = i.getPrevious();
                while (!(start instanceof VarInsnNode && start.getOpcode() == ALOAD && ((VarInsnNode)start).var == 1)) {
                    require(start instanceof FieldInsnNode || start instanceof MethodInsnNode, "atlas path expression");
                    start = start.getPrevious();
                }
                InsnList key = new InsnList();
                for (AbstractInsnNode n = start.getNext(); n != i; n = n.getNext())
                    key.add(n.clone(new HashMap<LabelNode, LabelNode>()));
                atlasKeys.add(key);
            }
        }
        require(atlasKeys.size() == 2, "terrain/items atlas paths");
    }

    private static void atlasKey(InsnList code) {
        LabelNode items = new LabelNode(), unknown = new LabelNode(), end = new LabelNode();
        field(code, "o00000", "I"); code.add(new JumpInsnNode(IFNE, items));
        for (AbstractInsnNode i : atlasKeys.get(0).toArray()) code.add(i.clone(new HashMap<LabelNode, LabelNode>()));
        code.add(new JumpInsnNode(GOTO, end)); code.add(items);
        field(code, "o00000", "I"); code.add(new InsnNode(ICONST_1));
        code.add(new JumpInsnNode(IF_ICMPNE, unknown));
        for (AbstractInsnNode i : atlasKeys.get(1).toArray()) code.add(i.clone(new HashMap<LabelNode, LabelNode>()));
        code.add(new JumpInsnNode(GOTO, end)); code.add(unknown);
        code.add(new InsnNode(ACONST_NULL)); code.add(end);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException("Unsupported MCGL bytecode: " + message);
    }
    private static MethodInsnNode call(int opcode, String owner, String name, String desc) {
        return new MethodInsnNode(opcode, owner, name, desc);
    }
    private static void field(InsnList code, String name, String desc) {
        code.add(new VarInsnNode(ALOAD, 3));
        code.add(new FieldInsnNode(GETFIELD, FX, name, desc));
    }

    private static void patchParticles(ClassNode c) {
        if (!c.name.equals("net/A/U/w")) return;
        for (MethodNode m : c.methods) if (m.name.equals("<init>")) {
            for (AbstractInsnNode ins : m.instructions.toArray()) {
                if (ins instanceof TypeInsnNode && ins.getOpcode() == NEW &&
                        ((TypeInsnNode)ins).desc.equals("java/util/ArrayList")) {
                    ((TypeInsnNode)ins).desc = LIST;
                    particleEdits++;
                    changed.add(c.name);
                }
                if (ins instanceof MethodInsnNode) {
                    MethodInsnNode i = (MethodInsnNode)ins;
                    if (i.owner.equals("java/util/ArrayList") && i.name.equals("<init>")) {
                        require(i.desc.equals("()V"), "particle list constructor");
                        i.owner = LIST;
                    }
                }
            }
        }
    }

    private static void patchTextures(ClassNode c) {
        for (MethodNode m : c.methods) {
            boolean animation = c.name.equals(ENGINE) && m.name.equals("Õ00000") && m.desc.equals("()V");
            for (AbstractInsnNode ins : m.instructions.toArray()) if (ins instanceof MethodInsnNode) {
                MethodInsnNode i = (MethodInsnNode)ins;
                if (i.owner.equals(GL) && (i.name.equals("glTexImage2D") || i.name.equals("glCopyTexImage2D") || i.name.equals("glCopyTexSubImage2D") ||
                        i.name.equals("glDeleteTextures") || (i.name.equals("glTexSubImage2D") && !animation))) {
                    // Upload arguments already on the operand stack remain intact.
                    m.instructions.insertBefore(i, call(INVOKESTATIC, CACHE, "invalidateAll", "()V"));
                    changed.add(c.name);
                }
            }
            if (!animation) continue;
            MethodInsnNode tick = null;
            AbstractInsnNode uploadLoop = null;
            int uploads = 0;
            LabelNode nextEffect = null;
            for (AbstractInsnNode ins : m.instructions.toArray()) {
                if (ins instanceof MethodInsnNode) {
                    MethodInsnNode i = (MethodInsnNode)ins;
                    if (i.owner.equals(FX) && i.name.equals("o00000") && i.desc.equals("()V")) tick = i;
                    if (i.owner.equals(GL) && i.name.equals("glTexSubImage2D")) uploads++;
                }
                if (ins.getOpcode() == ICONST_0 && ins.getNext() instanceof VarInsnNode &&
                        ins.getNext().getOpcode() == ISTORE && ((VarInsnNode)ins.getNext()).var == 4)
                    uploadLoop = ins;
            }
            require(tick != null && uploadLoop != null && uploads == 1 && m.maxLocals == 6, "animation loop shape");
            // The last backward GOTO in this method continues the outer effect iterator.
            for (AbstractInsnNode ins : m.instructions.toArray())
                if (ins.getOpcode() == GOTO) nextEffect = ((JumpInsnNode)ins).label;
            require(nextEffect != null, "animation continuation");
            // Move only this exact, side-effect-free-to-the-game buffer setup.
            // The binder MUST still run before the cache check: it may load a
            // texture and invalidate cached pixels. Do not jump over it on hits.
            InsnList bufferCopy = takeAnimationBufferCopy(m, tick);
            InsnList code = new InsnList();
            code.add(new VarInsnNode(ALOAD, 0));
            field(code, "return", "[B");
            field(code, "o00000", "I"); // atlas selector (terrain/items)
            field(code, "Ô00000", "I");
            code.add(call(INVOKESTATIC, "net/A/U/K", "new", "(I)I"));
            code.add(new VarInsnNode(ALOAD, 3));
            code.add(call(INVOKEVIRTUAL, FX, "new", "()I"));
            code.add(new InsnNode(IMUL));
            field(code, "Ô00000", "I");
            code.add(call(INVOKESTATIC, "net/A/U/K", "o00000", "(I)I"));
            code.add(new VarInsnNode(ALOAD, 3));
            code.add(call(INVOKEVIRTUAL, FX, "new", "()I"));
            code.add(new InsnNode(IMUL));
            code.add(new VarInsnNode(ALOAD, 3));
            code.add(call(INVOKEVIRTUAL, FX, "new", "()I"));
            field(code, "Ó00000", "I");
            atlasKey(code);
            code.add(call(INVOKESTATIC, CACHE, "upload", "(Ljava/lang/Object;[BIIIIILjava/lang/String;)Z"));
            code.add(new JumpInsnNode(IFEQ, nextEffect));
            code.add(bufferCopy);
            // Keep the original texture binder and its GL state side effects,
            // even when skipping an upload. Include the atlas path in the key:
            // switching to an already-loaded texture pack must not reuse it.
            m.instructions.insertBefore(uploadLoop, code);
            timeMethod(m, 0);
            animationEdits++;
            changed.add(c.name);
        }
    }

    private static String instructionKey(AbstractInsnNode n) {
        String key = Integer.toString(n.getOpcode());
        if (n instanceof VarInsnNode) key += ":" + ((VarInsnNode)n).var;
        if (n instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode)n;
            key += ":" + f.owner + ":" + f.name + ":" + f.desc;
        }
        if (n instanceof MethodInsnNode) {
            MethodInsnNode i = (MethodInsnNode)n;
            key += ":" + i.owner + ":" + i.name + ":" + i.desc;
        }
        return key;
    }

    private static InsnList takeAnimationBufferCopy(MethodNode m, MethodInsnNode tick) {
        String buffer = GETFIELD + ":" + ENGINE + ":new:Ljava/nio/ByteBuffer;";
        String pixels = GETFIELD + ":" + FX + ":return:[B";
        String[] expected = {
            ALOAD + ":0", buffer, INVOKEVIRTUAL + ":java/nio/ByteBuffer:clear:()Ljava/nio/Buffer;", "" + POP,
            ALOAD + ":0", buffer, ALOAD + ":3", pixels,
            INVOKEVIRTUAL + ":java/nio/ByteBuffer:put:([B)Ljava/nio/ByteBuffer;", "" + POP,
            ALOAD + ":0", buffer, "" + ICONST_0,
            INVOKEVIRTUAL + ":java/nio/ByteBuffer:position:(I)Ljava/nio/Buffer;",
            ALOAD + ":3", pixels, "" + ARRAYLENGTH,
            INVOKEVIRTUAL + ":java/nio/Buffer:limit:(I)Ljava/nio/Buffer;", "" + POP
        };
        List<AbstractInsnNode> nodes = new ArrayList<AbstractInsnNode>();
        AbstractInsnNode n = tick.getNext();
        for (String key : expected) {
            require(n != null && key.equals(instructionKey(n)), "animation buffer preparation");
            nodes.add(n); n = n.getNext();
        }
        require(n instanceof VarInsnNode && n.getOpcode() == ALOAD && ((VarInsnNode)n).var == 3 &&
                (GETFIELD + ":" + FX + ":Ô00000:I").equals(instructionKey(n.getNext())), "animation binder follows buffer");
        InsnList result = new InsnList();
        for (AbstractInsnNode move : nodes) { m.instructions.remove(move); result.add(move); }
        return result;
    }

    private static void timeMethod(MethodNode m, int phase) {
        int started = m.maxLocals;
        m.maxLocals += 2;
        InsnList before = new InsnList();
        before.add(call(INVOKESTATIC, DIAGNOSTICS, "begin", "()J"));
        before.add(new VarInsnNode(LSTORE, started));
        m.instructions.insert(before);
        for (AbstractInsnNode ins : m.instructions.toArray()) if (ins.getOpcode() == RETURN)
            m.instructions.insertBefore(ins, endTiming(phase, started));
    }

    private static InsnList endTiming(int phase, int started) {
        InsnList code = new InsnList();
        code.add(new InsnNode(ICONST_0 + phase));
        code.add(new VarInsnNode(LLOAD, started));
        code.add(call(INVOKESTATIC, DIAGNOSTICS, "end", "(IJ)V"));
        return code;
    }

    private static void patchPhaseProbes(ClassNode c) {
        if (c.name.equals("net/A/U/H")) {
            for (MethodNode m : c.methods) if (m.name.equals("Ö00000") && m.desc.equals("()V")) {
                // The validated method first returns immediately for a clean
                // chunk. Start AFTER that guard: count actual builds only.
                AbstractInsnNode counter = null;
                for (AbstractInsnNode n : m.instructions.toArray()) if (n instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode)n;
                    if (f.getOpcode() == GETSTATIC && f.owner.equals(c.name) && f.name.equals("privatesuper")) { counter = n; break; }
                }
                require(counter != null, "chunk build counter");
                int started = m.maxLocals; m.maxLocals += 2;
                InsnList before = new InsnList(); before.add(call(INVOKESTATIC, DIAGNOSTICS, "begin", "()J"));
                before.add(new VarInsnNode(LSTORE, started));
                boolean entered = false;
                for (AbstractInsnNode n : m.instructions.toArray()) {
                    if (n == counter) entered = true;
                    if (entered && n.getOpcode() == RETURN) m.instructions.insertBefore(n, endTiming(3, started));
                }
                m.instructions.insertBefore(counter, before);
                changed.add(c.name);
            }
        }
        if (!c.name.equals("net/minecraft/client/Minecraft")) return;
        for (MethodNode m : c.methods) if (m.name.equals("run") && m.desc.equals("()V")) {
            int started = m.maxLocals;
            m.maxLocals += 2;
            for (AbstractInsnNode ins : m.instructions.toArray()) if (ins instanceof MethodInsnNode) {
                MethodInsnNode i = (MethodInsnNode)ins;
                int phase;
                if (i.owner.equals("net/A/U/oooO") && i.name.equals("Ò00000") && i.desc.equals("(F)V")) {
                    phase = 1; renderProbes++;
                } else if (i.owner.equals(c.name) && i.name.equals("Ôo0000") && i.desc.equals("()V")) {
                    phase = 2; tickProbes++;
                } else continue;
                InsnList before = new InsnList();
                before.add(call(INVOKESTATIC, DIAGNOSTICS, "begin", "()J"));
                before.add(new VarInsnNode(LSTORE, started));
                m.instructions.insertBefore(i, before);
                m.instructions.insert(i, endTiming(phase, started));
                changed.add(c.name);
            }
        }
    }

    private static void patchWindow(ClassNode c) {
        if (!c.name.equals("net/minecraft/client/Minecraft")) return;
        MethodNode toggle = null, run = null;
        String resize = null;
        for (MethodNode m : c.methods) {
            if (m.name.equals("run") && m.desc.equals("()V")) run = m;
            if (!m.desc.equals("()V")) continue;
            for (AbstractInsnNode ins : m.instructions.toArray()) if (ins instanceof MethodInsnNode) {
                MethodInsnNode i = (MethodInsnNode)ins;
                if (i.owner.equals(DISPLAY) && i.name.equals("setFullscreen") && m.name.equals("ôo0000")) toggle = m;
            }
        }
        require(toggle != null && run != null, "fullscreen/run methods");
        for (AbstractInsnNode ins : toggle.instructions.toArray()) if (ins instanceof MethodInsnNode) {
            MethodInsnNode i = (MethodInsnNode)ins;
            if (i.getOpcode() == INVOKESPECIAL && i.owner.equals(c.name) && i.desc.equals("(II)V")) resize = i.name;
        }
        require(resize != null, "GUI resize method");
        int awtTails = 0;
        for (MethodNode method : c.methods) if (method.name.equals(resize) && method.desc.equals("(II)V")) {
            for (AbstractInsnNode ins : method.instructions.toArray()) if (ins instanceof MethodInsnNode) {
                MethodInsnNode i = (MethodInsnNode)ins;
                if (i.owner.equals("net/minecraft/client/MinecraftApplet") && i.name.equals("getParent")) {
                    AbstractInsnNode field = i.getPrevious(), receiver = field.getPrevious();
                    require(field instanceof FieldInsnNode && receiver.getOpcode() == ALOAD, "AWT resize tail");
                    // Dimensions and the scaled GUI have already been updated.
                    // There is no java.awt.Frame in the peerless Cocoa launcher.
                    method.instructions.insertBefore(receiver, new InsnNode(RETURN));
                    awtTails++;
                }
            }
        }
        require(awtTails == 1, "single peerless AWT resize tail");
        int active = 0, automaticToggle = 0, canvasWidth = 0, canvasHeight = 0;
        for (AbstractInsnNode ins : run.instructions.toArray()) if (ins instanceof MethodInsnNode) {
            MethodInsnNode i = (MethodInsnNode)ins;
            if (i.owner.equals("java/awt/Canvas") && i.desc.equals("()I") &&
                    (i.name.equals("getWidth") || i.name.equals("getHeight"))) {
                AbstractInsnNode canvas = i.getPrevious(), receiver = canvas.getPrevious();
                require(canvas instanceof FieldInsnNode && canvas.getOpcode() == GETFIELD &&
                        ((FieldInsnNode)canvas).desc.equals("Ljava/awt/Canvas;") &&
                        receiver instanceof VarInsnNode && receiver.getOpcode() == ALOAD &&
                        ((VarInsnNode)receiver).var == 0, "peerless Canvas dimensions");
                if (i.name.equals("getWidth")) canvasWidth++; else canvasHeight++;
                // The compatibility Canvas stays at its initial size; the real
                // drawable belongs to Cocoa. Never resize the GUI back to 900x700.
                run.instructions.remove(receiver);
                run.instructions.remove(canvas);
                run.instructions.set(i, call(INVOKESTATIC, DISPLAY, i.name, "()I"));
            }
            if (i.owner.equals(DISPLAY) && i.name.equals("isActive")) {
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(ALOAD, 0));
                hook.add(call(INVOKESPECIAL, c.name, "mcglResizeWindow", "()V"));
                run.instructions.insertBefore(i, hook);
                active++;
            }
            if (i.owner.equals(c.name) && i.name.equals(toggle.name)) {
                // Do not rebuild a fullscreen window just because macOS hides it.
                run.instructions.set(i, new InsnNode(POP));
                automaticToggle++;
            }
        }
        require(active == 1 && automaticToggle == 1, "focus-loss fullscreen branch");
        require(canvasWidth == 2 && canvasHeight == 2, "Canvas dimension reads");
        MethodNode m = new MethodNode(ACC_PRIVATE, "mcglResizeWindow", "()V", null, null);
        Label end = new Label();
        m.visitCode();
        m.visitMethodInsn(INVOKESTATIC, DISPLAY, "isFullscreen", "()Z");
        m.visitJumpInsn(IFNE, end);
        m.visitMethodInsn(INVOKESTATIC, DISPLAY, "wasResized", "()Z");
        m.visitJumpInsn(IFEQ, end);
        m.visitMethodInsn(INVOKESTATIC, DISPLAY, "getWidth", "()I"); m.visitVarInsn(ISTORE, 1);
        m.visitMethodInsn(INVOKESTATIC, DISPLAY, "getHeight", "()I"); m.visitVarInsn(ISTORE, 2);
        m.visitVarInsn(ILOAD, 1); m.visitJumpInsn(IFLE, end);
        m.visitVarInsn(ILOAD, 2); m.visitJumpInsn(IFLE, end);
        m.visitVarInsn(ALOAD, 0); m.visitVarInsn(ILOAD, 1); m.visitVarInsn(ILOAD, 2);
        m.visitMethodInsn(INVOKESPECIAL, c.name, resize, "(II)V");
        m.visitLabel(end); m.visitInsn(RETURN); m.visitMaxs(3, 3); m.visitEnd();
        c.methods.add(m);
        windowEdits++;
        changed.add(c.name);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "usage: input.jar output.jar");
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        try (JarFile jar = new JarFile(args[0])) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                try (InputStream in = jar.getInputStream(e)) { files.put(e.getName(), read(in)); }
            }
        }
        // Unknown custom texture binders must be reviewed before caching uploads.
        Set<String> effects = new HashSet<String>(); effects.add(FX);
        Map<String, ClassNode> classes = new LinkedHashMap<String, ClassNode>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) if (e.getKey().endsWith(".class")) {
            ClassNode c = new ClassNode(); new ClassReader(e.getValue()).accept(c, 0);
            classes.put(e.getKey(), c);
        }
        boolean grew;
        require(classes.containsKey(FX + ".class"), "base texture effect");
        readAtlasKeys(classes.get(FX + ".class"));
        do { grew = false; for (ClassNode c : classes.values()) if (effects.contains(c.superName)) grew |= effects.add(c.name); } while (grew);
        for (ClassNode c : classes.values()) if (effects.contains(c.name) && !c.name.equals(FX))
            for (MethodNode m : c.methods) require(!(m.name.equals("o00000") && m.desc.equals("(L" + ENGINE + ";)V")), "custom texture binder " + c.name);
        PatchMCGLLightmap.patch(classes, changed);
        PatchMCGLChunkVbo.patch(classes, changed);
        for (Map.Entry<String, ClassNode> e : classes.entrySet()) {
            ClassNode c = e.getValue();
            // The official client uses Java 6 classes; retain verifier frames by
            // recomputing with the input jar on the patch tool's class path.
            patchParticles(c); patchTextures(c); patchWindow(c); patchPhaseProbes(c);
            if (!changed.contains(c.name)) continue;
            ClassWriter writer = new HierarchyWriter(classes);
            c.accept(writer); files.put(e.getKey(), writer.toByteArray());
        }
        require(particleEdits == 1 && animationEdits == 1 && windowEdits == 1, "expected patch counts");
        require(renderProbes == 1 && tickProbes == 1, "expected render/tick phase probes");
        for (String name : new String[]{LIST, CACHE, CACHE + "$Entry", DIAGNOSTICS,
                PatchMCGLLightmap.HELPER, PatchMCGLLightmap.HELPER + "$Cache", PatchMCGLLightmap.HELPER + "$Entry",
                PatchMCGLChunkVbo.HELPER, PatchMCGLChunkVbo.HELPER + "$Mesh", PatchMCGLChunkVbo.HELPER + "$Command",
                PatchMCGLChunkVbo.HELPER + "$MeshIndex", PatchMCGLQuadSort.HELPER, PatchMCGLQuadSort.HELPER + "$Scratch"}) {
            try (InputStream in = PatchMCGLPerformance.class.getResourceAsStream("/" + name + ".class")) {
                require(in != null, "missing helper " + name); files.put(name + ".class", read(in));
            }
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(Paths.get(args[1]), StandardOpenOption.CREATE_NEW))) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey())); out.write(e.getValue()); out.closeEntry();
            }
        }
        System.out.println("MCGL patches: ordered particles, animation cache, isolated lightmap uploads, stable quad sorting, phase diagnostics, safe focus/resize, optional terrain VBO/VAO with native fallback.");
    }
    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return out.toByteArray();
    }

    /** Compute verifier frames from metadata only. Loading client classes while
     * installing can run their static initializers (including AWT/native code). */
    static final class HierarchyWriter extends ClassWriter {
        final Map<String, ClassNode> nodes = new HashMap<String, ClassNode>();
        HierarchyWriter(Map<String, ClassNode> classes) {
            super(COMPUTE_FRAMES | COMPUTE_MAXS);
            for (ClassNode c : classes.values()) nodes.put(c.name, c);
        }
        ClassNode node(String name) {
            ClassNode c = nodes.get(name);
            if (c != null) return c;
            try (InputStream in = PatchMCGLPerformance.class.getResourceAsStream("/" + name + ".class")) {
                require(in != null, "missing hierarchy metadata " + name);
                c = new ClassNode(); new ClassReader(in).accept(c, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                nodes.put(name, c); return c;
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
        String component(String name) {
            String c = name.substring(1);
            return c.charAt(0) == 'L' ? c.substring(1, c.length() - 1) : c;
        }
        boolean primitive(String name) { return name.length() == 1; }
        boolean assignable(String target, String source) {
            if (target.equals(source) || target.equals("java/lang/Object")) return true;
            if (source.startsWith("[")) {
                if (target.equals("java/lang/Cloneable") || target.equals("java/io/Serializable")) return true;
                if (!target.startsWith("[")) return false;
                String a = component(target), b = component(source);
                return primitive(a) || primitive(b) ? a.equals(b) : assignable(a, b);
            }
            if (target.startsWith("[")) return false;
            ClassNode c = node(source);
            if (c.superName != null && assignable(target, c.superName)) return true;
            for (String i : c.interfaces) if (assignable(target, i)) return true;
            return false;
        }
        protected String getCommonSuperClass(String a, String b) {
            if (assignable(a, b)) return a;
            if (assignable(b, a)) return b;
            if (a.startsWith("[") && b.startsWith("[")) {
                String x = component(a), y = component(b);
                if (primitive(x) || primitive(y)) return "java/lang/Object";
                String c = getCommonSuperClass(x, y);
                return "[" + (c.startsWith("[") ? c : "L" + c + ";");
            }
            if (a.startsWith("[") || b.startsWith("[") ||
                    (node(a).access & ACC_INTERFACE) != 0 || (node(b).access & ACC_INTERFACE) != 0) return "java/lang/Object";
            do { a = node(a).superName; } while (!assignable(a, b));
            return a;
        }
    }
}
