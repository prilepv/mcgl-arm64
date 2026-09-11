package local.mcgl.render;

import java.io.File;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.util.*;

/** Byte-exact comparison with a separately loaded, previously packaged renderer. No GPU or client. */
public final class GameGeometryEquivalenceTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        try(URLClassLoader baseline=new URLClassLoader(new URL[]{new File(args[0]).toURI().toURL()},null)) {
            Class<?> old=Class.forName("local.mcgl.render.GameGeometry",true,baseline);
            Method raw=old.getMethod("raw",int[].class,int.class,int.class,int.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class);
            Method floats=old.getMethod("floats",ByteBuffer.class,int.class,int.class);
            Random random=new Random(193);
            for(int mode=0;mode<=9;mode++)for(int vertices:new int[]{0,1,2,3,4,6,8,17,128,1024}) {
                if(mode==7&&vertices%4!=0||mode==8&&vertices%2!=0)continue;
                int[] words=new int[vertices*8];
                for(int i=0;i<words.length;i++)words[i]=i%8<5?Float.floatToRawIntBits((random.nextFloat()-.5f)*2048):random.nextInt();
                for(int flags=0;flags<16;flags++) {
                    boolean c=(flags&1)!=0,t=(flags&2)!=0,l=(flags&4)!=0,n=(flags&8)!=0;
                    compare(raw.invoke(null,words,words.length,vertices,mode,false,true,c,t,l,n),GameGeometry.raw(words,words.length,vertices,mode,false,true,c,t,l,n));
                }
                for(boolean masks:new boolean[]{false,true})for(boolean readOnly:new boolean[]{false,true}) {
                    ByteBuffer input=ByteBuffer.allocateDirect(vertices*60+16).order(ByteOrder.nativeOrder());
                    input.position(4);for(int i=0;i<vertices*15;i++)input.putFloat(i%17==0?-0f:(random.nextFloat()-.5f)*2048);input.limit(input.position());input.position(4);input.mark();
                    if(readOnly)input=input.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
                    int limit=input.limit(),mask=31|(masks?128:0);ByteBuffer bytes=input;
                    compare(floats.invoke(null,bytes,mode,mask),GameGeometry.floats(bytes,mode,mask));
                    check(bytes.position()==4&&bytes.limit()==limit,"producer position/limit retained");bytes.reset();check(bytes.position()==4,"producer mark retained");
                }
            }
            // Cross the unsigned-short index boundary with each expanded topology.
            for(int mode:new int[]{1,2,3,7,8}) {
                int vertices=mode==7?65540:mode==8?32772:32770;int[] words=new int[vertices*8];
                compare(raw.invoke(null,words,words.length,vertices,mode,false,true,true,true,true,true),GameGeometry.raw(words,words.length,vertices,mode,false,true,true,true,true,true));
            }
            ByteOrder opposite=ByteOrder.nativeOrder()==ByteOrder.BIG_ENDIAN?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN;
            for(ByteBuffer bad:new ByteBuffer[]{ByteBuffer.allocateDirect(60).order(opposite),ByteBuffer.allocate(60).order(ByteOrder.nativeOrder()),ByteBuffer.allocateDirect(59).order(ByteOrder.nativeOrder())}) {
                boolean oldRejected=false,newRejected=false;
                try{floats.invoke(null,bad,1,31);}catch(InvocationTargetException e){if(!(e.getCause() instanceof IllegalArgumentException))throw e;oldRejected=true;}
                try{GameGeometry.floats(bad,1,31);}catch(IllegalArgumentException expected){newRejected=true;}
                check(oldRejected&&newRejected,"word-copy input contract rejects non-native, heap, and incomplete buffers");
            }
        }
        System.out.println("GAME_GEOMETRY_EQUIVALENCE_PASS checks="+checks);
    }
    private static Object call(Object target,String name)throws Exception{return target.getClass().getMethod(name).invoke(target);}
    private static Object field(Object target,String name)throws Exception{return target.getClass().getField(name).get(target);}
    private static void compare(Object old,GameGeometry current)throws Exception {
        check(field(old,"attributeMask").equals(current.attributeMask),"material mask");
        check(field(old,"primitive").toString().equals(current.primitive.toString()),"primitive");
        Object mesh=field(old,"mesh"),layout=call(mesh,"layout");
        check(call(layout,"stride").equals(current.mesh.layout().stride()),"vertex stride");
        check(call(mesh,"vertexCount").equals(current.mesh.vertexCount()),"vertex count");
        List<?> fields=(List<?>)call(layout,"attributes");List<VertexLayout.Attribute> actual=current.mesh.layout().attributes();
        check(fields.size()==actual.size(),"attribute count");
        for(int i=0;i<fields.size();i++)for(String name:new String[]{"location","components","offset","normalized","storage"})
            check(field(fields.get(i),name).toString().equals(field(actual.get(i),name).toString()),"attribute "+name);
        check(((ByteBuffer)call(mesh,"vertices")).equals(current.mesh.vertices()),"exact vertex bytes");
        Object indices=call(mesh,"indices");check(call(indices,"type").toString().equals(current.mesh.indices().type().toString()),"index type");
        check(((ByteBuffer)call(indices,"bytes")).equals(current.mesh.indices().bytes()),"exact index bytes");
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
