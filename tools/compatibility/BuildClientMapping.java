import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Offline migration audit. Produces candidates, never installed automatically. */
public class BuildClientMapping {
    static Map<String,ClassNode> old,fresh;
    static Map<String,String> classes=new TreeMap<String,String>(),reverse=new TreeMap<String,String>();
    static Set<String> conflicts=new TreeSet<String>();
    static void pair(String a,String b,String origin) {
        if(!old.containsKey(a)||!fresh.containsKey(b))return;
        if(classes.containsKey(a)&&!classes.get(a).equals(b)||reverse.containsKey(b)&&!reverse.get(b).equals(a)) {
            conflicts.add(a+" -> "+b+" existing="+classes.get(a)+" reverse="+reverse.get(b)+" from "+origin);return;
        }classes.put(a,b);reverse.put(b,a);
    }
    static void desc(String a,String b,String origin) {
        java.util.regex.Matcher x=java.util.regex.Pattern.compile("L([^;]+);").matcher(a),y=java.util.regex.Pattern.compile("L([^;]+);").matcher(b);
        while(x.find()&&y.find())pair(x.group(1),y.group(1),origin);
    }
    static List<AbstractInsnNode> instructions(MethodNode m) {
        List<AbstractInsnNode> list=new ArrayList<AbstractInsnNode>();for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()>=0)list.add(i);return list;
    }
    static String mapDesc(String input) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("L([^;]+);").matcher(input);StringBuffer out=new StringBuffer();
        while(m.find())m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement("L"+classes.getOrDefault(m.group(1),m.group(1))+";"));m.appendTail(out);return out.toString();
    }
    static String declaring(Map<String,ClassNode> jar,String kind,String owner,String name,String desc) {
        ClassNode c=jar.get(owner);if(c==null)return null;
        if(kind.equals("F")){for(FieldNode f:c.fields)if(f.name.equals(name)&&f.desc.equals(desc))return owner;}
        else {for(MethodNode m:c.methods)if(m.name.equals(name)&&m.desc.equals(desc))return owner;}
        if(c.superName!=null){String s=declaring(jar,kind,c.superName,name,desc);if(s!=null)return s;}
        for(String parent:c.interfaces){String s=declaring(jar,kind,parent,name,desc);if(s!=null)return s;}
        return null;
    }
    static void member(Map<String,String> members,String kind,String x,String xn,String xd,String y,String yn,String yd,String origin) {
        x=declaring(old,kind,x,xn,xd);y=declaring(fresh,kind,y,yn,yd);
        if(x==null||y==null||!y.equals(classes.get(x))||!mapDesc(xd).equals(yd))return;
        String key=kind+"\t"+x+"\t"+xn+"\t"+xd;
        String previous=members.get(key);
        if(previous!=null&&!previous.equals(yn))conflicts.add("MEMBER "+key+" -> "+previous+" / "+yn+" from "+origin);
        else members.put(key,yn);
    }
    public static void main(String[] args)throws Exception {
        old=ClientShapeAudit.read(args[0]);fresh=ClientShapeAudit.read(args[1]);
        Map<String,List<String>> a=new HashMap<String,List<String>>(),b=new HashMap<String,List<String>>();
        for(ClassNode n:old.values())a.computeIfAbsent(ClientShapeAudit.shape(n,old,true),k->new ArrayList<String>()).add(n.name);
        for(ClassNode n:fresh.values())b.computeIfAbsent(ClientShapeAudit.shape(n,fresh,true),k->new ArrayList<String>()).add(n.name);
        for(String shape:a.keySet())if(a.get(shape).size()==1&&b.containsKey(shape)&&b.get(shape).size()==1)pair(a.get(shape).get(0),b.get(shape).get(0),"unique-shape");
        pair("net/minecraft/client/Minecraft","net/minecraft/client/Minecraft","stable-entrypoint");
        pair("net/minecraft/client/MinecraftApplet","net/minecraft/client/MinecraftApplet","stable-entrypoint");
        int last=-1;
        while(last!=classes.size()) {
            last=classes.size();
            for(Map.Entry<String,String> e:new ArrayList<Map.Entry<String,String>>(classes.entrySet())) {
                ClassNode x=old.get(e.getKey()),y=fresh.get(e.getValue());
                pair(x.superName,y.superName,x.name+" super");
                if(x.interfaces.size()==y.interfaces.size())for(int i=0;i<x.interfaces.size();i++)pair(x.interfaces.get(i),y.interfaces.get(i),x.name+" interface");
                if(x.fields.size()==y.fields.size())for(int i=0;i<x.fields.size();i++) {
                    FieldNode f=x.fields.get(i),g=y.fields.get(i);
                    if(f.access==g.access&&ClientShapeAudit.desc(f.desc,old).equals(ClientShapeAudit.desc(g.desc,fresh)))desc(f.desc,g.desc,x.name+" field "+i);
                }
                if(x.methods.size()!=y.methods.size())continue;
                for(int i=0;i<x.methods.size();i++) {
                    MethodNode f=x.methods.get(i),g=y.methods.get(i);
                    if(!ClientShapeAudit.method(f,old,false).equals(ClientShapeAudit.method(g,fresh,false)))continue;
                    desc(f.desc,g.desc,x.name+" method "+i);
                    if(!ClientShapeAudit.method(f,old,true).equals(ClientShapeAudit.method(g,fresh,true)))continue;
                    List<AbstractInsnNode> u=instructions(f),v=instructions(g);
                    if(u.size()!=v.size())continue;
                    for(int j=0;j<u.size();j++) {
                        AbstractInsnNode p=u.get(j),q=v.get(j);String origin=x.name+"."+f.name+" instruction "+j;
                        if(p instanceof FieldInsnNode&&q instanceof FieldInsnNode) {FieldInsnNode ff=(FieldInsnNode)p,gg=(FieldInsnNode)q;pair(ff.owner,gg.owner,origin);desc(ff.desc,gg.desc,origin);}
                        if(p instanceof MethodInsnNode&&q instanceof MethodInsnNode) {MethodInsnNode ff=(MethodInsnNode)p,gg=(MethodInsnNode)q;pair(ff.owner,gg.owner,origin);desc(ff.desc,gg.desc,origin);}
                        if(p instanceof TypeInsnNode&&q instanceof TypeInsnNode)pair(((TypeInsnNode)p).desc,((TypeInsnNode)q).desc,origin);
                    }
                }
            }
        }
        List<String> output=new ArrayList<String>();
        for(Map.Entry<String,String> e:classes.entrySet()) {
            output.add("C\t"+e.getKey()+"\t"+e.getValue());ClassNode x=old.get(e.getKey()),y=fresh.get(e.getValue());
            if(x.fields.size()==y.fields.size())for(int i=0;i<x.fields.size();i++) {
                FieldNode f=x.fields.get(i),g=y.fields.get(i);
                if(f.access==g.access&&mapDesc(f.desc).equals(g.desc))output.add("F\t"+x.name+"\t"+f.name+"\t"+f.desc+"\t"+g.name);
                else System.err.println("UNMAPPED_FIELD "+x.name+" "+f.name+f.desc);
            }
            if(x.methods.size()==y.methods.size())for(int i=0;i<x.methods.size();i++) {
                MethodNode f=x.methods.get(i),g=y.methods.get(i);
                if(f.access==g.access&&mapDesc(f.desc).equals(g.desc))output.add("M\t"+x.name+"\t"+f.name+"\t"+f.desc+"\t"+g.name);
                else System.err.println("UNMAPPED_METHOD "+x.name+" "+f.name+f.desc);
            }
        }
        Map<String,String> members=new LinkedHashMap<String,String>();
        List<String> classRecords=new ArrayList<String>();
        for(String line:output){String[] p=line.split("\t");if(p[0].equals("C"))classRecords.add(line);else members.put(p[0]+"\t"+p[1]+"\t"+p[2]+"\t"+p[3],p[4]);}
        for(Map.Entry<String,String> e:classes.entrySet()) {
            ClassNode x=old.get(e.getKey()),y=fresh.get(e.getValue());if(x.methods.size()!=y.methods.size())continue;
            for(int i=0;i<x.methods.size();i++) {
                MethodNode f=x.methods.get(i),g=y.methods.get(i);
                if(!ClientShapeAudit.method(f,old,true).equals(ClientShapeAudit.method(g,fresh,true)))continue;
                List<AbstractInsnNode> a0=instructions(f),b0=instructions(g);if(a0.size()!=b0.size())continue;
                for(int j=0;j<a0.size();j++) {
                    AbstractInsnNode p=a0.get(j),q=b0.get(j);String origin=x.name+"."+f.name+" "+j;
                    if(p instanceof FieldInsnNode&&q instanceof FieldInsnNode){FieldInsnNode u=(FieldInsnNode)p,v=(FieldInsnNode)q;member(members,"F",u.owner,u.name,u.desc,v.owner,v.name,v.desc,origin);}
                    if(p instanceof MethodInsnNode&&q instanceof MethodInsnNode){MethodInsnNode u=(MethodInsnNode)p,v=(MethodInsnNode)q;member(members,"M",u.owner,u.name,u.desc,v.owner,v.name,v.desc,origin);}
                }
            }
        }
        output=classRecords;for(Map.Entry<String,String> e:members.entrySet())output.add(e.getKey()+"\t"+e.getValue());
        Files.write(Paths.get(args[2]),output);
        Files.write(Paths.get(args[2]+".conflicts"),conflicts);
        System.out.println("MAPPING_CANDIDATE classes="+classes.size()+" records="+output.size()+" conflicts="+conflicts.size());
    }
}
