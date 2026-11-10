package local.mcgl.render.tests;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.net.*;
import java.util.Locale;
import com.sun.management.ThreadMXBean;
import local.mcgl.render.GameMatrices;

/** Same reflection overhead for both versions; allocation/CPU microbenchmark, never a game FPS claim. */
public final class GameMatricesCostProbe {
    private static volatile double sink;
    static final class Version {
        final Object value;final Method push,pop,translate,scale,rotate,read;
        final Object[] empty={},translation={127.25,-8.5,256.125},scaling={1.000001,.999999,1.0},rotation={17.5,1.0,2.0,3.0};
        Version(Class<?> type)throws Exception {
            value=type.getConstructor().newInstance();push=type.getMethod("push");pop=type.getMethod("pop");read=type.getMethod("modelView");
            translate=type.getMethod("translate",double.class,double.class,double.class);
            scale=type.getMethod("scale",double.class,double.class,double.class);
            rotate=type.getMethod("rotate",double.class,double.class,double.class,double.class);
        }
        void work(int count)throws Exception{
            double sum=0;
            for(int i=0;i<count;i++){
                push.invoke(value,empty);translate.invoke(value,translation);scale.invoke(value,scaling);rotate.invoke(value,rotation);
                sum+=((double[])read.invoke(value,empty))[12];pop.invoke(value,empty);
            }
            sink=sum;
        }
    }
    public static void main(String[] args)throws Exception{
        try(URLClassLoader loader=new URLClassLoader(new URL[]{new File(args[0]).toURI().toURL()},null)){
            Version[] versions={new Version(Class.forName("local.mcgl.render.GameMatrices",true,loader)),new Version(GameMatrices.class)};
            ThreadMXBean bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
            long thread=Thread.currentThread().threadId();
            for(int i=0;i<4;i++)for(Version v:versions)v.work(15000);
            for(int round=0;round<4;round++)for(int step=0;step<2;step++){
                int version=(round+step)%2,iterations=100000;
                long bytes=bean.getThreadAllocatedBytes(thread),cpu=bean.getCurrentThreadCpuTime(),start=System.nanoTime();
                versions[version].work(iterations);
                long elapsed=System.nanoTime()-start,cpuElapsed=bean.getCurrentThreadCpuTime()-cpu,allocated=bean.getThreadAllocatedBytes(thread)-bytes;
                System.out.printf(Locale.ROOT,"MATRIX_COST version=%s round=%d ns_per_model=%.1f cpu_ns_per_model=%.1f bytes_per_model=%.1f synthetic-only%n",version==0?"accepted":"candidate",round,elapsed/(double)iterations,cpuElapsed/(double)iterations,allocated/(double)iterations);
            }
        }
    }
}
