import java.io.*;
import java.util.*;
import java.util.jar.*;

/** HotSpot verification of changed original classes, without class initialization or authentication. */
public final class GameClassLoadTest {
    public static void main(String[] args)throws Exception {
        int classes=0;
        try(JarFile before=new JarFile(args[0]);JarFile after=new JarFile(args[1])) {
            for(Enumeration<JarEntry> entries=after.entries();entries.hasMoreElements();) {
                JarEntry e=entries.nextElement();if(!e.getName().endsWith(".class")||Arrays.equals(read(before,e.getName()),read(after,e.getName())))continue;
                Class<?> type=Class.forName(e.getName().substring(0,e.getName().length()-6).replace('/','.'),false,GameClassLoadTest.class.getClassLoader());
                type.getDeclaredMethods();type.getDeclaredConstructors();classes++;
            }
        }
        if(classes<200)throw new AssertionError("Incomplete client verification");
        System.out.println("GAME_CLASS_LOAD_PASS verified="+classes+" initialization=false");
    }
    private static byte[] read(JarFile jar,String name)throws IOException{try(InputStream in=jar.getInputStream(jar.getJarEntry(name));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
}
