import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Independent equivalence audit for the original-cache control, including both alphaSort branches. */
public final class OriginalChunkAdapterTest implements Opcodes {
    private static int checks,verified;
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("post-chunk-client original-core-client manifest negative-directory");
        SortedMap<String,RenderCommandSpec> manifest=RenderCommandSpec.read(Paths.get(args[2]));
        try(JarFile before=new JarFile(args[0]);JarFile after=new JarFile(args[1])) {
            check(new String(read(after,"META-INF/mcgl/chunk-policy"),StandardCharsets.UTF_8).equals("original-cache\n"),"explicit installed policy marker");
            String world=null,tess=null;
            for(Enumeration<JarEntry> es=after.entries();es.hasMoreElements();) {
                String name=es.nextElement().getName();if(!name.endsWith(".class"))continue;
                ClassNode n=type(read(after,name));
                if(n.name.matches("net/A/U/Ooo0O{100,}"))world=n.name;
                if(n.interfaces.contains("local/mcgl/render/ChunkTessellator"))tess=n.name;
                if(n.name.startsWith("local/mcgl/perf/ChunkVbo"))continue;
                boolean changed=!Arrays.equals(read(before,name),read(after,name));
                for(MethodNode m:n.methods) {
                    if(changed&&(m.access&(ACC_NATIVE|ACC_ABSTRACT))==0){new Analyzer(new BasicVerifier()).analyze(n.name,m);verified++;}
                    if(m.name.equals("mcglChunkLegacyDraw"))continue;
                    for(AbstractInsnNode i:m.instructions.toArray())if(i instanceof MethodInsnNode){MethodInsnNode c=(MethodInsnNode)i;
                        check(!c.owner.startsWith("local/mcgl/render/legacy/")&&!c.owner.equals("local/mcgl/perf/ChunkVbo"),"no live native legacy helper");
                        check(!(c.owner.startsWith("org/lwjgl/opengl/")&&c.name.startsWith("gl")),"no native game graphics bypass");
                    }
                }
            }
            check(world!=null&&tess!=null,"original client boundary located");
            ClassNode oldChunk=type(read(before,"net/A/U/H.class")),chunk=type(read(after,"net/A/U/H.class"));
            for(MethodNode old:oldChunk.methods){String name=old.name;
                if(name.equals("Ö00000")&&old.desc.equals("()V"))name="mcglRebuildOriginalBody";
                if(name.equals("Õ00000")&&old.desc.equals("()V"))name="mcglResortOriginalBody";
                equivalent(old,method(chunk,name,old.desc),manifest,"chunk "+old.name+old.desc);
            }
            for(String className:new String[]{world,"net/A/U/thisclass","net/A/U/o0oOo"}) {
                ClassNode old=type(read(before,className+".class")),next=type(read(after,className+".class"));
                check(old.methods.size()+(className.equals(world)?1:0)==next.methods.size(),"only world submission scope added; no replaced scheduler/region/comparator methods");
                for(MethodNode m:old.methods){
                    String name=className.equals(world)&&m.name.equals("o00000")&&m.desc.equals("(ID)V")?"mcglDrawOriginalTerrainBody":m.name;
                    equivalent(m,method(next,name,m.desc),manifest,className+" "+m.name+m.desc);
                }
            }
            MethodNode rebuild=method(chunk,"Ö00000","()V"),resort=method(chunk,"Õ00000","()V");
            for(MethodNode m:new MethodNode[]{rebuild,resort}) {
                check(calls(m,"beginOriginalChunk")==1&&calls(m,"finishOriginalChunk")==1&&calls(m,"abortOriginalChunk")==1&&m.tryCatchBlocks.size()==1,"atomic Core-cache publication wrapper");
                check(calls(m,"mcglAbortGameBatch")==1,"failed original build resets accumulator");
            }
            check(calls(method(chunk,"mcglRebuildOriginalBody","()V"),"Õ00000")==1,"original CPU transparency snapshot retained");
            check(calls(method(chunk,"mcglResortOriginalBody","()V"),"Ó00000")==1,"actual original per-chunk sorter retained in resort body");
            ClassNode nextWorld=type(read(after,world+".class"));
            MethodNode worldBody=method(nextWorld,"mcglDrawOriginalTerrainBody","(ID)V"),worldWrapper=method(nextWorld,"o00000","(ID)V");
            check(calls(worldBody,"Õ00000")==1,"original one-chunk resort queue retained");
            check(calls(worldBody,"drawChunks")==0,"global face pipeline is not used by control world");
            check(calls(worldWrapper,"beginOriginalTerrain")==1&&calls(worldWrapper,"endOriginalTerrain")==2&&calls(worldWrapper,"mcglDrawOriginalTerrainBody")==1&&worldWrapper.tryCatchBlocks.size()==1,"balanced original terrain wrapper with exceptional cleanup");
            check(calls(method(type(read(after,tess+".class")),"new","()I"),"raw")==1,"same indexed Core accumulator drain");
        }
        Path root=Paths.get(args[3]);Files.createDirectories(root);
        fails(args[1],root.resolve("repeat.jar"),args[2],"--original-chunks");
        fails(args[0],root.resolve("unknown.jar"),args[2],"--unknown-policy");
        check(verified>1000,"whole changed client control flow verified");
        System.out.println("ORIGINAL_CHUNK_ADAPTER_PASS checks="+checks+" verifiedMethods="+verified+" original-algorithms/Core-only/transactional-publication");
    }
    private static void equivalent(MethodNode old,MethodNode actual,Map<String,RenderCommandSpec> manifest,String label)throws Exception {
        MethodNode expected=copy(old);
        for(AbstractInsnNode i:expected.instructions.toArray())if(i instanceof MethodInsnNode) {
            MethodInsnNode c=(MethodInsnNode)i;
            if(c.owner.equals("local/mcgl/perf/ChunkVbo")&&c.name.equals("unsupported")){expected.instructions.set(c,new InsnNode(POP));continue;}
            if(c.owner.equals("local/mcgl/perf/ChunkVbo")&&c.name.equals("glNewList")&&c.desc.equals("(IIZ)V")){expected.instructions.insertBefore(c,new InsnNode(POP));c.desc="(II)V";}
            String owner=c.owner.startsWith("local/mcgl/render/legacy/")?"org/lwjgl/opengl/"+c.owner.substring("local/mcgl/render/legacy/".length())
                    :c.owner.equals("local/mcgl/perf/ChunkVbo")&&c.name.startsWith("gl")?"org/lwjgl/opengl/GL11":c.owner;
            if(owner.startsWith("org/lwjgl/opengl/")&&c.name.startsWith("gl")){
                RenderCommandSpec spec=manifest.get(RenderCommandSpec.key(owner,c.name,c.desc));check(spec!=null,"known original graphics signature");c.owner="local/mcgl/render/game/"+spec.family;
            }
        }
        check(Arrays.equals(normalized(expected),normalized(actual)),"exact original control flow retained: "+label);
    }
    private static byte[] normalized(MethodNode source) {
        MethodNode copy=copy(source);copy.name="checked";copy.access=ACC_PUBLIC;copy.maxStack=copy.maxLocals=0;
        ClassWriter writer=new ClassWriter(0);writer.visit(50,ACC_PUBLIC,"Checked",null,"java/lang/Object",null);copy.accept(writer);writer.visitEnd();return writer.toByteArray();
    }
    private static MethodNode copy(MethodNode source){MethodNode result=new MethodNode(source.access,source.name,source.desc,source.signature,source.exceptions==null?null:(String[])source.exceptions.toArray(new String[0]));source.accept(result);return result;}
    private static int calls(MethodNode m,String name){int count=0;for(AbstractInsnNode i:m.instructions.toArray())if(i instanceof MethodInsnNode&&((MethodInsnNode)i).name.equals(name))count++;return count;}
    private static void fails(String input,Path output,String manifest,String option)throws Exception{boolean failed=false;try{PatchMCGLGame.main(new String[]{input,output.toString(),manifest,option});}catch(IOException|IllegalArgumentException expected){failed=true;}check(failed&&!Files.exists(output),"invalid control input leaves no output");}
    private static ClassNode type(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    private static MethodNode method(ClassNode n,String name,String desc){for(MethodNode m:n.methods)if(m.name.equals(name)&&m.desc.equals(desc))return m;throw new AssertionError("Missing method "+name+desc);}
    private static byte[] read(JarFile jar,String name)throws IOException{JarEntry entry=jar.getJarEntry(name);if(entry==null)throw new IOException("Missing "+name);try(InputStream in=jar.getInputStream(entry);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
