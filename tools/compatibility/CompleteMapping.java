import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.tree.*;

/** Complete a candidate bijection, explicitly disambiguating added members. */
public class CompleteMapping {
 public static void main(String[] args)throws Exception {
  Map<String,ClassNode> current=ClientShapeAudit.read(args[1]);
  MCGLClientCompatibility.Names names=new MCGLClientCompatibility.Names(current);
  List<String[]> records=new ArrayList<String[]>();
  for(String line:Files.readAllLines(Paths.get(args[0]))){String[] p=line.split("\t");records.add(p);if(p[0].equals("C"))names.classes.put(p[2],p[1]);}
  for(String name:current.keySet())if(!names.classes.containsKey(name))names.classes.put(name,"local/mcgl/staging/upstream/"+name);
  MCGLClientCompatibility.Names forward=new MCGLClientCompatibility.Names(Collections.<String,ClassNode>emptyMap());for(Map.Entry<String,String> e:names.classes.entrySet())forward.classes.put(e.getValue(),e.getKey());
  for(String[] p:records)if(!p[0].equals("C"))names.members.put(MCGLClientCompatibility.Names.key(p[0],forward.map(p[1]),p[4],p[0].equals("M")?forward.mapMethodDesc(p[3]):forward.mapDesc(p[3])),p[2]);
  List<String> out=new ArrayList<String>();for(Map.Entry<String,String> e:new TreeMap<String,String>(names.classes).entrySet())out.add("C\t"+e.getValue()+"\t"+e.getKey());
  int collisions=0;
  for(ClassNode c:current.values())for(String kind:new String[]{"F","M"}) {
   List<String[]> members=new ArrayList<String[]>();
   if(kind.equals("F"))for(FieldNode f:c.fields)members.add(new String[]{f.name,f.desc});else for(MethodNode m:c.methods)members.add(new String[]{m.name,m.desc});
   Set<String> used=new HashSet<String>();Map<String,String> chosen=new LinkedHashMap<String,String>();
   // Explicit candidate correspondences win over new/unmatched declarations.
   for(String[] p:members){String k=MCGLClientCompatibility.Names.key(kind,c.name,p[0],p[1]),mapped=names.members.get(k);if(mapped!=null){if(!used.add(mapped+" "+p[1]))throw new Exception("Explicit alias collision: "+k);chosen.put(k,mapped);}}
   for(String[] p:members) {
    String k=MCGLClientCompatibility.Names.key(kind,c.name,p[0],p[1]);if(chosen.containsKey(k))continue;
    String mapped=kind.equals("F")?names.mapFieldName(c.name,p[0],p[1]):names.mapMethodName(c.name,p[0],p[1]);
    if(!used.add(mapped+" "+p[1])) {
     mapped="mcglStaging$"+MCGLBytecodeFingerprint.sha256((k).getBytes("UTF-8")).substring(0,24);
     if(!used.add(mapped+" "+p[1]))throw new Exception("Staging collision");collisions++;
    }
    chosen.put(k,mapped);
   }
   for(String[] p:members)out.add(kind+"\t"+names.map(c.name)+"\t"+chosen.get(MCGLClientCompatibility.Names.key(kind,c.name,p[0],p[1]))+"\t"+(kind.equals("M")?names.mapMethodDesc(p[1]):names.mapDesc(p[1]))+"\t"+p[0]);
  }
  Files.write(Paths.get(args[2]),out);System.out.println("COMPLETE_MAPPING records="+out.size()+" disambiguated="+collisions);
 }
}
