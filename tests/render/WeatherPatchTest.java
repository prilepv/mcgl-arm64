import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import local.mcgl.render.*;
import local.mcgl.render.tests.OriginalChunkEmitter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Executes the real client's weather initializer and accumulator without a world, account or GPU. */
public final class WeatherPatchTest implements Opcodes {
    private static final String WEATHER="net/A/U/oooO", MATH="net/A/for/VB";
    private static final String POLICY="META-INF/mcgl/weather-policy";
    private static int checks;
    public static void main(String[] args)throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("post-chunk-client released-core-client fixed-core-client manifest negative-directory");
        float[][] oldDirections,newDirections;
        try(JarFile old=new JarFile(args[1]);JarFile fixed=new JarFile(args[2])) {
            check(old.getEntry(POLICY)==null,"baseline predates the weather fix");
            check(new String(read(fixed,POLICY),StandardCharsets.UTF_8).equals("finite-center-v1\n"),"installed weather policy");
            for(Enumeration<JarEntry> entries=old.entries();entries.hasMoreElements();) {
                String name=entries.nextElement().getName();
                if(name.endsWith("/")||name.equals(WEATHER+".class"))continue;
                check(Arrays.equals(read(old,name),read(fixed,name)),"unrelated client entry unchanged: "+name);
            }
            for(Enumeration<JarEntry> entries=fixed.entries();entries.hasMoreElements();) {
                String name=entries.nextElement().getName();
                check(name.endsWith("/")||name.equals(POLICY)||old.getEntry(name)!=null,"no unrelated new entry");
            }
            ClassNode previous=type(read(old,WEATHER+".class")),next=type(read(fixed,WEATHER+".class"));
            check(previous.methods.size()==next.methods.size(),"weather class method inventory unchanged");
            for(MethodNode before:previous.methods) {
                MethodNode after=method(next,before.name,before.desc);
                if((after.access&(ACC_ABSTRACT|ACC_NATIVE))==0)new Analyzer(new BasicVerifier()).analyze(next.name,after);
                MethodNode restored=copy(after);
                if(before.name.equals("void")&&before.desc.equals("(F)V"))removeGuard(restored);
                check(Arrays.equals(normalized(before),normalized(restored)),"only the reviewed length guard changes: "+before.name+before.desc);
            }
            oldDirections=directions(previous,type(read(old,MATH+".class")));
            newDirections=directions(next,type(read(fixed,MATH+".class")));
        }
        int center=16*32+16;
        for(int axis=0;axis<2;axis++) {
            check(oldDirections[axis].length==1024&&newDirections[axis].length==1024,"original weather table size");
            check(Float.isNaN(oldDirections[axis][center]),"real original initializer reproduces center NaN");
            check(Float.isFinite(newDirections[axis][center])&&newDirections[axis][center]==0,"fixed center is finite and zero width: axis="+axis+" value="+newDirections[axis][center]);
            for(int i=0;i<1024;i++)if(i!=center) {
                check(Float.isFinite(oldDirections[axis][i]),"original neighbor finite");
                check(Float.floatToRawIntBits(oldDirections[axis][i])==Float.floatToRawIntBits(newDirections[axis][i]),"every noncenter direction remains bit-identical");
            }
        }
        File client=new File(args[2]);int cases=0;
        for(boolean snow:new boolean[]{false,true})for(int radius:new int[]{5,10})for(int x:new int[]{0,-137,300000}) {
            int z=-x+23,diameter=radius*2+1,vertices=diameter*diameter*4;
            int[] bad=emit(client,oldDirections,snow,radius,x,z),good=emit(client,newDirections,snow,radius,x,z);
            boolean rejected=false;try{geometry(bad,vertices);}catch(IllegalArgumentException expected){rejected=expected.getMessage().equals("Non-finite game vertex");}
            check(rejected,"original rain/snow reproduces the reported Core failure");
            GameGeometry mesh=geometry(good,vertices);
            check(mesh.mesh.vertexCount()==vertices&&mesh.mesh.indices().count()==vertices/4*6,"fixed rain/snow keeps complete indexed quads");
            int centerStart=(radius*diameter+radius)*32;
            for(int word=0;word<good.length;word++)if(word<centerStart||word>=centerStart+32)
                check(bad[word]==good[word],"original emitter preserves every noncenter vertex word");
            for(int corner=1;corner<4;corner++)for(int axis:new int[]{0,2})
                check(good[centerStart+axis]==good[centerStart+corner*8+axis],"center remains a zero-width degenerate quad");
            int[] invalid=good.clone();invalid[0]=Float.floatToRawIntBits(Float.NaN);reject(invalid,vertices);
            invalid=good.clone();invalid[3]=Float.floatToRawIntBits(Float.POSITIVE_INFINITY);reject(invalid,vertices);
            cases++;
        }
        Path negatives=Paths.get(args[4]);Files.createDirectories(negatives);
        Path changed=negatives.resolve("changed-weather.jar");
        try(JarFile original=new JarFile(args[0]);JarOutputStream out=new JarOutputStream(Files.newOutputStream(changed,StandardOpenOption.CREATE_NEW))) {
            for(Enumeration<JarEntry> entries=original.entries();entries.hasMoreElements();) {
                String name=entries.nextElement().getName();if(name.endsWith("/"))continue;byte[] data=read(original,name);
                if(name.equals(WEATHER+".class")) {
                    ClassNode node=type(data);method(node,"void","(F)V").instructions.insert(new InsnNode(NOP));
                    ClassWriter writer=new ClassWriter(0);node.accept(writer);data=writer.toByteArray();
                }
                out.putNextEntry(new JarEntry(name));out.write(data);out.closeEntry();
            }
        }
        Path rejected=negatives.resolve("rejected.jar");boolean failed=false;
        try{PatchMCGLGame.main(new String[]{changed.toString(),rejected.toString(),args[3],"--original-chunks"});}
        catch(IOException expected){failed=expected.getMessage().equals("Changed original rain/snow renderer");}
        check(failed&&!Files.exists(rejected),"unknown upstream weather algorithm fails without publishing an output");
        System.out.println("WEATHER_PATCH_PASS checks="+checks+" cases="+cases+" original-NaN-reproduced/all-1023-neighbors-bit-identical/finite-rain-and-snow/Core-guards-retained");
    }
    private static GameGeometry geometry(int[] words,int vertices){return GameGeometry.raw(words,vertices*8,vertices,7,false,true,true,true,true,false);}
    private static void reject(int[] words,int vertices){boolean rejected=false;try{geometry(words,vertices);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Core still rejects invalid positions/UVs");}
    private static int[] emit(File jar,float[][] directions,boolean snow,int radius,int cameraX,int cameraZ)throws Exception {
        int diameter=radius*2+1,words=diameter*diameter*32;
        OriginalChunkEmitter tess=new OriginalChunkEmitter(jar,words+128);
        tess.begin();tess.color(255,255,255,180);tess.lightmap(0x00f000f0);tess.translate(-cameraX-.25,-67.5,-cameraZ-.75);
        for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++) {
            int index=(z+16)*32+x+16;float dx=directions[0][index]*.5f,dz=directions[1][index]*.5f;
            // Original rain and snow emit the same vertical quad with different finite UV motion.
            double left=cameraX+x-dx+.5,right=cameraX+x+dx+.5;
            double front=cameraZ+z-dz+.5,back=cameraZ+z+dz+.5;
            double u=snow?.375:0,v=snow?-.625:.25;
            tess.uv(u,18+v);tess.vertex(left,72,front);
            tess.uv(u+1,18+v);tess.vertex(right,72,back);
            tess.uv(u+1,15+v);tess.vertex(right,60,back);
            tess.uv(u,15+v);tess.vertex(left,60,front);
        }
        return Arrays.copyOf(tess.raw(),words);
    }
    /** Execute the original initializer bytecode, not a handwritten replacement for its formula. */
    private static float[][] directions(ClassNode source,ClassNode math)throws Exception {
        MethodNode draw=method(source,"void","(F)V");AbstractInsnNode start=null;LabelNode end=null;
        for(AbstractInsnNode i:draw.instructions.toArray())if(i instanceof FieldInsnNode) {
            FieldInsnNode f=(FieldInsnNode)i;
            if(f.getOpcode()==GETFIELD&&f.name.equals("Objectsuper")&&i.getNext().getOpcode()==IFNONNULL) {
                start=i.getPrevious();end=((JumpInsnNode)i.getNext()).label;break;
            }
        }
        check(start!=null&&end!=null,"real weather initializer boundary located");
        String name="WeatherDirectionFixture";ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_7,ACC_PUBLIC,name,null,"java/lang/Object",null);
        for(String field:new String[]{"Objectsuper","ÖÖ0000"})writer.visitField(ACC_PUBLIC,field,"[F",null,null).visitEnd();
        MethodVisitor constructor=writer.visitMethod(ACC_PUBLIC,"<init>","()V",null,null);
        constructor.visitCode();constructor.visitVarInsn(ALOAD,0);constructor.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V");constructor.visitInsn(RETURN);constructor.visitMaxs(0,0);constructor.visitEnd();
        MethodNode initialize=new MethodNode(ACC_PUBLIC,"initialize","()V",null,null);
        Map<LabelNode,LabelNode> labels=new IdentityHashMap<LabelNode,LabelNode>();
        for(AbstractInsnNode i=start;;i=i.getNext()){if(i instanceof LabelNode)labels.put((LabelNode)i,new LabelNode());if(i==end)break;}
        for(AbstractInsnNode i=start;;i=i.getNext()) {
            if(!(i instanceof FrameNode)&&!(i instanceof LineNumberNode)) {
                AbstractInsnNode copy=i.clone(labels);
                if(copy instanceof FieldInsnNode){FieldInsnNode f=(FieldInsnNode)copy;check(f.owner.equals(source.name)&&f.desc.equals("[F"),"initializer touches only direction arrays");f.owner=name;}
                if(copy instanceof MethodInsnNode){MethodInsnNode m=(MethodInsnNode)copy;if(m.owner.equals(MATH)){m.owner=name;m.name="weatherLength";}else check(m.owner.equals("java/lang/Math")&&m.name.equals("max"),"no external weather initialization work");}
                initialize.instructions.add(copy);
            }
            if(i==end)break;
        }
        initialize.instructions.add(new InsnNode(RETURN));initialize.accept(writer);
        MethodNode sqrt=copy(method(math,"Ô00000","(F)F"));sqrt.access=ACC_PUBLIC|ACC_STATIC;sqrt.name="weatherLength";sqrt.accept(writer);writer.visitEnd();
        Class<?> type=new ClassLoader(WeatherPatchTest.class.getClassLoader()){Class<?> load(byte[] bytes){return defineClass(name,bytes,0,bytes.length);}}.load(writer.toByteArray());
        Object fixture=type.getDeclaredConstructor().newInstance();type.getMethod("initialize").invoke(fixture);
        float[] x=(float[])type.getField("Objectsuper").get(fixture),z=(float[])type.getField("ÖÖ0000").get(fixture);
        type.getMethod("initialize").invoke(fixture);
        check(type.getField("Objectsuper").get(fixture)==x&&type.getField("ÖÖ0000").get(fixture)==z,"existing direction arrays are reused");
        return new float[][]{x,z};
    }
    private static void removeGuard(MethodNode method) {
        int guards=0;
        for(AbstractInsnNode i:method.instructions.toArray())if(i instanceof MethodInsnNode) {
            MethodInsnNode call=(MethodInsnNode)i;
            if(call.owner.equals("java/lang/Math")&&call.name.equals("max")&&call.desc.equals("(FF)F")) {
                check(i.getPrevious().getOpcode()==FCONST_1,"guard lower bound is exactly one");
                AbstractInsnNode length=i.getPrevious().getPrevious();
                check(length instanceof MethodInsnNode&&((MethodInsnNode)length).owner.equals(MATH)&&((MethodInsnNode)length).name.equals("Ô00000"),"guard applies only to weather direction length");
                method.instructions.remove(i.getPrevious());method.instructions.remove(i);guards++;
            }
        }
        check(guards==1,"exactly one finite-center guard");
    }
    private static byte[] normalized(MethodNode source){MethodNode m=copy(source);m.maxStack=m.maxLocals=0;ClassWriter w=new ClassWriter(0);w.visit(50,ACC_PUBLIC,"Checked",null,"java/lang/Object",null);m.accept(w);w.visitEnd();return w.toByteArray();}
    private static MethodNode copy(MethodNode source){
        // ASM's Label.info can still point to the source tree. Reset it before
        // visiting another MethodNode so editing the audit copy cannot alter the input.
        source.instructions.resetLabels();
        MethodNode m=new MethodNode(source.access,source.name,source.desc,source.signature,source.exceptions==null?null:(String[])source.exceptions.toArray(new String[0]));
        source.accept(m);source.instructions.resetLabels();m.instructions.resetLabels();return m;
    }
    private static ClassNode type(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    private static MethodNode method(ClassNode node,String name,String desc){for(MethodNode m:node.methods)if(m.name.equals(name)&&m.desc.equals(desc))return m;throw new AssertionError("Missing "+name+desc);}
    private static byte[] read(JarFile jar,String name)throws IOException{JarEntry entry=jar.getJarEntry(name);if(entry==null)throw new IOException("Missing "+name);try(InputStream in=jar.getInputStream(entry);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
