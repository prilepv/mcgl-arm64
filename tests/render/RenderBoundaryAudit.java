import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Enforce architectural boundaries in the built classes, including descriptors and handles. */
public final class RenderBoundaryAudit implements Opcodes {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("baseline-lwjgl.jar candidate-lwjgl.jar manifest [rendered-client.jar ...]");
        SortedMap<String, RenderCommandSpec> commands = RenderCommandSpec.read(Paths.get(args[2]));
        int rendererClasses = 0, adapters = 0, nativeCalls = 0, compatibilityCalls = 0, coreCalls = 0, frameCalls = 0, shaderCalls = 0, meshCalls = 0, gameCalls = 0;
        try (JarFile baseline = new JarFile(args[0]); JarFile candidate = new JarFile(args[1])) {
            require(candidate.getEntry("local/mcgl/render/RenderDevice.class") != null, "No renderer contract in candidate");
            require(Arrays.equals(readBytes(candidate, "META-INF/mcgl/render-commands.txt"), Files.readAllBytes(Paths.get(args[2]))),
                    "Packaged command manifest does not match the audited source");
            Set<String> expectedMethods = new TreeSet<String>();
            for (RenderCommandSpec command : commands.values()) {
                expectedMethods.add(command.dispatch + command.descriptor);
                ClassNode adapter = read(candidate, command.bridge + ".class");
                require("java/lang/Object".equals(adapter.superName), "Legacy adapter inherits native methods");
                MethodNode method = method(adapter, command.name, command.descriptor);
                require((method.access & (ACC_PUBLIC | ACC_STATIC)) == (ACC_PUBLIC | ACC_STATIC), "Adapter ABI is not public static");
                List<MethodInsnNode> calls = calls(method);
                int hooks = command.family.equals("GL11") && command.name.equals("glNewList") ? 1 : 0;
                require(calls.size() == 2 + hooks, "Unexpected work inside typed adapter");
                if (hooks == 1) require(calls.get(0).owner.equals("local/mcgl/render/RenderSystem")
                        && calls.get(0).name.equals("recordDisplayList"), "Missing legacy list profiler event");
                require(calls.get(hooks).owner.equals("local/mcgl/render/RenderSystem") && calls.get(hooks).name.equals("commands"), "Adapter bypasses render context");
                MethodInsnNode dispatch = calls.get(hooks + 1);
                require(dispatch.owner.equals("local/mcgl/render/LegacyRenderCommands") && dispatch.name.equals(command.dispatch)
                        && dispatch.desc.equals(command.descriptor), "Typed dispatch mismatch");
                MethodNode guard = method(read(candidate, "local/mcgl/render/GuardedRenderCommands.class"), command.dispatch, command.descriptor);
                List<MethodInsnNode> guarded = calls(guard);
                require(guarded.size() == 2 && guarded.get(0).owner.equals("local/mcgl/render/RenderContext")
                        && guarded.get(0).name.equals("checkOwner") && guarded.get(1).name.equals(command.dispatch), "Missing per-command ownership guard");
            }
            Set<String> actualMethods = new TreeSet<String>();
            for (MethodNode method : read(candidate, "local/mcgl/render/LegacyRenderCommands.class").methods)
                actualMethods.add(method.name + method.desc);
            require(actualMethods.equals(expectedMethods), "Contract contains missing/extra commands");
            if(candidate.getEntry("local/mcgl/render/GameRenderCommands.class")!=null) {
                ClassNode guards=read(candidate,"local/mcgl/render/GuardedGameRenderCommands.class");
                for(MethodNode contract:read(candidate,"local/mcgl/render/GameRenderCommands.class").methods) {
                    MethodNode guarded=method(guards,contract.name,contract.desc);List<MethodInsnNode> sequence=calls(guarded);
                    require(sequence.get(0).owner.equals("local/mcgl/render/RenderContext")&&sequence.get(0).name.equals(contract.name.equals("abandon")?"isClosed":"checkOwner"),"Missing game context guard: "+contract.name);
                }
                for(RenderCommandSpec command:commands.values()) {
                    ClassNode adapter=read(candidate,"local/mcgl/render/game/"+command.family+".class");
                    require(adapter.superName.equals("java/lang/Object"),"Game adapter inherits native methods");
                    List<MethodInsnNode> sequence=calls(method(adapter,command.name,command.descriptor));
                    require(sequence.get(0).owner.equals("local/mcgl/render/RenderSystem")&&sequence.get(0).name.equals("game")
                            &&sequence.get(1).owner.equals("local/mcgl/render/GameRenderCommands"),"Game adapter bypasses typed context");
                }
            }
            for (Enumeration<JarEntry> entries = candidate.entries(); entries.hasMoreElements();) {
                String name = entries.nextElement().getName();
                boolean renderer = name.startsWith("local/mcgl/render/"), platform = name.startsWith("local/mcgl/platform/");
                if (!name.endsWith(".class") || !(renderer || platform)) continue;
                boolean backend = name.startsWith("local/mcgl/render/backend/");
                boolean composition = name.equals("local/mcgl/render/RenderSystem.class");
                ClassNode type = read(candidate, name);
                for (String reference : references(type)) {
                    if (renderer) {
                        require(!reference.startsWith("local/mcgl/platform/") && !reference.startsWith("local/mcgl/perf/")
                                && !reference.startsWith("net/minecraft/") && !reference.startsWith("net/mcgl/")
                                && !reference.startsWith("java/awt/") && !reference.startsWith("java/applet/"), "Renderer imports game/window type: " + name + " -> " + reference);
                        if (!backend) require(!reference.startsWith("org/lwjgl/"), "Native binding leaked into renderer contract/adapter");
                        if (!backend && !composition) require(!reference.startsWith("local/mcgl/render/backend/"), "Backend selection leaked outside composition root");
                        if (backend) require(!reference.startsWith("org/lwjgl/opengl/MCGL")
                                && !reference.equals("org/lwjgl/opengl/GLContext") && !reference.equals("org/lwjgl/opengl/ContextCapabilities")
                                && !reference.equals("org/lwjgl/opengl/Display") && !reference.startsWith("local/mcgl/render/legacy/"),
                                "Native backend imports legacy adapter: " + reference);
                    }
                    if (platform) require(!reference.startsWith("local/mcgl/render/"), "Platform depends on renderer");
                }
                if (renderer) for (MethodNode method : type.methods) for (MethodInsnNode call : calls(method)) {
                    if (call.owner.startsWith("org/lwjgl/opengl/") && call.name.startsWith("gl")) {
                        require(backend, "Native rendering outside backend: " + name); nativeCalls++;
                        if (name.equals("local/mcgl/render/backend/CompatibilityCommands.class")
                                || name.equals("local/mcgl/render/backend/CompatibilityBackend.class")) compatibilityCalls++;
                        else if (name.equals("local/mcgl/render/backend/Core41Backend.class")) {
                            require(call.owner.equals("org/lwjgl/opengl/GL11C")
                                    && (call.name.equals("glGetInteger") || call.name.equals("glGetString")),
                                    "Core context initialization contains unexpected rendering: " + call.name);
                            coreCalls++;
                        } else if (name.equals("local/mcgl/render/backend/NativeFrameCommands.class")) {
                            require(call.owner.equals("org/lwjgl/opengl/GL11C")
                                    && (call.name.equals("glViewport") || call.name.equals("glClearColor") || call.name.equals("glClear")),
                                    "Frame operations contain non-Core rendering: " + call.name);
                            frameCalls++;
                        } else if (name.matches("local/mcgl/render/backend/NativeShaderPipeline(\\$[^/]*)?\\.class")) {
                            boolean lifecycle = call.owner.equals("org/lwjgl/opengl/GL20C") && call.name.matches(
                                    "gl(CreateProgram|AttachShader|LinkProgram|GetProgramInfoLog|GetProgrami|DetachShader|DeleteShader|DeleteProgram"
                                    + "|CreateShader|ShaderSource|CompileShader|GetShaderInfoLog|GetShaderi|UseProgram|GetActiveUniform|GetUniformLocation)");
                            boolean uniform = call.owner.equals("org/lwjgl/opengl/GL41C") && call.name.matches(
                                    "glProgramUniform(1i|1f|2f|3f|4f|Matrix3fv|Matrix4fv)");
                            boolean binding = call.owner.equals("org/lwjgl/opengl/GL11C") && call.name.equals("glGetInteger");
                            require(lifecycle || uniform || binding, "Unexpected shader backend native command: " + call.name);
                            shaderCalls++;
                        } else if (name.matches("local/mcgl/render/backend/NativeMesh(Pipeline|Arena)(\\$[^/]*)?\\.class")) {
                            boolean buffer = call.owner.equals("org/lwjgl/opengl/GL15C") && call.name.matches(
                                    "gl(GenBuffers|BindBuffer|BufferData|BufferSubData|GetBufferSubData|GetBufferParameteri|DeleteBuffers)");
                            boolean vao = call.owner.equals("org/lwjgl/opengl/GL30C") && call.name.matches(
                                    "gl(GenVertexArrays|BindVertexArray|DeleteVertexArrays)");
                            boolean attribute = call.owner.equals("org/lwjgl/opengl/GL20C") && call.name.matches(
                                    "gl(VertexAttribPointer|EnableVertexAttribArray)");
                            boolean draw = call.owner.equals("org/lwjgl/opengl/GL11C") && call.name.equals("glDrawElements")
                                    && call.desc.equals("(IIIJ)V"); // GPU byte offset only, no client-memory indices.
                            boolean arena=name.startsWith("local/mcgl/render/backend/NativeMeshArena");
                            if(arena&&call.owner.equals("org/lwjgl/opengl/GL32C"))draw|=
                                    call.name.equals("glDrawElementsBaseVertex")&&call.desc.equals("(IIIJI)V")
                                    ||call.name.equals("glMultiDrawElementsBaseVertex")
                                    &&call.desc.equals("(ILjava/nio/IntBuffer;ILorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;)V");
                            boolean query = call.owner.equals("org/lwjgl/opengl/GL11C") && call.name.equals("glGetInteger");
                            require(buffer || vao || attribute || draw || query, "Unexpected mesh native command: " + call.name);
                            if (method.name.equals("draw")) require(draw || vao, "Per-draw query/allocation in mesh backend");
                            if (attribute && call.name.equals("glVertexAttribPointer"))
                                require(call.desc.equals("(IIIZIJ)V"), "Mesh uses a client-memory vertex pointer");
                            meshCalls++;
                        } else if(name.matches("local/mcgl/render/backend/Game(Renderer|RasterState|Commands)(\\$[^/]*)?\\.class")) {
                            require(gameNative(call),"Non-Core or unexpected game raster operation: "+call.owner+"."+call.name+call.desc);
                            gameCalls++;
                        }
                    }
                    require(!call.owner.equals("java/lang/reflect/Method") && !call.owner.equals("java/lang/reflect/Proxy"), "Reflective command dispatch in production");
                }
                if (renderer) rendererClasses++;
                if (name.startsWith("local/mcgl/render/legacy/")) adapters++;
            }
            require(adapters == 13 && compatibilityCalls == commands.size() + 3, "Unexpected compatibility command inventory");
            boolean core = candidate.getEntry("local/mcgl/render/backend/Core41Backend.class") != null;
            require(coreCalls == (core ? 8 : 0) && frameCalls == (core ? 3 : 0), "Unexpected Core/frame command inventory");
            boolean shaders = candidate.getEntry("local/mcgl/render/backend/NativeShaderPipeline.class") != null;
            // javac --release 8 duplicates finally cleanup: five calls in create and one in compile.
            // 33 source call sites become 39 bytecode invocation sites, all independently allowlisted above.
            // The bounded matrix-array setter adds one more program-targeted upload.
            boolean matrixArrays=false;if(shaders)for(MethodNode method:read(candidate,"local/mcgl/render/backend/NativeShaderPipeline$Program$Uniform.class").methods)if(method.name.equals("setMatrix4Array"))matrixArrays=true;
            require(shaderCalls == (shaders ? 39+(matrixArrays?1:0) : 0), "Unexpected shader native command inventory: " + shaderCalls);
            if (shaders) {
                require(candidate.getEntry("local/mcgl/render/shaders/material.vert") != null
                        && candidate.getEntry("local/mcgl/render/shaders/material.frag") != null, "Bundled shader sources are missing");
            }
            boolean meshes = candidate.getEntry("local/mcgl/render/backend/NativeMeshPipeline.class") != null;
            boolean combinedMeshes=false;if(meshes)for(MethodNode method:read(candidate,"local/mcgl/render/backend/NativeMeshPipeline.class").methods)if(method.name.equals("combine"))combinedMeshes=true;
            boolean streamReplacement=false;if(meshes)for(MethodNode method:read(candidate,"local/mcgl/render/backend/NativeMeshPipeline$NativeMesh.class").methods)if(method.name.equals("replaceStream"))streamReplacement=true;
            boolean meshArena=candidate.getEntry("local/mcgl/render/backend/NativeMeshArena.class")!=null;
            boolean arenaImport=false;if(meshArena)for(MethodNode method:read(candidate,"local/mcgl/render/backend/NativeMeshArena.class").methods)if(method.name.equals("importMesh"))arenaImport=true;
            // Arena: 9 allocation/multi-submit, 4 member draw/readback, 26 page lifecycle
            // invocation sites, including javac's duplicated finally cleanup. Only two
            // draw entry points are allowed, both indexed GPU-offset/BaseVertex variants.
            // Bounded import adds four source read sites and three binding-save/restore
            // sites (including duplicated finally), never a per-draw native operation.
            require(meshCalls == (meshes ? (combinedMeshes?57:34)+(streamReplacement?5:0)+(meshArena?39:0)+(arenaImport?7:0) : 0), "Unexpected mesh native command inventory: " + meshCalls);
            require(nativeCalls == compatibilityCalls + coreCalls + frameCalls + shaderCalls + meshCalls + gameCalls, "Native calls in an unaudited backend");
            for (String name : new String[] {"Display", "GLContext", "ContextCapabilities", "MCGLGL11", "MCGLGL13", "MCGLARBMultitexture", "Util"})
                require(publicAbi(read(baseline, "org/lwjgl/opengl/" + name + ".class")).equals(
                        publicAbi(read(candidate, "org/lwjgl/opengl/" + name + ".class"))), "Legacy public ABI changed: " + name);
        }
        int callsites = 0;
        for (int index = 3; index < args.length; index++) try (JarFile client = new JarFile(args[index])) {
            boolean game=client.getEntry("META-INF/mcgl/game-core-v1")!=null;
            for (Enumeration<JarEntry> entries = client.entries(); entries.hasMoreElements();) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                if(game&&name.startsWith("local/mcgl/perf/ChunkVbo"))continue; // unreachable reference implementation
                for (MethodNode method : read(client, name).methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if(game&&method.name.equals("mcglChunkLegacyDraw"))continue;
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode)instruction;
                        callsites += auditCall(commands, call.owner, call.name, call.desc,game);
                    } else if (instruction instanceof InvokeDynamicInsnNode) {
                        InvokeDynamicInsnNode call = (InvokeDynamicInsnNode)instruction;
                        callsites += auditHandle(commands, call.bsm,game);
                        for (Object argument : call.bsmArgs) if (argument instanceof MethodHandle) callsites += auditHandle(commands, (MethodHandle)argument,game);
                    } else if (instruction instanceof LdcInsnNode && ((LdcInsnNode)instruction).cst instanceof MethodHandle)
                        callsites += auditHandle(commands, (MethodHandle)((LdcInsnNode)instruction).cst,game);
                }
            }
        }
        if (args.length > 3) require(callsites > 0, "No real client call sites routed through renderer");
        System.out.println("RENDER_BOUNDARY_PASS classes=" + rendererClasses + " adapters=" + adapters + " commands=" + commands.size()
                + " routedCallsites=" + callsites + " publicABIs=7 native-direction/typed-guards/manifest=checked");
    }
    private static boolean gameNative(MethodInsnNode call) {
        // No immediate mode, display lists, matrix/attribute stacks, legacy lighting, client arrays,
        // glDrawArrays or shader creation are admitted here. Geometry/shaders keep their own owners.
        switch(call.owner) {
            case "org/lwjgl/opengl/GL11C":return call.name.matches("gl(BindTexture|BlendFunc|Clear|ClearColor|ClearDepth|ClearStencil|ColorMask|CopyTexSubImage2D|CullFace|DeleteTextures|DepthFunc|DepthMask|DepthRange|Disable|Enable|FrontFace|GenTextures|GetBoolean|GetError|GetFloat|GetFloatv|GetInteger|GetIntegerv|GetString|GetTexParameteri|IsEnabled|LineWidth|LogicOp|PixelStorei|PolygonOffset|ReadPixels|StencilFunc|StencilOp|TexImage2D|TexParameteri|TexSubImage2D|Viewport)");
            case "org/lwjgl/opengl/GL13C":return call.name.equals("glActiveTexture");
            case "org/lwjgl/opengl/GL14C":return call.name.matches("gl(BlendEquation|BlendFuncSeparate)");
            case "org/lwjgl/opengl/GL20C":return call.name.equals("glBlendEquationSeparate");
            case "org/lwjgl/opengl/GL15C":return call.name.matches("gl(GenQueries|BeginQuery|EndQuery|GetQueryObjectuiv|GenBuffers|BindBuffer|BufferData|GetBufferParameteri|DeleteBuffers)");
            case "org/lwjgl/opengl/GL30C":return call.name.matches("gl(GetStringi|GenFramebuffers|BindFramebuffer|DeleteFramebuffers|CheckFramebufferStatus|FramebufferTexture2D|GenRenderbuffers|BindRenderbuffer|RenderbufferStorage|FramebufferRenderbuffer|DeleteRenderbuffers|GenVertexArrays|BindVertexArray|DeleteVertexArrays)");
            default:return false;
        }
    }
    private static int auditHandle(Map<String, RenderCommandSpec> commands, MethodHandle handle,boolean game) {
        return auditCall(commands, handle.getOwner(), handle.getName(), handle.getDesc(),game);
    }
    private static int auditCall(Map<String, RenderCommandSpec> commands, String owner, String name, String descriptor,boolean game) {
        require(!(owner.startsWith("org/lwjgl/opengl/") && name.startsWith("gl")), "Client bypasses renderer: " + owner + "." + name);
        if(game)require(!owner.startsWith("local/mcgl/render/legacy/")&&!owner.equals("local/mcgl/perf/ChunkVbo"),"Core game still calls legacy renderer: "+owner+"."+name);
        String prefix=game?"local/mcgl/render/game/":"local/mcgl/render/legacy/";
        if (!owner.startsWith(prefix)) return 0;
        String original = "org/lwjgl/opengl/" + owner.substring(prefix.length());
        require(commands.containsKey(RenderCommandSpec.key(original, name, descriptor)), "Unknown bridge call");
        return 1;
    }
    private static Set<String> publicAbi(ClassNode type) {
        Set<String> result = new TreeSet<String>();
        for (MethodNode method : type.methods) if ((method.access & ACC_PUBLIC) != 0)
            result.add("M " + method.name + method.desc + " " + (method.access & ACC_STATIC));
        for (FieldNode field : type.fields) if ((field.access & ACC_PUBLIC) != 0)
            result.add("F " + field.name + field.desc + " " + field.access);
        return result;
    }
    private static MethodNode method(ClassNode type, String name, String descriptor) {
        for (MethodNode method : type.methods) if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        throw new AssertionError("Missing method " + type.name + "." + name + descriptor);
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<MethodInsnNode>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) calls.add((MethodInsnNode)instruction);
        return calls;
    }
    private static byte[] readBytes(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name); require(entry != null, "Missing entry: " + name);
        try (InputStream input = jar.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
    private static ClassNode read(JarFile jar, String name) throws IOException {
        ClassNode type = new ClassNode(); new ClassReader(readBytes(jar, name)).accept(type, ClassReader.SKIP_DEBUG); return type;
    }
    private static Set<String> references(ClassNode type) {
        Set<String> result = new TreeSet<String>();
        if (type.superName != null) result.add(type.superName);
        result.addAll(type.interfaces);
        for (FieldNode field : type.fields) descriptor(result, field.desc);
        for (MethodNode method : type.methods) {
            descriptor(result, method.desc); result.addAll(method.exceptions);
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof TypeInsnNode) {
                    String name = ((TypeInsnNode)instruction).desc;
                    if (name.startsWith("[")) descriptor(result, name); else result.add(name);
                } else if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode)instruction; result.add(call.owner); descriptor(result, call.desc);
                } else if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode)instruction; result.add(field.owner); descriptor(result, field.desc);
                } else if (instruction instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode call = (InvokeDynamicInsnNode)instruction; descriptor(result, call.desc); handle(result, call.bsm);
                    for (Object argument : call.bsmArgs) {
                        if (argument instanceof MethodHandle) handle(result, (MethodHandle)argument);
                        else if (argument instanceof MethodType) descriptor(result, ((MethodType)argument).getDescriptor());
                        else if (argument instanceof Type) type(result, (Type)argument);
                    }
                } else if (instruction instanceof LdcInsnNode) {
                    Object value = ((LdcInsnNode)instruction).cst;
                    if (value instanceof Type) type(result, (Type)value);
                    else if (value instanceof MethodHandle) handle(result, (MethodHandle)value);
                }
            }
        }
        return result;
    }
    private static void handle(Set<String> result, MethodHandle handle) { result.add(handle.getOwner()); descriptor(result, handle.getDesc()); }
    private static void descriptor(Set<String> result, String descriptor) {
        if (descriptor.startsWith("(")) {
            for (Type argument : Type.getArgumentTypes(descriptor)) type(result, argument);
            type(result, Type.getReturnType(descriptor));
        } else type(result, Type.getType(descriptor));
    }
    private static void type(Set<String> result, Type type) {
        if (type.getSort() == Type.ARRAY) type(result, type.getElementType());
        else if (type.getSort() == Type.OBJECT) result.add(type.getInternalName());
    }
    private static void require(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
}
