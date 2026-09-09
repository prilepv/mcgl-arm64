import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Independent structural equivalence audit of every original class and method in a real client JAR. */
public final class ChunkPatchTest implements Opcodes {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[1]); Files.createDirectories(root);
        Path input=Paths.get(args[0]), output=root.resolve("chunk-client.jar");
        PatchMCGLChunks.main(new String[]{input.toString(),output.toString()});
        String target=null; int changed=0;
        try(JarFile before=new JarFile(input.toFile());JarFile after=new JarFile(output.toFile())) {
            check(names(before).equals(names(after)),"identical archive entry set");
            for(String name:names(before)) {
                byte[] a=read(before,name),b=read(after,name);
                if(Arrays.equals(a,b)){checks++;continue;}
                changed++; target=name;
                check(name.matches("net/A/for/oOOOoO+\\.class"),"only the accumulator changes");
                check(Arrays.equals(normalize(a),normalize(b)),"ALL original methods/fields/access/control flow retained");
                ClassNode type=type(b); int sinkCalls=0, nativeCalls=0;
                for(MethodNode method:type.methods) if(method.name.startsWith("mcgl")&&!method.name.equals("mcglChunkLegacyDraw")) {
                    for(AbstractInsnNode n:method.instructions.toArray())if(n instanceof MethodInsnNode){
                        MethodInsnNode call=(MethodInsnNode)n;
                        if(call.owner.equals("local/mcgl/render/ChunkMeshBuilder$Sink")&&call.name.equals("append"))sinkCalls++;
                        if(call.owner.startsWith("org/lwjgl/")||call.owner.startsWith("local/mcgl/render/legacy/"))nativeCalls++;
                    }
                }
                check(sinkCalls==1&&nativeCalls==0,"CPU drain has one sink call and no native/legacy command");
            }
        }
        check(changed==1,"exactly one changed class");
        Path repeat=root.resolve("repeat.jar"); reject(output,repeat,"double adaptation rejected before writing output");
        byte[] existing=Files.readAllBytes(output); reject(input,output,"overwrite rejected");
        check(Arrays.equals(existing,Files.readAllBytes(output)),"existing output byte-identical after rejection");
        Path changedInput=root.resolve("changed-emitter.jar");
        try(JarFile original=new JarFile(input.toFile());JarOutputStream jar=new JarOutputStream(Files.newOutputStream(changedInput,StandardOpenOption.CREATE_NEW))){
            for(String name:names(original)){
                byte[] bytes=read(original,name);
                if(name.equals(target)){
                    ClassNode type=type(bytes);
                    for(MethodNode method:type.methods)if(method.name.equals("new")&&method.desc.equals("(DDD)V"))method.instructions.insert(new InsnNode(NOP));
                    ClassWriter writer=new ClassWriter(0);type.accept(writer);bytes=writer.toByteArray();
                }
                jar.putNextEntry(new JarEntry(name));jar.write(bytes);jar.closeEntry();
            }
        }
        reject(changedInput,root.resolve("unknown-output.jar"),"changed vertex writer fingerprint rejected");
        System.out.println("CHUNK_PATCH_EQUIVALENCE_PASS checks="+checks+" changed=1 legacy-draw/emitter/game-classes/resources=preserved strict-rejection");
    }
    private static byte[] normalize(byte[] bytes){
        ClassNode node=type(bytes);node.interfaces.remove("local/mcgl/render/ChunkTessellator");
        node.fields.removeIf(f->f.name.equals("mcglChunkSink"));
        boolean adapted=false;for(MethodNode m:node.methods)if(m.name.equals("mcglChunkLegacyDraw"))adapted=true;
        if(adapted){
            node.methods.removeIf(m->(m.name.equals("new")&&m.desc.equals("()I"))||m.name.equals("mcglChunkDrain")||m.name.equals("mcglBindChunkBatch")||m.name.equals("mcglAbortChunkBatch"));
            for(MethodNode m:node.methods)if(m.name.equals("mcglChunkLegacyDraw")){m.name="new";m.access=ACC_PUBLIC;}
        }
        ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
    }
    private static void reject(Path input,Path output,String message)throws Exception{
        boolean exists=Files.exists(output);
        try{PatchMCGLChunks.main(new String[]{input.toString(),output.toString()});throw new AssertionError(message);}
        catch(IOException expected){check(Files.exists(output)==exists,message);}
    }
    private static Set<String> names(JarFile jar){Set<String> names=new TreeSet<String>();for(Enumeration<JarEntry> entries=jar.entries();entries.hasMoreElements();){JarEntry e=entries.nextElement();if(!e.isDirectory())names.add(e.getName());}return names;}
    private static byte[] read(JarFile jar,String name)throws IOException{try(InputStream in=jar.getInputStream(jar.getJarEntry(name));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private static ClassNode type(byte[] b){ClassNode node=new ClassNode();new ClassReader(b).accept(node,0);return node;}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
