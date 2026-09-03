package local.mcgl.perf;

import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Render-thread-only, opt-in mirror of validated terrain lists. Native lists
 * remain authoritative fallbacks; no changes to vertices, sorting or culling. */
public final class ChunkVbo {
    private static final boolean ENABLED = Boolean.getBoolean("mcgl.chunk.vbo");
    private static final boolean PROFILE = Boolean.getBoolean("mcgl.graphics.profile");
    private static final boolean VAO = !"false".equals(System.getProperty("mcgl.chunk.vao"));
    private static final boolean TEXTURE_BINDINGS = !"false".equals(System.getProperty("mcgl.chunk.textureBindings"));
    private static final long BUDGET = 256L * 1024 * 1024;
    private static ContextCapabilities context;
    private static final MeshIndex meshes = new MeshIndex();
    private static final Map<String, Integer> rejectionReasons = new TreeMap<String, Integer>();
    private static Mesh recording;
    private static boolean compiling, supported, vaoSupported;
    private static int textureUnits;
    private static long bytes, accepted, rejected, draws, nativeCalls, nextReport;
    private static long vaoDraws, nativeBatches;
    private static long textureCompiles, textureBinds;
    private static int liveVaos, reportChecks;
    private static ByteBuffer scratch;

    private static boolean ready() {
        if (!ENABLED) return false;
        ContextCapabilities current = GLContext.getCapabilities();
        if (current != context) {
            // Buffer names belong to their old context. Never delete those
            // names in a replacement context (they may already be reused).
            meshes.clear(); recording = null; compiling = false; bytes = 0; liveVaos = 0;
            context = current;
            supported = current.OpenGL15 && current.OpenGL13;
            vaoSupported = supported && VAO && current.GL_APPLE_vertex_array_object;
            if (supported) textureUnits = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
            System.out.println("[MCGL VBO] " + (supported ? "enabled; native fallback retained; buffer budget 256 MiB; VAO=" + vaoSupported + "; textureBindings=" + TEXTURE_BINDINGS : "unavailable; using native lists"));
        }
        return supported;
    }

    public static void unsupported() {
        unsupported("unknown/nested list");
    }
    public static void unsupported(String reason) {
        if (!ENABLED || recording == null) return;
        invalidate(recording, reason);
    }
    private static void invalidate(Mesh mesh, String reason) {
        if (mesh.valid) { mesh.valid = false; if (PROFILE) mesh.reason = reason; }
    }
    private static void rejected(Mesh mesh, String reason) {
        rejected++;
        if (PROFILE) {
            String key = mesh.reason == null ? reason : mesh.reason;
            Integer count = rejectionReasons.get(key);
            rejectionReasons.put(key, count == null ? 1 : count + 1);
        }
    }
    public static void glNewList(int id, int mode, boolean terrain) {
        boolean available = ready();
        if (available) {
            remove(id);
            recording = terrain && mode == GL11.GL_COMPILE && !compiling ? new Mesh(id) : null;
            if (recording != null && !cleanArrays()) invalidate(recording, "client arrays active before compilation");
            compiling = true;
        }
        GL11.glNewList(id, mode);
    }
    private static boolean cleanArrays() {
        // Lists bake enabled arrays at compile time. Do not guess about arrays
        // supplied by another renderer, or a nonstandard active texture unit.
        int active = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        boolean clean = active == GL13.GL_TEXTURE0 &&
                !GL11.glIsEnabled(GL11.GL_VERTEX_ARRAY) && !GL11.glIsEnabled(GL11.GL_NORMAL_ARRAY) &&
                !GL11.glIsEnabled(GL11.GL_COLOR_ARRAY) && !GL11.glIsEnabled(GL11.GL_INDEX_ARRAY) &&
                !GL11.glIsEnabled(GL11.GL_EDGE_FLAG_ARRAY) &&
                !GL11.glIsEnabled(GL14.GL_SECONDARY_COLOR_ARRAY) && !GL11.glIsEnabled(GL14.GL_FOG_COORDINATE_ARRAY);
        for (int unit = 0; unit < textureUnits; unit++) {
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0 + unit);
            clean &= !GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        GL13.glClientActiveTexture(active);
        return clean;
    }
    private static void prepareArrays() {
        GL11.glDisableClientState(GL11.GL_INDEX_ARRAY);
        GL11.glDisableClientState(GL11.GL_EDGE_FLAG_ARRAY);
        GL11.glDisableClientState(GL14.GL_SECONDARY_COLOR_ARRAY);
        GL11.glDisableClientState(GL14.GL_FOG_COORDINATE_ARRAY);
        for (int unit = 2; unit < textureUnits; unit++) {
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
    }
    public static void glEndList() {
        GL11.glEndList();
        if (!ENABLED || !supported) return;
        Mesh mesh = recording;
        recording = null; compiling = false;
        if (mesh == null) return;
        if (!mesh.valid || mesh.commands.isEmpty() || mesh.size == 0 || bytes + mesh.size > BUDGET) {
            rejected(mesh, bytes + mesh.size > BUDGET ? "VBO budget" : "empty or unsupported geometry"); return;
        }
        int binding = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean uploaded = false;
        try {
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                System.out.println("[MCGL VBO] pre-existing GL error " + error + "; native fallback");
                return;
            }
            for (Command cmd : mesh.commands) if (cmd.op == 6) {
                if (scratch == null || scratch.capacity() < cmd.data.length) scratch = BufferUtils.createByteBuffer(cmd.data.length);
                scratch.clear(); scratch.put(cmd.data); scratch.flip();
                cmd.buffer = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cmd.buffer);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, scratch, GL15.GL_STATIC_DRAW);
                error = GL11.glGetError();
                if (cmd.buffer == 0 || error != GL11.GL_NO_ERROR ||
                        GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_SIZE) != cmd.data.length) {
                    System.out.println("[MCGL VBO] buffer upload failed (GL " + error + "); native fallback");
                    return;
                }
                cmd.data = null;
                if (vaoSupported) createVertexArray(cmd);
            }
            meshes.put(mesh.id, mesh); bytes += mesh.size; accepted++; uploaded = true;
            if (mesh.textureBindings) textureCompiles++;
        } catch (RuntimeException error) {
            System.out.println("[MCGL VBO] upload exception " + error.getClass().getSimpleName() + "; native fallback");
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, binding);
            if (!uploaded) { dispose(mesh); rejected(mesh, "upload failed or pre-existing GL error"); }
        }
    }
    public static void glDeleteLists(int first, int count) {
        if (ready() && count > 0) {
            if (count <= 64) {
                // A single chunk deletion must not scan the entire world.
                for (int offset = 0; offset < count; offset++) remove(first + offset);
            } else for (Mesh head : meshes.buckets) {
                for (Mesh mesh = head; mesh != null;) {
                    Mesh next = mesh.next;
                    if (((mesh.id - first) & 0xffffffffL) < count) remove(mesh.id);
                    mesh = next;
                }
            }
        }
        GL11.glDeleteLists(first, count);
    }
    private static void remove(int id) {
        Mesh old = meshes.remove(id);
        if (old != null) { dispose(old); bytes -= old.size; }
    }
    private static void dispose(Mesh mesh) {
        for (Command cmd : mesh.commands) {
            if (cmd.vao != 0) { APPLEVertexArrayObject.glDeleteVertexArraysAPPLE(cmd.vao); cmd.vao = 0; liveVaos--; }
            if (cmd.buffer != 0) { GL15.glDeleteBuffers(cmd.buffer); cmd.buffer = 0; }
        }
    }
    private static void createVertexArray(Command cmd) {
        // APPLE VAO binding is restored by PopClientAttrib, together with the
        // native arrays and LWJGL's Java-side pointer/buffer references. Do not
        // use ARB/GL30 VAOs in this legacy 2.1 context, or change array state
        // outside this pair. DrawArrays itself needs no Java-side pointer lookup.
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        int vao = 0;
        try {
            vao = APPLEVertexArrayObject.glGenVertexArraysAPPLE();
            if (vao == 0) throw new IllegalStateException("no VAO name");
            APPLEVertexArrayObject.glBindVertexArrayAPPLE(vao);
            prepareArrays(); configureArrays(cmd);
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("VAO GL error " + error);
            cmd.vao = vao; liveVaos++;
        } catch (RuntimeException failure) {
            vaoSupported = false;
            System.out.println("[MCGL VBO] VAO setup failed; ordinary VBO retained: " + failure.getClass().getSimpleName());
        } finally {
            GL11.glPopClientAttrib();
            if (vao != 0 && cmd.vao == 0) APPLEVertexArrayObject.glDeleteVertexArraysAPPLE(vao);
        }
    }

    public static void glPushMatrix() { record(1, 0, 0, 0); GL11.glPushMatrix(); }
    public static void glPopMatrix() { record(2, 0, 0, 0); GL11.glPopMatrix(); }
    public static void glTranslatef(float x, float y, float z) { record(3, x, y, z); GL11.glTranslatef(x, y, z); }
    public static void glScalef(float x, float y, float z) { record(4, x, y, z); GL11.glScalef(x, y, z); }
    public static void glDepthMask(boolean value) { record(5, value ? 1 : 0, 0, 0); GL11.glDepthMask(value); }
    public static void glBindTexture(int target, int texture) {
        if (ENABLED && recording != null && recording.valid) {
            if (!TEXTURE_BINDINGS || target != GL11.GL_TEXTURE_2D) {
                unsupported("GL11.glBindTexture");
            } else {
                // Record the exact name, not its current image or binding unit.
                // GL_COMPILE defers this server-state change until list replay.
                // The current active texture unit at replay remains authoritative.
                Command cmd = new Command(7, 0, 0, 0);
                cmd.mode = target; cmd.count = texture;
                recording.commands.add(cmd); recording.textureBindings = true;
            }
        }
        GL11.glBindTexture(target, texture);
    }
    private static void record(int op, float x, float y, float z) {
        if (ENABLED && recording != null && recording.valid) recording.commands.add(new Command(op, x, y, z));
    }
    public static void glDrawArrays(int mode, int first, int count, ByteBuffer data,
                                    boolean texture, boolean light, boolean color, boolean normal, boolean legacyVbo) {
        if (ENABLED && recording != null && recording.valid) {
            long length = (long)count * 32;
            if (legacyVbo || first != 0 || count < 0 || length != data.limit() ||
                    length > 8 * 1024 * 1024 || recording.size + length + bytes > BUDGET) {
                invalidate(recording, legacyVbo ? "legacy tessellator VBO" :
                    (recording.size + length + bytes > BUDGET ? "VBO budget" : "tessellator layout/size"));
            } else if (count > 0) {
                Command cmd = new Command(6, 0, 0, 0);
                cmd.mode = mode; cmd.count = count;
                cmd.flags = (texture ? 1 : 0) | (light ? 2 : 0) | (color ? 4 : 0) | (normal ? 8 : 0);
                cmd.data = new byte[(int)length];
                ByteBuffer copy = data.duplicate(); copy.position(0); copy.get(cmd.data);
                recording.commands.add(cmd); recording.size += length;
            }
        }
        GL11.glDrawArrays(mode, first, count);
    }

    public static void glCallList(int id) {
        if (!ready() || compiling) { unsupported(); GL11.glCallList(id); return; }
        Mesh mesh = meshes.get(id);
        if (mesh == null) { nativeCalls++; GL11.glCallList(id); }
        else {
            int callerVao = currentVertexArray();
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            try { prepareArrays(); replay(mesh, callerVao); } finally { GL11.glPopClientAttrib(); }
        }
        report();
    }
    public static void glCallLists(IntBuffer ids) {
        if (!ready() || compiling || meshes.isEmpty()) { unsupported(); GL11.glCallLists(ids); return; }
        int base = GL11.glGetInteger(GL11.GL_LIST_BASE);
        boolean hasVbo = false;
        for (int i = ids.position(); i < ids.limit(); i++) if (meshes.get(ids.get(i) + base) != null) { hasVbo = true; break; }
        if (!hasVbo) {
            // Keep an all-native batch a single native call. In 1.5.0 it was
            // expanded into one JNI/driver call per list once any VBO existed.
            nativeCalls += ids.remaining(); nativeBatches++;
            GL11.glCallLists(ids); report(); return;
        }
        int callerVao = currentVertexArray();
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            prepareArrays();
            // Absolute reads preserve both caller position and draw order.
            for (int i = ids.position(); i < ids.limit(); i++) {
                int id = ids.get(i) + base;
                Mesh mesh = meshes.get(id);
                if (mesh == null) { nativeCalls++; GL11.glCallList(id); }
                else replay(mesh, callerVao);
            }
        } finally { GL11.glPopClientAttrib(); }
        report();
    }
    private static int currentVertexArray() {
        return liveVaos > 0 ? GL11.glGetInteger(APPLEVertexArrayObject.GL_VERTEX_ARRAY_BINDING_APPLE) : -1;
    }
    private static void replay(Mesh mesh, int callerVao) {
        for (int i = 0; i < mesh.commands.size(); i++) {
            Command c = mesh.commands.get(i);
            switch (c.op) {
                case 1: GL11.glPushMatrix(); break;
                case 2: GL11.glPopMatrix(); break;
                case 3: GL11.glTranslatef(c.x, c.y, c.z); break;
                case 4: GL11.glScalef(c.x, c.y, c.z); break;
                case 5: GL11.glDepthMask(c.x != 0); break;
                case 6: draw(c, callerVao); break;
                case 7: GL11.glBindTexture(c.mode, c.count); textureBinds++; break;
                default: throw new AssertionError("Unknown VBO command");
            }
        }
    }
    private static void state(int cap, boolean enable) {
        if (enable) GL11.glEnableClientState(cap); else GL11.glDisableClientState(cap);
    }
    private static void draw(Command c, int callerVao) {
        if (c.vao != 0) {
            APPLEVertexArrayObject.glBindVertexArrayAPPLE(c.vao);
            GL11.glDrawArrays(c.mode, 0, c.count); vaoDraws++; draws++;
            return;
        }
        // Do not overwrite the VAO of a preceding fast draw when a mesh in
        // the same batch has fallen back to ordinary VBO pointer setup.
        if (callerVao >= 0) APPLEVertexArrayObject.glBindVertexArrayAPPLE(callerVao);
        configureArrays(c);
        GL11.glDrawArrays(c.mode, 0, c.count); draws++;
    }
    private static void configureArrays(Command c) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, c.buffer);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 32, 0L);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        state(GL11.GL_TEXTURE_COORD_ARRAY, (c.flags & 1) != 0);
        if ((c.flags & 1) != 0) GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 32, 12L);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        state(GL11.GL_TEXTURE_COORD_ARRAY, (c.flags & 2) != 0);
        if ((c.flags & 2) != 0) GL11.glTexCoordPointer(2, GL11.GL_SHORT, 32, 28L);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        state(GL11.GL_COLOR_ARRAY, (c.flags & 4) != 0);
        if ((c.flags & 4) != 0) GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 32, 20L);
        state(GL11.GL_NORMAL_ARRAY, (c.flags & 8) != 0);
        if ((c.flags & 8) != 0) GL11.glNormalPointer(GL11.GL_BYTE, 32, 24L);
    }
    private static void report() {
        if (!PROFILE) return;
        // glCallList is also used for glyphs and occlusion boxes. Avoid reading
        // the clock hundreds of thousands of times/second just for diagnostics.
        if ((reportChecks++ & 1023) != 0) return;
        long now = System.nanoTime();
        if (now < nextReport) return;
        nextReport = now + 5000000000L;
        System.out.println("[MCGL VBO] lists=" + meshes.size() + " bytes=" + bytes + " compiled=" + accepted +
                " fallbackCompiles=" + rejected + " draws=" + draws + " nativeCalls=" + nativeCalls +
                " vaos=" + liveVaos + " vaoDraws=" + vaoDraws + " nativeBatches=" + nativeBatches +
                " textureCompiles=" + textureCompiles + " textureBinds=" + textureBinds);
        if (!rejectionReasons.isEmpty()) { System.out.println("[MCGL VBO fallback] " + rejectionReasons); rejectionReasons.clear(); }
        accepted = rejected = draws = nativeCalls = vaoDraws = nativeBatches = 0;
        textureCompiles = textureBinds = 0;
    }
    private static final class Mesh {
        final int id;
        final ArrayList<Command> commands = new ArrayList<Command>();
        boolean valid = true;
        boolean textureBindings;
        String reason;
        Mesh next;
        long size;
        Mesh(int id) { this.id = id; }
    }
    private static final class Command {
        final int op;
        final float x, y, z;
        // mode/count also carry the bind target/name; GLuint must not pass through float.
        int buffer, vao, mode, count, flags;
        byte[] data;
        Command(int op, float x, float y, float z) { this.op = op; this.x = x; this.y = y; this.z = z; }
    }
    /** Primitive-key lookup: no Integer allocation on the per-list hot path. */
    private static final class MeshIndex {
        MeshIndex() { }
        final Mesh[] buckets = new Mesh[4096];
        int count;
        private int bucket(int id) { return (id ^ (id >>> 16)) & (buckets.length - 1); }
        Mesh get(int id) {
            for (Mesh mesh = buckets[bucket(id)]; mesh != null; mesh = mesh.next) if (mesh.id == id) return mesh;
            return null;
        }
        void put(int id, Mesh mesh) {
            remove(id);
            int slot = bucket(id); mesh.next = buckets[slot]; buckets[slot] = mesh; count++;
        }
        Mesh remove(int id) {
            int slot = bucket(id); Mesh previous = null;
            for (Mesh mesh = buckets[slot]; mesh != null; mesh = mesh.next) {
                if (mesh.id == id) {
                    if (previous == null) buckets[slot] = mesh.next; else previous.next = mesh.next;
                    mesh.next = null; count--; return mesh;
                }
                previous = mesh;
            }
            return null;
        }
        void clear() { Arrays.fill(buckets, null); count = 0; }
        boolean isEmpty() { return count == 0; }
        int size() { return count; }
    }
}
