import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.jar.*;
import local.mcgl.render.GameRenderCommands;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Packaged 1.7.1 versus the candidate: only the world submission wrapper may
 * change. Execute that actual wrapper, including its exception table, with a
 * synthetic body/command target rather than a world, account or native context. */
public final class OriginalTerrainScopePatchTest implements Opcodes {
    private static final String SELF="OriginalTerrainScopePatchTest", FIXTURE="OriginalTerrainWrapperFixture";
    private static final List<String> trace=new ArrayList<String>();
    private static RuntimeException beginFailure,bodyFailure,endFailure;
    private static int checks;
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("released-1.7.1-core-client candidate-core-client");
        ClassNode world=null;
        try(JarFile before=new JarFile(args[0]);JarFile after=new JarFile(args[1])) {
            Set<String> oldNames=names(before),newNames=names(after);
            check(oldNames.equals(newNames),"client entry inventory unchanged");
            for(String name:oldNames){
                byte[] old=read(before,name),next=read(after,name);
                if(name.matches("net/A/U/Ooo0O{100,}\\.class")) {
                    check(world==null,"exactly one original world renderer");world=type(next);
                    ClassNode previous=type(old);
                    check(world.methods.size()==previous.methods.size()+1,"only one submission wrapper added");
                    for(MethodNode m:previous.methods){
                        String target=m.name.equals("o00000")&&m.desc.equals("(ID)V")?"mcglDrawOriginalTerrainBody":m.name;
                        check(Arrays.equals(normalized(m),normalized(method(world,target,m.desc))),"original world method unchanged: "+m.name+m.desc);
                    }
                }else check(Arrays.equals(old,next),"unrelated packaged client entry unchanged: "+name);
            }
        }
        check(world!=null,"world renderer located");
        Method run=fixture(world).getMethod("run",int.class,double.class);Object instance=run.getDeclaringClass().getConstructor().newInstance();
        run.invoke(instance,2,.375);
        check(trace.equals(Arrays.asList("begin","body:2:0.375","end:7")),"actual wrapper forwards pass/interpolation and closes its returned token");
        for(String fault:new String[]{"begin","body","end"}) {
            trace.clear();RuntimeException expected=new IllegalStateException(fault);
            beginFailure=fault.equals("begin")?expected:null;bodyFailure=fault.equals("body")?expected:null;endFailure=fault.equals("end")?expected:null;
            Throwable caught=null;try{run.invoke(instance,1,.625);}catch(InvocationTargetException failure){caught=failure.getCause();}
            check(caught==expected,"actual wrapper propagates "+fault+" exception");
            check(trace.equals(fault.equals("begin")?Arrays.asList("begin"):Arrays.asList("begin","body:1:0.625","end:7")),"actual wrapper cleans up exactly once for "+fault);
        }
        beginFailure=bodyFailure=endFailure=null;trace.clear();run.invoke(instance,0,1.0);
        check(trace.equals(Arrays.asList("begin","body:0:1.0","end:7")),"valid call after failed submissions");
        System.out.println("ORIGINAL_TERRAIN_SCOPE_PATCH_PASS checks="+checks+" only-world-wrapper/original-body/arguments/success/failure/no-double-cleanup");
    }
    public static GameRenderCommands commands(){
        return (GameRenderCommands)Proxy.newProxyInstance(OriginalTerrainScopePatchTest.class.getClassLoader(),new Class<?>[]{GameRenderCommands.class},(proxy,method,args)->{
            if(method.getName().equals("beginOriginalTerrain")){trace.add("begin");if(beginFailure!=null)throw beginFailure;return 7;}
            if(method.getName().equals("endOriginalTerrain")){trace.add("end:"+args[0]);if(endFailure!=null)throw endFailure;return null;}
            throw new AssertionError("Unexpected command "+method.getName());
        });
    }
    public static void body(int pass,double interpolation){trace.add("body:"+pass+":"+interpolation);if(bodyFailure!=null)throw bodyFailure;}
    private static Class<?> fixture(ClassNode world){
        MethodNode original=method(world,"o00000","(ID)V"),wrapper=copy(original);wrapper.name="run";
        for(AbstractInsnNode i:wrapper.instructions.toArray()) {
            if(i instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)i;
                if(call.owner.equals(world.name))call.owner=FIXTURE;
                else if(call.owner.equals("local/mcgl/render/RenderSystem")&&call.name.equals("game")){call.owner=SELF;call.name="commands";}
            }else if(i instanceof FrameNode){FrameNode frame=(FrameNode)i;if(frame.local!=null)for(int n=0;n<frame.local.size();n++)if(world.name.equals(frame.local.get(n)))frame.local.set(n,FIXTURE);}
        }
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(V1_7,ACC_PUBLIC,FIXTURE,null,"java/lang/Object",null);
        MethodVisitor ctor=writer.visitMethod(ACC_PUBLIC,"<init>","()V",null,null);ctor.visitCode();ctor.visitVarInsn(ALOAD,0);ctor.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V");ctor.visitInsn(RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();
        MethodVisitor body=writer.visitMethod(ACC_PRIVATE,"mcglDrawOriginalTerrainBody","(ID)V",null,null);body.visitCode();body.visitVarInsn(ILOAD,1);body.visitVarInsn(DLOAD,2);body.visitMethodInsn(INVOKESTATIC,SELF,"body","(ID)V");body.visitInsn(RETURN);body.visitMaxs(0,0);body.visitEnd();
        wrapper.accept(writer);writer.visitEnd();byte[] bytes=writer.toByteArray();
        return new ClassLoader(OriginalTerrainScopePatchTest.class.getClassLoader()){Class<?> define(){return defineClass(FIXTURE,bytes,0,bytes.length);}}.define();
    }
    private static byte[] normalized(MethodNode source){MethodNode copy=copy(source);copy.name="checked";copy.access=ACC_PUBLIC;copy.maxStack=copy.maxLocals=0;ClassWriter writer=new ClassWriter(0);writer.visit(V1_6,ACC_PUBLIC,"Checked",null,"java/lang/Object",null);copy.accept(writer);writer.visitEnd();return writer.toByteArray();}
    private static MethodNode copy(MethodNode source){MethodNode result=new MethodNode(source.access,source.name,source.desc,source.signature,source.exceptions==null?null:(String[])source.exceptions.toArray(new String[0]));source.accept(result);return result;}
    private static MethodNode method(ClassNode node,String name,String desc){for(MethodNode method:node.methods)if(method.name.equals(name)&&method.desc.equals(desc))return method;throw new AssertionError("Missing "+name+desc);}
    private static ClassNode type(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static Set<String> names(JarFile jar){Set<String> result=new TreeSet<String>();for(Enumeration<JarEntry> es=jar.entries();es.hasMoreElements();){JarEntry e=es.nextElement();if(!e.isDirectory())result.add(e.getName());}return result;}
    private static byte[] read(JarFile jar,String name)throws IOException{try(InputStream in=jar.getInputStream(jar.getJarEntry(name));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[16384];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);return out.toByteArray();}}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
