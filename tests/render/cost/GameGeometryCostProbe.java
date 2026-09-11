package local.mcgl.render.tests;

import java.io.File;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

/** CPU-only, identical reflection overhead for both renderers; not a game FPS estimate. */
public final class GameGeometryCostProbe {
    private static volatile Object sink;
    public static void main(String[] args)throws Exception {
        try(URLClassLoader old=new URLClassLoader(new URL[]{new File(args[0]).toURI().toURL()},null)) {
            Class<?>[] versions={Class.forName("local.mcgl.render.GameGeometry",true,old),Class.forName("local.mcgl.render.GameGeometry")};
            Method[] methods=new Method[2];for(int i=0;i<2;i++)methods[i]=versions[i].getMethod("raw",int[].class,int.class,int.class,int.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class);
            for(int mode:new int[]{1,7}) {
                int vertices=1024;int[] words=new int[vertices*8];for(int v=0;v<vertices;v++){words[v*8]=Float.floatToRawIntBits(v%512);words[v*8+1]=Float.floatToRawIntBits(v%37);words[v*8+5]=-1;}
                Object[] input={words,words.length,vertices,mode,false,true,true,false,false,false};
                for(int warm=0;warm<600;warm++)for(Method method:methods)sink=method.invoke(null,input);
                for(int round=0;round<4;round++)for(int step=0;step<2;step++) {
                    int k=(step+round)%2;double[] times=new double[128];
                    for(int sample=0;sample<times.length;sample++){long start=System.nanoTime();sink=methods[k].invoke(null,input);times[sample]=(System.nanoTime()-start)/1e6;}
                    Arrays.sort(times);System.out.printf(Locale.ROOT,"GEOMETRY_CPU_COST mode=%d version=%s round=%d p50_ms=%.4f p95_ms=%.4f%n",mode,k==0?"packaged":"candidate",round,times[64],times[121]);
                }
            }
        }
        System.out.println("GAME_GEOMETRY_COST_PASS synthetic-only");
    }
}
