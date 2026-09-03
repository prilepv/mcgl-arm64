import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public final class LightmapPatchTest implements Opcodes {
    static void check(boolean b, String why) { if(!b) throw new AssertionError(why); }
    static MethodNode method(Map<String,ClassNode> c,String owner,String name,String desc) {
        for(MethodNode m:c.get(owner+".class").methods) if(m.name.equals(name)&&m.desc.equals(desc)) return m;
        throw new AssertionError("missing method");
    }
    static void reject(Map<String,ClassNode> classes) {
        try { PatchMCGLLightmap.patch(classes,new HashSet<String>()); throw new AssertionError("unsafe bytecode accepted"); }
        catch(IllegalStateException expected) {}
    }
    public static void main(String[] args)throws Exception {
        String[][] guarded={
            {"net/A/U/oooO","<init>","(Lnet/minecraft/client/Minecraft;)V"},
            {"net/A/U/oooO","Ö00000","()V"},{"net/A/U/oooO","o00000","(D)V"},
            {"net/A/U/Oooo","o00000","([IIII)V"},{"net/A/U/Oooo","o00000","(Ljava/awt/image/BufferedImage;)I"},
            {"net/A/U/Oooo","Ò00000","(I)V"},{"net/A/for/oOO0","o00000","(Ljava/nio/IntBuffer;)V"}};
        for(String[] key:guarded) {
            Map<String,ClassNode> c=ChunkVboPatchTest.read(args[0]);
            method(c,key[0],key[1],key[2]).instructions.insert(new InsnNode(NOP)); reject(c);
        }
        Map<String,ClassNode> c=ChunkVboPatchTest.read(args[0]);
        MethodNode extra=new MethodNode(ACC_PUBLIC|ACC_STATIC,"testTextureState","()V",null,null);
        extra.instructions.add(new LdcInsnNode(3314)); extra.instructions.add(new InsnNode(ICONST_1));
        extra.instructions.add(new MethodInsnNode(INVOKESTATIC,"org/lwjgl/opengl/GL11","glPixelStorei","(II)V"));
        extra.instructions.add(new InsnNode(RETURN)); c.get("net/A/U/Oooo.class").methods.add(extra); reject(c);

        c=ChunkVboPatchTest.read(args[0]);
        extra=new MethodNode(ACC_PUBLIC|ACC_STATIC,"testTextureEscape","(Lnet/A/U/oooO;)I",null,null);
        extra.instructions.add(new VarInsnNode(ALOAD,0));
        extra.instructions.add(new FieldInsnNode(GETFIELD,"net/A/U/oooO","OÔ0000","I"));
        extra.instructions.add(new InsnNode(IRETURN)); c.get("net/A/U/Oooo.class").methods.add(extra); reject(c);

        c=ChunkVboPatchTest.read(args[0]);
        long before=PatchMCGLChunkVbo.fingerprint(method(c,"net/A/U/Oooo","o00000","([IIII)V"));
        PatchMCGLLightmap.patch(c,new HashSet<String>());
        check(before==PatchMCGLChunkVbo.fingerprint(method(c,"net/A/U/Oooo","o00000","([IIII)V")),"generic uploader changed");
        check(method(c,"net/A/U/Oooo","mcglUploadLightmap","([IIII)V")!=null,"specialized uploader");
        int redirected=0;
        for(ClassNode cls:c.values()) for(MethodNode m:cls.methods) for(AbstractInsnNode n:m.instructions.toArray())
            if(n instanceof MethodInsnNode && ((MethodInsnNode)n).name.equals("mcglUploadLightmap")) redirected++;
        check(redirected==1,"single dedicated caller");
        reject(c);
        System.out.println("LIGHTMAP_PATCH_PASS fingerprints=7 unsafePixelStoreRejected=true escapedTextureIdRejected=true genericUnchanged=true caller=1 doublePatchRejected=true");
    }
}

