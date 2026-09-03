import java.lang.reflect.*;
import java.util.*;
public final class MeshIndexTest {
    static Method method(Class<?> c, String name, Class<?>... args) throws Exception {
        Method m = c.getDeclaredMethod(name, args); m.setAccessible(true); return m;
    }
    public static void main(String[] args) throws Exception {
        Class<?> indexType = Class.forName("local.mcgl.perf.ChunkVbo$MeshIndex");
        Class<?> meshType = Class.forName("local.mcgl.perf.ChunkVbo$Mesh");
        Constructor<?> ic = indexType.getDeclaredConstructor(), mc = meshType.getDeclaredConstructor(int.class);
        ic.setAccessible(true); mc.setAccessible(true); Object index = ic.newInstance();
        Method get = method(indexType, "get", int.class), put = method(indexType, "put", int.class, meshType);
        Method remove = method(indexType, "remove", int.class), clear = method(indexType, "clear"), size = method(indexType, "size");
        Map<Integer,Object> reference = new HashMap<Integer,Object>(); Random random = new Random(627819);
        for (int i = 0; i < 100000; i++) {
            int id = i % 3 == 0 ? random.nextInt() : random.nextInt(512) * 4096;
            int op = random.nextInt(3);
            if (op == 0) { Object mesh = mc.newInstance(id); put.invoke(index, id, mesh); reference.put(id, mesh); }
            if (op == 1 && remove.invoke(index, id) != reference.remove(id)) throw new AssertionError("remove");
            if (get.invoke(index, id) != reference.get(id) || ((Number)size.invoke(index)).intValue() != reference.size()) throw new AssertionError("lookup/size");
            if (i % 10000 == 9999) {
                for (int key : reference.keySet()) if (get.invoke(index, key) != reference.get(key)) throw new AssertionError("collision chain");
                clear.invoke(index); reference.clear();
            }
        }
        System.out.println("MESH_INDEX_PASS 100000 operations, collisions, replacement, signed keys, clear");
    }
}
