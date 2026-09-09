import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Independent complete-method audit: formatting, metrics, glyphs and shadow algorithms are retained. */
public final class FontAdapterTest implements Opcodes {
    private static int checks,methods;
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("before after manifest negative-directory");
        Map<String,RenderCommandSpec> manifest=RenderCommandSpec.read(Paths.get(args[2]));
        try(JarFile before=new JarFile(args[0]);JarFile after=new JarFile(args[1])) {
            check(new String(read(after,"META-INF/mcgl/text-policy"),StandardCharsets.UTF_8).equals("ordered-glyphs-v1\n"),"explicit text policy");
            for(String name:new String[]{"net/A/U/E/C","net/A/U/E/oOOO"}) {
                ClassNode old=type(read(before,name+".class")),next=type(read(after,name+".class"));
                String[] descriptors=name.endsWith("/C")?new String[]{"(Ljava/lang/String;IIIZ)V","(Ljava/lang/String;FFIZ)V"}:new String[]{"(Ljava/lang/String;FFIZ)V"};
                check(next.fields.size()==old.fields.size()&&next.methods.size()==old.methods.size()+descriptors.length,"only scope wrappers added");
                for(MethodNode m:old.methods) {
                    MethodNode actual=copy(method(next,m.name.equals("o00000")&&Arrays.asList(descriptors).contains(m.desc)?"mcglDrawTextBody":m.name,m.desc));
                    if(name.endsWith("/C")&&m.name.equals("<init>"))stripRegistration(actual,name);
                    MethodNode expected=copy(m);
                    for(AbstractInsnNode i:expected.instructions.toArray())if(i instanceof MethodInsnNode) {
                        MethodInsnNode call=(MethodInsnNode)i;
                        if(call.owner.equals("local/mcgl/perf/ChunkVbo")&&call.name.equals("unsupported")){expected.instructions.set(call,new InsnNode(POP));continue;}
                        if(call.owner.equals("local/mcgl/perf/ChunkVbo")&&call.name.equals("glNewList")&&call.desc.equals("(IIZ)V")){expected.instructions.insertBefore(call,new InsnNode(POP));call.desc="(II)V";}
                        String owner=call.owner.startsWith("local/mcgl/render/legacy/")?"org/lwjgl/opengl/"+call.owner.substring("local/mcgl/render/legacy/".length()):call.owner.equals("local/mcgl/perf/ChunkVbo")&&call.name.startsWith("gl")?"org/lwjgl/opengl/GL11":call.owner;
                        if(owner.startsWith("org/lwjgl/opengl/")&&call.name.startsWith("gl")) {
                            RenderCommandSpec spec=manifest.get(RenderCommandSpec.key(owner,call.name,call.desc));check(spec!=null,"known glyph command: "+owner+"."+call.name+call.desc);call.owner="local/mcgl/render/game/"+spec.family;
                        }
                    }
                    check(Arrays.equals(normalize(expected),normalize(actual)),"original font method unchanged: "+name+" "+m.name+m.desc);methods++;
                }
                for(String desc:descriptors){MethodNode wrapper=method(next,"o00000",desc);
                    for(String call:new String[]{"beginText","endText","abortText","mcglDrawTextBody"})check(calls(wrapper,call)==1,"single scoped call: "+call);
                    check(wrapper.tryCatchBlocks.size()==1&&wrapper.tryCatchBlocks.get(0).type.equals("java/lang/Throwable"),"all failures clean up scope");
                }
                for(MethodNode m:next.methods)if((m.access&(ACC_ABSTRACT|ACC_NATIVE))==0)new Analyzer(new BasicVerifier()).analyze(name,m);
            }
        }
        Path root=Paths.get(args[3]);Files.createDirectories(root);
        for(int variant=0;variant<3;variant++) {
            Path input=root.resolve("font-mutated-"+variant+".jar"),output=root.resolve("font-rejected-"+variant+".jar");
            try(JarFile jar=new JarFile(args[0]);JarOutputStream out=new JarOutputStream(Files.newOutputStream(input))) {
                for(Enumeration<JarEntry> entries=jar.entries();entries.hasMoreElements();) {
                    JarEntry e=entries.nextElement();if(e.isDirectory())continue;
                    if(variant==2&&e.getName().equals("net/A/U/E/C.class"))continue;
                    byte[] bytes=read(jar,e.getName());
                    if(e.getName().equals(variant==0?"net/A/U/E/C.class":"net/A/U/E/oOOO.class")&&variant<2) {
                        ClassNode n=type(bytes);MethodNode m=variant==0?method(n,"<init>","(Lnet/A/for/o00Oo;Ljava/lang/String;Lnet/A/U/Oooo;)V"):method(n,"Ó00000","(C)V");
                        m.instructions.insert(new InsnNode(NOP));ClassWriter w=new ClassWriter(0);n.accept(w);bytes=w.toByteArray();
                    }
                    out.putNextEntry(new JarEntry(e.getName()));out.write(bytes);out.closeEntry();
                }
            }
            boolean failed=false;try{PatchMCGLGame.main(new String[]{input.toString(),output.toString(),args[2],"--original-chunks"});}catch(IOException expected){failed=true;}
            check(failed&&!Files.exists(output),"unexpected font input rejected atomically");
        }
        System.out.println("FONT_ADAPTER_PASS checks="+checks+" originalMethods="+methods+" exact-glyphs/formatting/metrics/shadows/scoped-cleanup");
    }
    private static void stripRegistration(MethodNode m,String owner) {
        int found=0;
        for(AbstractInsnNode i:m.instructions.toArray())if(i instanceof MethodInsnNode&&((MethodInsnNode)i).name.equals("defineFontGlyphs")) {
            AbstractInsnNode[] hook=new AbstractInsnNode[5];hook[4]=i;for(int n=3;n>=0;n--)hook[n]=hook[n+1].getPrevious();
            check(hook[0] instanceof MethodInsnNode&&((MethodInsnNode)hook[0]).owner.equals("local/mcgl/render/RenderSystem")&&((MethodInsnNode)hook[0]).name.equals("game"),"registration uses context owner");
            check(hook[1].getOpcode()==ALOAD&&((VarInsnNode)hook[1]).var==0,"registration receiver");
            check(hook[2].getOpcode()==GETFIELD&&((FieldInsnNode)hook[2]).owner.equals(owner)&&((FieldInsnNode)hook[2]).name.equals("class"),"original glyph range");
            check(hook[3].getOpcode()==SIPUSH&&((IntInsnNode)hook[3]).operand==256,"only 256 glyphs, excluding color commands");
            for(AbstractInsnNode remove:hook)m.instructions.remove(remove);found++;
        }
        check(found==1,"one glyph registration");
    }
    private static int calls(MethodNode m,String name){int n=0;for(AbstractInsnNode i:m.instructions.toArray())if(i instanceof MethodInsnNode&&((MethodInsnNode)i).name.equals(name))n++;return n;}
    private static byte[] normalize(MethodNode m){m.name="checked";m.access=ACC_PUBLIC;m.maxStack=m.maxLocals=0;ClassWriter w=new ClassWriter(0);w.visit(50,ACC_PUBLIC,"Checked",null,"java/lang/Object",null);m.accept(w);w.visitEnd();return w.toByteArray();}
    private static MethodNode copy(MethodNode m){MethodNode n=new MethodNode(m.access,m.name,m.desc,m.signature,m.exceptions==null?null:(String[])m.exceptions.toArray(new String[0]));m.accept(n);return n;}
    private static ClassNode type(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    private static MethodNode method(ClassNode n,String name,String desc){for(MethodNode m:n.methods)if(m.name.equals(name)&&m.desc.equals(desc))return m;throw new AssertionError(name+desc);}
    private static byte[] read(JarFile j,String name)throws IOException{try(InputStream in=j.getInputStream(j.getJarEntry(name));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
