import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.security.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class ClientShapeAudit implements Opcodes {
    static Map<String,ClassNode> read(String path)throws Exception {
        Map<String,ClassNode> out=new TreeMap<String,ClassNode>();
        try(JarFile jar=new JarFile(path)) {
            for(Enumeration<JarEntry> e=jar.entries();e.hasMoreElements();) {
                JarEntry entry=e.nextElement();if(!entry.getName().endsWith(".class"))continue;
                try(InputStream in=jar.getInputStream(entry)) {ClassNode n=new ClassNode();new ClassReader(in).accept(n,0);out.put(n.name,n);}
            }
        }return out;
    }
    static String type(String name,Map<String,ClassNode> all) {return all.containsKey(name)?"internal":name;}
    static String desc(String desc,Map<String,ClassNode> all) {
        Matcher m=Pattern.compile("L([^;]+);").matcher(desc);StringBuffer b=new StringBuffer();
        while(m.find())m.appendReplacement(b,Matcher.quoteReplacement("L"+type(m.group(1),all)+";"));m.appendTail(b);return b.toString();
    }
    static String method(MethodNode m,Map<String,ClassNode> all,boolean body) {
        StringBuilder s=new StringBuilder(m.access+":"+(m.name.startsWith("<")?m.name:"method")+desc(m.desc,all));
        if(!body)return s.toString();
        for(AbstractInsnNode n:m.instructions.toArray()) {
            int op=n.getOpcode();if(op<0)continue;
            if(op>=ICONST_M1&&op<=ICONST_5||op==BIPUSH||op==SIPUSH){s.append(";int");continue;}
            if(n instanceof LdcInsnNode) {Object c=((LdcInsnNode)n).cst;s.append(c instanceof Integer?";int":";const:"+c.getClass().getName());continue;}
            s.append(';').append(op);
            if(n instanceof MethodInsnNode) {MethodInsnNode c=(MethodInsnNode)n;s.append(':').append(type(c.owner,all)).append('.').append(all.containsKey(c.owner)&&!c.name.startsWith("<")?"m":c.name).append(desc(c.desc,all));}
            if(n instanceof FieldInsnNode) {FieldInsnNode f=(FieldInsnNode)n;s.append(':').append(type(f.owner,all)).append('.').append(all.containsKey(f.owner)?"f":f.name).append(desc(f.desc,all));}
            if(n instanceof TypeInsnNode)s.append(':').append(type(((TypeInsnNode)n).desc,all));
            if(n instanceof VarInsnNode)s.append(':').append(((VarInsnNode)n).var);
            if(n instanceof IincInsnNode)s.append(':').append(((IincInsnNode)n).var).append(':').append(((IincInsnNode)n).incr);
            if(n instanceof IntInsnNode)s.append(':').append(((IntInsnNode)n).operand);
        }return s.toString();
    }
    static String shape(ClassNode c,Map<String,ClassNode> all,boolean body)throws Exception {
        List<String> entries=new ArrayList<String>();
        for(FieldNode f:c.fields)entries.add("field:"+f.access+":"+desc(f.desc,all));
        for(MethodNode m:c.methods)entries.add("method:"+method(m,all,body));
        Collections.sort(entries);StringBuilder s=new StringBuilder(c.access+":"+type(c.superName,all)+":"+c.interfaces.size());
        for(String i:entries)s.append('\n').append(i);
        byte[] h=MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes("UTF-8"));StringBuilder b=new StringBuilder();for(byte x:h)b.append(String.format("%02x",x));return b.toString();
    }
    public static void main(String[] args)throws Exception {
        Map<String,ClassNode> old=read(args[0]),fresh=read(args[1]);
        Map<String,List<String>> full=new HashMap<String,List<String>>(),headers=new HashMap<String,List<String>>();
        for(ClassNode n:fresh.values()){full.computeIfAbsent(shape(n,fresh,true),k->new ArrayList<String>()).add(n.name);headers.computeIfAbsent(shape(n,fresh,false),k->new ArrayList<String>()).add(n.name);}
        int matched=0;
        for(ClassNode n:old.values()) {
            List<String> result=full.get(shape(n,old,true));
            if(result!=null&&result.size()==1){System.out.println("EXACT\t"+n.name+"\t"+result.get(0));matched++;}
            else {result=headers.get(shape(n,old,false));if(result!=null)System.out.println("HEADER\t"+n.name+"\t"+String.join(",",result));else System.out.println("UNMATCHED\t"+n.name);}
        }
        System.err.println("Unique structural matches: "+matched+" / "+old.size());
    }
}
