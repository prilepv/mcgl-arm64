package local.mcgl.render.tests;

import java.io.File;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import local.mcgl.render.GameMatrices;

/** Compare transforms and complete stack snapshots against an explicit accepted renderer JAR. */
public final class GameMatricesRegressionTest {
    private static int checks;
    static final class Version {
        final Class<?> type; final Object value;
        Object saved;
        Version(Class<?> type)throws Exception{this.type=type;value=type.getConstructor().newInstance();}
        Object call(String name,Class<?>[] types,Object... args)throws Exception {
            try{return type.getMethod(name,types).invoke(value,args);}
            catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
        }
        void operation(String name,double... numbers)throws Exception{
            Class<?>[] types=new Class<?>[numbers.length];Arrays.fill(types,double.class);
            Object[] args=new Object[numbers.length];for(int i=0;i<args.length;i++)args[i]=numbers[i];call(name,types,args);
        }
        void mode(int mode,int unit)throws Exception {call("mode",new Class[]{int.class},mode);call("textureUnit",new Class[]{int.class},unit);}
        void save()throws Exception{saved=call("snapshot",new Class[0]);}
        void restore()throws Exception{saved.getClass().getMethod("restore",type).invoke(saved,value);}
        void matrix(String name,double[] matrix)throws Exception{call(name,new Class[]{double[].class},(Object)matrix);}
        double[] read(String name)throws Exception{return (double[])call(name,new Class[0]);}
        double[] texture(int unit)throws Exception{return (double[])call("texture",new Class[]{int.class},unit);}
    }
    static void same(double[] a,double[] b){
        if(a.length!=b.length)throw new AssertionError("Matrix length");
        for(int i=0;i<a.length;i++){checks++;if(Double.doubleToRawLongBits(a[i])!=Double.doubleToRawLongBits(b[i]))throw new AssertionError("Matrix bits differ at "+i+": "+a[i]+" / "+b[i]);}
    }
    static void compare(Version a,Version b)throws Exception{
        same(a.read("modelView"),b.read("modelView"));same(a.read("projection"),b.read("projection"));
        for(int i=0;i<4;i++)same(a.texture(i),b.texture(i));
        if(!a.call("mode",new Class[0]).equals(b.call("mode",new Class[0])))throw new AssertionError("Mode differs");
        double[] matrix=a.read("modelView");float[] before=(float[])a.type.getMethod("normal",double[].class).invoke(null,(Object)matrix),after=GameMatrices.normal(matrix);
        float[] reused=new float[9];GameMatrices.normal(matrix,reused);
        for(int i=0;i<9;i++){checks++;if(Float.floatToRawIntBits(before[i])!=Float.floatToRawIntBits(after[i])||Float.floatToRawIntBits(before[i])!=Float.floatToRawIntBits(reused[i]))throw new AssertionError("Normal bits differ");}
        float[] input={.25f,-.5f,1.5f,1};float[] expected=(float[])a.type.getMethod("transform",double[].class,float[].class).invoke(null,matrix,input);
        float[] output=new float[4];GameMatrices.transform(matrix,input,output);GameMatrices.transform(matrix,input,input);
        for(int i=0;i<4;i++){checks++;if(Float.floatToRawIntBits(expected[i])!=Float.floatToRawIntBits(output[i])||Float.floatToRawIntBits(expected[i])!=Float.floatToRawIntBits(input[i]))throw new AssertionError("Vector transform bits differ");}
    }
    static void rejected(Version version,String name,double[] matrix)throws Exception{
        double[] before=version.read("modelView");
        try{version.matrix(name,matrix);throw new AssertionError("Invalid transform accepted");}catch(IllegalArgumentException expected){}
        same(before,version.read("modelView"));
    }
    public static void main(String[] args)throws Exception{
        try(URLClassLoader loader=new URLClassLoader(new URL[]{new File(args[0]).toURI().toURL()},null)){
            Version a=new Version(Class.forName("local.mcgl.render.GameMatrices",true,loader)),b=new Version(GameMatrices.class);
            Random random=new Random(175200);int[] depths={1,1,1,1,1,1},savedDepths=depths.clone();int mode=5888,unit=0,savedMode=mode,savedUnit=unit;
            a.save();b.save();
            for(int step=0;step<12000;step++){
                int op=random.nextInt(10),stack=mode==5888?0:mode==5889?1:unit+2;
                switch(op){
                    case 0:case 1:case 2:
                        String name=op==0?"translate":op==1?"scale":"rotate";
                        double[] values=op==2?new double[]{random.nextDouble()*720-360,random.nextDouble(),random.nextDouble(),random.nextDouble()}
                            :op==0?new double[]{random.nextInt(1<<25)-(1<<24),random.nextDouble()*1000,-0.0}
                            :new double[]{random.nextDouble()*2-1,random.nextDouble()*2-1,random.nextDouble()*2-1};
                        a.operation(name,values);b.operation(name,values);break;
                    case 3:mode=5888+random.nextInt(3);unit=random.nextInt(4);a.mode(mode,unit);b.mode(mode,unit);break;
                    case 4:case 5:
                        boolean push=op==4?depths[stack]<12:depths[stack]==1;String operation=push?"push":"pop";
                        a.call(operation,new Class[0]);b.call(operation,new Class[0]);depths[stack]+=push?1:-1;break;
                    case 6:a.call("loadIdentity",new Class[0]);b.call("loadIdentity",new Class[0]);break;
                    case 7:
                        double[] value=GameMatrices.identity();for(int i=0;i<16;i++)value[i]+=(random.nextDouble()-.5)*.1;
                        a.matrix("multiply",value);b.matrix("multiply",value);break;
                    case 8:a.save();b.save();savedDepths=depths.clone();savedMode=mode;savedUnit=unit;break;
                    case 9:a.restore();b.restore();depths=savedDepths.clone();mode=savedMode;unit=savedUnit;break;
                }
                compare(a,b);
            }
            for(int step=0;step<100;step++){
                a.mode(5889,0);b.mode(5889,0);a.call("loadIdentity",new Class[0]);b.call("loadIdentity",new Class[0]);
                double left=-random.nextDouble()*100-1,right=random.nextDouble()*100+1;
                double[] bounds={left,right,-.5,512.25,-128,4096};a.operation("ortho",bounds);b.operation("ortho",bounds);compare(a,b);
            }
            for(Version version:new Version[]{a,b}){
                version.mode(5888,0);double[] value=GameMatrices.identity();value[12]=Double.MAX_VALUE;version.matrix("load",value);
                double[] bad=GameMatrices.identity();bad[0]=Double.NaN;rejected(version,"load",bad);rejected(version,"multiply",bad);
                bad=GameMatrices.identity();bad[12]=Double.MAX_VALUE;rejected(version,"multiply",bad);
                value=GameMatrices.identity();version.matrix("load",value);value[0]=99;same(version.read("modelView"),GameMatrices.identity());
                value=version.read("modelView");value[0]=99;same(version.read("modelView"),GameMatrices.identity());
            }
            GameMatrices candidate=(GameMatrices)b.value;double[] target=new double[16];candidate.copyModelView(target);same(target,candidate.modelView());target[0]=99;same(candidate.modelView(),GameMatrices.identity());
            for(int i=0;i<4;i++){candidate.copyTexture(i,target);same(target,candidate.texture(i));}
            candidate.copyProjection(target);same(target,candidate.projection());
            System.out.println("MATRIX_REGRESSION_PASS exactValues="+checks+" snapshots/pool-reuse/rollback/input-output-isolation");
        }
    }
}
