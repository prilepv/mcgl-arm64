import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.tree.*;
public class RefineMembers {
 public static void main(String[] a)throws Exception {
  Map<String,ClassNode> old=ClientShapeAudit.read(a[0]),fresh=ClientShapeAudit.read(a[1]);
  List<String> out=Files.readAllLines(Paths.get(a[2]));Map<String,String> records=new LinkedHashMap<>();
  for(String line:out){String[] p=line.split("\t");if(!p[0].equals("C"))records.put(String.join("\t",Arrays.copyOf(p,4)),p[4]);}
  int added=0;
  for(String line:new ArrayList<>(out)) {String[] p=line.split("\t");if(!p[0].equals("C"))continue;
   ClassNode x=old.get(p[1]),y=fresh.get(p[2]);
   Map<String,List<MethodNode>> left=new HashMap<>(),right=new HashMap<>();
   for(MethodNode m:x.methods)left.computeIfAbsent(ClientShapeAudit.method(m,old,true),k->new ArrayList<>()).add(m);
   for(MethodNode m:y.methods)right.computeIfAbsent(ClientShapeAudit.method(m,fresh,true),k->new ArrayList<>()).add(m);
   for(String shape:left.keySet())if(left.get(shape).size()==1&&right.containsKey(shape)&&right.get(shape).size()==1){
    MethodNode m=left.get(shape).get(0),n=right.get(shape).get(0);String k="M\t"+x.name+"\t"+m.name+"\t"+m.desc;
    if(!records.containsKey(k)){records.put(k,n.name);added++;System.out.println("UNIQUE_BODY "+x.name+" "+m.name+m.desc+" -> "+n.name);}
    else if(!records.get(k).equals(n.name))System.out.println("REVIEW_CONFLICT "+k+" "+records.get(k)+" vs "+n.name);
   }
  }
  List<String> result=new ArrayList<>();for(String l:out)if(l.startsWith("C\t"))result.add(l);
  for(Map.Entry<String,String> e:records.entrySet())result.add(e.getKey()+"\t"+e.getValue());
  Files.write(Paths.get(a[3]),result);System.out.println("ADDED_UNIQUE_METHODS "+added);
 }
}
