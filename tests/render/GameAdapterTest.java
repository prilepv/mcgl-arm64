import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Original-client integration, control-flow and fail-closed patch transaction checks. No GPU/account. */
public final class GameAdapterTest implements Opcodes {
    private static int checks,verified,routed;
    private static final Set<String> REMOVED=new HashSet<String>(Arrays.asList("glColorPointer","glNormalPointer","glVertexPointer","glTexCoordPointer",
            "glEnableClientState","glDisableClientState","glPushClientAttrib","glPopClientAttrib","glDrawArrays"));
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("post-chunk-client.jar game-client.jar manifest output-directory");
        Path root=Paths.get(args[3]);Files.createDirectories(root);String tess=null,world=null;
        try(JarFile before=new JarFile(args[0]);JarFile after=new JarFile(args[1])) {
            check(after.getEntry("META-INF/mcgl/game-core-v1")!=null,"game transaction marker");
            for(Enumeration<JarEntry> entries=after.entries();entries.hasMoreElements();) {
                JarEntry entry=entries.nextElement();if(!entry.getName().endsWith(".class"))continue;
                byte[] bytes=read(after,entry.getName());ClassNode type=type(bytes);
                if(type.interfaces.contains("local/mcgl/render/ChunkTessellator"))tess=type.name;
                if(type.name.matches("net/A/U/Ooo0O{100,}"))world=type.name;
                boolean changed=!Arrays.equals(bytes,read(before,entry.getName()));
                if(type.name.startsWith("local/mcgl/perf/ChunkVbo")){check(!changed,"retained opt-in legacy implementation untouched");continue;}
                for(MethodNode m:type.methods) {
                    if(changed&&(m.access&(ACC_ABSTRACT|ACC_NATIVE))==0) {
                        try{new Analyzer(new BasicVerifier()).analyze(type.name,m);verified++;}
                        catch(AnalyzerException failure){throw new AssertionError("Invalid migrated control flow: "+type.name+"."+m.name+m.desc,failure);}
                    }
                    if(m.name.equals("mcglChunkLegacyDraw"))continue;
                    for(AbstractInsnNode i:m.instructions.toArray()) {
                        if(i instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)i;audit(call.owner,call.name);}
                        else if(i instanceof LdcInsnNode&&((LdcInsnNode)i).cst instanceof MethodHandle)handle((MethodHandle)((LdcInsnNode)i).cst);
                        else if(i instanceof InvokeDynamicInsnNode){InvokeDynamicInsnNode call=(InvokeDynamicInsnNode)i;handle(call.bsm);for(Object a:call.bsmArgs)if(a instanceof MethodHandle)handle((MethodHandle)a);}
                    }
                }
            }
            check(tess!=null&&world!=null,"original accumulator and world located");
            ClassNode chunk=type(read(after,"net/A/U/H.class")),accumulator=type(read(after,tess+".class")),scheduler=type(read(after,world+".class"));
            check(chunk.interfaces.contains("local/mcgl/render/GameChunkHandle"),"original chunk implements typed handle");
            MethodNode rebuild=method(chunk,"Ö00000","()V"),body=method(chunk,"mcglRebuildGameBody","()V");
            check(calls(rebuild,"beginChunk")==1&&calls(rebuild,"finishChunk")==1&&calls(rebuild,"abortChunk")==1&&rebuild.tryCatchBlocks.size()==1,"transactional original chunk wrapper");
            check(calls(body,"Õ00000")==0,"no old transparent vertex snapshot");
            for(AbstractInsnNode i:body.instructions.toArray())if(i instanceof MethodInsnNode){MethodInsnNode c=(MethodInsnNode)i;check(!(c.owner.equals(tess)&&c.name.equals("Ó00000")&&c.desc.equals("(DDD)V")),"no CPU terrain vertex resort");}
            check(opcodes(method(chunk,"Õ00000","()V")).equals(Collections.singletonList(RETURN)),"obsolete resort entry is inert");
            check(calls(method(accumulator,"new","()I"),"mcglChunkLegacyDraw")==0&&calls(method(accumulator,"new","()I"),"raw")==1,"original draw connected by default");
            check(calls(method(scheduler,"o00000","(ID)V"),"drawChunks")==1,"original draw connected to chunk registry");
            // Visibility/occlusion/counter loops remain byte-for-byte equivalent through their boundary.
            MethodNode oldFilter=method(type(read(before,world+".class")),"o00000","(IIID)I"),newFilter=method(scheduler,"o00000","(IIID)I");
            List<String> oldPrefix=prefix(oldFilter),newPrefix=prefix(newFilter);
            check(oldPrefix.size()>100&&newPrefix.size()>=oldPrefix.size()&&oldPrefix.equals(newPrefix.subList(0,oldPrefix.size())),"original visibility and occlusion selection retained");
            check(calls(method(accumulator,"mcglAbortGameBatch","()V"),"Ó00000")==1,"failed rebuild resets accumulator");
        }
        Path existing=root.resolve("existing.jar");byte[] sentinel={3,1,4};Files.write(existing,sentinel,StandardOpenOption.CREATE_NEW);
        fails(args[0],existing,args[2]);check(Arrays.equals(Files.readAllBytes(existing),sentinel),"existing output preserved");
        Path twice=root.resolve("twice.jar");fails(args[1],twice,args[2]);check(!Files.exists(twice),"double-patch does not publish output");
        Path modified=root.resolve("changed-chunk.jar");
        try(JarFile input=new JarFile(args[0]);JarOutputStream output=new JarOutputStream(Files.newOutputStream(modified,StandardOpenOption.CREATE_NEW))) {
            for(Enumeration<JarEntry> entries=input.entries();entries.hasMoreElements();) {
                JarEntry e=entries.nextElement();if(e.isDirectory())continue;byte[] b=read(input,e.getName());
                if(e.getName().equals("net/A/U/H.class")){ClassNode n=type(b);method(n,"Ö00000","()V").instructions.insert(new InsnNode(NOP));ClassWriter w=new ClassWriter(0);n.accept(w);b=w.toByteArray();}
                output.putNextEntry(new JarEntry(e.getName()));output.write(b);output.closeEntry();
            }
        }
        Path rejected=root.resolve("rejected.jar");fails(modified.toString(),rejected,args[2]);check(!Files.exists(rejected),"changed original fingerprint fails transaction");
        check(verified>1000&&routed>1000,"whole migrated class inventory checked");
        System.out.println("GAME_ADAPTER_TEST_PASS checks="+checks+" verifiedMethods="+verified+" routedCalls="+routed);
    }
    private static void audit(String owner,String name) {
        check(!owner.startsWith("local/mcgl/render/legacy/")&&!owner.equals("local/mcgl/perf/ChunkVbo"),"live game legacy call: "+owner+"."+name);
        check(!(owner.startsWith("org/lwjgl/opengl/")&&name.startsWith("gl")),"live native bypass: "+owner+"."+name);
        if(owner.startsWith("local/mcgl/render/game/")){check(!owner.endsWith("/GL20")&&!owner.endsWith("/ARBShaderObjects")&&!REMOVED.contains(name),"unmigrated live array/shader call: "+owner+"."+name);routed++;}
    }
    private static void handle(MethodHandle h){audit(h.getOwner(),h.getName());}
    private static List<String> prefix(MethodNode m){List<String> r=new ArrayList<String>();for(AbstractInsnNode i:m.instructions.toArray()){if(i instanceof FieldInsnNode&&((FieldInsnNode)i).name.equals("õO0000"))break;if(i.getOpcode()<0)continue;String s=""+i.getOpcode();if(i instanceof FieldInsnNode){FieldInsnNode f=(FieldInsnNode)i;s+=f.owner+f.name+f.desc;}else if(i instanceof MethodInsnNode){MethodInsnNode c=(MethodInsnNode)i;s+=c.owner+c.name+c.desc;}else if(i instanceof VarInsnNode)s+=":"+((VarInsnNode)i).var;else if(i instanceof IincInsnNode)s+=":"+((IincInsnNode)i).var+":"+((IincInsnNode)i).incr;r.add(s);}return r;}
    private static List<Integer> opcodes(MethodNode m){List<Integer> r=new ArrayList<Integer>();for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()>=0)r.add(i.getOpcode());return r;}
    private static int calls(MethodNode m,String name){int n=0;for(AbstractInsnNode i:m.instructions.toArray())if(i instanceof MethodInsnNode&&((MethodInsnNode)i).name.equals(name))n++;return n;}
    private static void fails(String input,Path output,String manifest)throws Exception{boolean failed=false;try{PatchMCGLGame.main(new String[]{input,output.toString(),manifest});}catch(IOException expected){failed=true;}check(failed,"invalid patch input rejected");}
    private static MethodNode method(ClassNode n,String name,String desc){for(MethodNode m:n.methods)if(m.name.equals(name)&&m.desc.equals(desc))return m;throw new AssertionError("Missing method "+name+desc);}
    private static ClassNode type(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    private static byte[] read(JarFile jar,String name)throws IOException{try(InputStream in=jar.getInputStream(jar.getJarEntry(name));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
