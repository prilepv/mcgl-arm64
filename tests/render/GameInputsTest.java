package local.mcgl.render;

import java.lang.reflect.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** CPU contract tests for game transforms, scoped inputs, topology and shader source migration. */
public final class GameInputsTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        matrices(); state(); geometry(); shaders(Paths.get(args[0]));
        System.out.println("GAME_INPUTS_CPU_PASS checks=" + checks);
    }
    private static void matrices() {
        GameMatrices m = new GameMatrices(); m.translate(4, 5, 6); m.scale(2, 3, 4);
        equal(GameMatrices.transform(m.modelView(), new float[] {1, 2, 3, 1}), new float[] {6, 11, 18, 1}, "postmultiply translation/scale");
        equal(GameMatrices.normal(m.modelView()), new float[] {.5f,0,0,0,1f/3,0,0,0,.25f}, "inverse transpose nonuniform scale");
        m.push(); m.rotate(90, 0, 0, 1);
        equal(GameMatrices.transform(m.modelView(), new float[] {1,0,0,1}), new float[] {4,8,6,1}, "rotation handedness and composition");
        float[] normal = GameMatrices.normal(m.modelView()); near(normal[1], 1f/3, "rotated normal"); m.pop();
        double[] snapshot = m.modelView(); snapshot[12] = -99; near((float)m.modelView()[12], 4, "matrix snapshot immutable");
        GameMatrices scoped=new GameMatrices();scoped.translate(2,0,0);scoped.push();scoped.translate(3,0,0);
        scoped.mode(5890);scoped.textureUnit(1);scoped.translate(0,7,0);GameMatrices.Snapshot scope=scoped.snapshot();
        scoped.loadIdentity();scoped.push();scoped.mode(5888);scoped.pop();scoped.loadIdentity();scope.restore(scoped);
        check(scoped.mode()==5890,"matrix scope restores active mode");near((float)scoped.get(2984)[13],7,"matrix scope restores active texture stack");
        scoped.mode(5888);near((float)scoped.modelView()[12],5,"matrix scope restores removed top");scoped.pop();near((float)scoped.modelView()[12],2,"matrix scope restores lower stack contents");
        scope.restore(scoped);scoped.mode(5890);scoped.textureUnit(1);near((float)scoped.texture(1)[13],7,"matrix snapshot can be reused");
        m.mode(5889); m.ortho(0, 100, 60, 0, -1, 1);
        equal(GameMatrices.transform(m.projection(), new float[] {0,0,0,1}), new float[] {-1,1,0,1}, "GUI top left");
        equal(GameMatrices.transform(m.projection(), new float[] {100,60,0,1}), new float[] {1,-1,0,1}, "GUI bottom right");
        m.mode(5890); m.textureUnit(1); m.scale(1d/256,1d/256,1); m.translate(8,8,8);
        equal(GameMatrices.transform(m.texture(1), new float[] {240,0,0,1}), new float[] {248f/256,8f/256,8,1}, "lightmap matrix");
        near((float)m.texture(0)[0], 1, "texture stacks independent"); near((float)m.modelView()[12], 4, "model stack independent");
        m.loadIdentity(); fails(() -> m.pop(), "matrix underflow"); fails(() -> m.mode(0), "unknown matrix mode");
        fails(() -> m.scale(Double.NaN,1,1), "nonfinite matrix rejected");
        FloatBuffer buffer = ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder()).asFloatBuffer(); buffer.position(2);
        for (int i=0;i<16;i++) buffer.put(2+i,i); m.load(GameMatrices.read(buffer)); check(buffer.position()==2, "matrix buffer preserved");
        near((float)m.get(2984)[15],15,"matrix reads buffer-relative");
    }
    private static void state() {
        GameRenderState s = new GameRenderState(); Capture p = new Capture();
        s.bind(p.program, 31); near(p.number("uAlphaFunction"),0,"alpha default off"); near(p.number("uTextured"),0,"texture default off");
        s.enable(3553,true);s.enable(3008,true);s.alpha(516,.6f);s.enable(2912,true);s.fog(2917,9729);s.fog(2915,5);s.fog(2916,20);
        s.color(.2f,.3f,.4f,.5f); GameRenderState.Snapshot current = s.snapshot(1);
        s.color(1,0,0,1);s.enable(3553,false);s.matrices.translate(9,0,0);current.restore(s);
        equal(s.color(),new float[]{.2f,.3f,.4f,.5f},"current attribute group restores color");check(!s.enabled(3553),"current group leaves texture enable");
        near((float)s.matrices.modelView()[12],9,"attributes do not restore matrix contents");
        GameRenderState.Snapshot fog = s.snapshot(0x80);s.fog(2916,100);s.shade(7424);fog.restore(s);s.bind(p.program,1);
        near(p.number("uGameFog.end"),20,"fog scope");near(p.number("uFlat"),1,"fog leaves lighting group shade");
        GameRenderState.Snapshot lighting=s.snapshot(0x40);s.shade(7425);s.fog(2916,120);lighting.restore(s);s.bind(p.program,1);
        near(p.number("uFlat"),1,"lighting scope restores shade");near(p.number("uGameFog.end"),120,"lighting leaves fog");
        s.enable(2896,true);s.enable(16384,true);s.enable(2903,true);s.colorMaterial(1032,5634);s.color(.1f,.2f,.3f,.4f);
        s.matrices.loadIdentity();s.matrices.rotate(90,0,0,1);s.light(0,4611,new float[]{1,0,0,0});s.matrices.loadIdentity();s.bind(p.program,31);
        equal((float[])p.values.get("uGameLight0.position"),new float[]{0,1,0,0},"light position transformed at setter time");
        equal((float[])p.values.get("uGameMaterial.diffuse"),s.color(),"tracked diffuse material");
        GameRenderState.Snapshot enables=s.snapshot(0x2000);s.enable(2896,false);s.enable(3008,false);enables.restore(s);check(s.enabled(2896)&&s.enabled(3008),"enable group restores inputs");
        s.bind(p.program,1);near(p.number("uAlphaFunction"),5,"alpha greater maps explicitly");
        s.activeUnit(1);s.enable(3553,true);s.uv(1,240,224);s.activeUnit(0);s.bind(p.program,1);near(p.number("uLightmapped"),1,"independent lightmap enable");
        fails(()->s.activeUnit(4),"unsupported texture unit");fails(()->s.light(2,4611,new float[4]),"unknown light");
        fails(()->s.alpha(200,0),"unknown alpha function");fails(()->s.fog(1,1),"unknown fog parameter");
    }
    private static void geometry() {
        for (int mode=0;mode<=9;mode++) {
            int count=mode==7?8:mode==8?6:7;int[] indices=GameGeometry.indices(mode,count);
            for(int index:indices)check(index>=0&&index<count,"topology index bounds "+mode);
        }
        check(Arrays.equals(GameGeometry.indices(5,5),new int[]{0,1,2,2,1,3,2,3,4}),"strip winding and provoking vertex");
        int[] raw=new int[32];for(int v=0;v<4;v++){raw[v*8]=Float.floatToRawIntBits(v);raw[v*8+5]=0x12340000+v;raw[v*8+6]=0x00010203+v;}
        GameGeometry g=GameGeometry.raw(raw,32,4,7,false,true,true,false,false,true);
        check(g.mesh.layout().stride()==40&&g.attributeMask==(1|2|16|32|64),"quad flat inputs added explicitly");
        ByteBuffer b=g.mesh.vertices();for(int v=0;v<4;v++){check(b.getInt(v*40+32)==raw[29],"fourth vertex flat color");check((b.getInt(v*40+36)&0x00ffffff)==raw[30],"fourth vertex flat normal");}
        ShortBuffer quadIndices=g.mesh.indices().bytes().asShortBuffer();int[] expectedIndices={0,1,2,0,2,3};
        for(int i=0;i<expectedIndices.length;i++)check(quadIndices.get(i)==expectedIndices[i],"original quad diagonal retained");
        raw[0]=Float.floatToRawIntBits(100);near(g.mesh.vertices().getFloat(0),0,"raw geometry snapshot");
        fails(()->GameGeometry.raw(raw,31,4,7,false,true,true,false,false,true),"bad words");
        fails(()->GameGeometry.raw(raw,32,4,7,true,true,true,false,false,true),"historical conversion rejected");
        fails(()->GameGeometry.indices(7,5),"partial quad rejected");
        int[] line=new int[16];line[0]=Float.floatToRawIntBits(-1);line[8]=Float.floatToRawIntBits(1);line[5]=0xff00ff00;line[13]=0xffff0000;
        GameGeometry strip=GameGeometry.raw(line,16,2,1,false,true,true,false,false,false);
        check(strip.primitive==Mesh.Primitive.TRIANGLES&&strip.mesh.vertexCount()==4&&strip.mesh.indices().count()==6&&(strip.attributeMask&512)!=0,"Core lines use explicit indexed strips");
        check(strip.mesh.layout().stride()==60&&strip.mesh.vertices().getFloat(32)==1&&strip.mesh.vertices().getFloat(2*60+32)==-1,"line endpoints carry their projected partner");
        for(int v=0;v<4;v++)check(strip.mesh.vertices().getInt(v*60+52)==line[13],"flat line color is its last endpoint");
    }
    private static void shaders(Path directory)throws Exception{
        int count=0;try(DirectoryStream<Path> paths=Files.newDirectoryStream(directory)){
            for(Path path:paths){String name=path.getFileName().toString();if(!name.endsWith(".vert")&&!name.endsWith(".frag"))continue;
                String original=new String(Files.readAllBytes(path),StandardCharsets.UTF_8),source=GameShaderSource.migrate(name.endsWith(".vert")?35633:35632,original);
                check(source.startsWith("#version 410 core\n"),"Core shader "+name);check(!source.contains("gl_ModelView")&&!source.contains("gl_FragColor")&&!source.contains("texture2D("),"removed builtins "+name);count++;
            }
        }check(count==26,"all original 26 effect stages inspected");
        String comments=GameShaderSource.migrate(35633,"// gl_Unknown\nvoid main(){/* gl_Unknown */gl_Position=gl_Vertex;}");
        check(!comments.contains("gl_Unknown"),"comments do not trigger unsupported symbols");
        fails(()->GameShaderSource.migrate(35633,"void main(){gl_Position=gl_Unknown;}"),"unsupported builtin fails closed");
        fails(()->GameShaderSource.migrate(35633,"/*broken"),"unterminated comment");
        fails(()->GameShaderSource.migrate(35632,"vec4 color;"),"missing fragment entry");
        String primary="uniform sampler2D colorMap;void main(){gl_FragColor=texture2D(colorMap,vec2(0.5));}";
        check(GameShaderSource.chunkTexturesSupported(primary),"two-argument primary sampler is table-compatible");
        String table=GameShaderSource.migrate(35632,primary,true);
        check(table.contains("mcglTexture2D(colorMap,")&&table.contains("textureGrad(uGameChunkTexture8,uv,dx,dy)"),"primary lookup uses bounded constant-sampler branches with explicit gradients");
        check(!table.contains("uGameChunkTexture["),"no non-uniform sampler-array indexing");
        check(!GameShaderSource.migrate(35632,primary).contains("uniform sampler2D uGameChunkTexture"),"ordinary effects do not activate unused table samplers");
        check(!GameShaderSource.chunkTexturesSupported("uniform sampler2D colorMap;void main(){gl_FragColor=texture2D(colorMap,vec2(.5),1.0);}"),"biased sampler keeps ordinary lookup");
        check(!GameShaderSource.chunkTexturesSupported("uniform sampler2D colorMap;void main(){gl_FragColor=texture2DProj(colorMap,vec3(.5));}"),"unrecognized projective effect keeps ordinary lookup");
        check(!GameShaderSource.chunkTexturesSupported("uniform sampler2D colorMap;vec4 sample(sampler2D s){return texture2D(s,vec2(.5));}void main(){gl_FragColor=sample(colorMap);}"),"sampler forwarding keeps ordinary lookup");
    }
    private static final class Capture {
        final Map<String,Object> values=new HashMap<String,Object>();
        final ShaderProgram program=(ShaderProgram)Proxy.newProxyInstance(GameInputsTest.class.getClassLoader(),new Class[]{ShaderProgram.class},(o,m,a)->{
            if(m.getName().equals("findUniform")) { final String name=(String)a[0];return Proxy.newProxyInstance(GameInputsTest.class.getClassLoader(),new Class[]{ShaderUniform.class},(u,method,args)->{
                if(method.getName().startsWith("set")){
                    if(args[0] instanceof FloatBuffer){FloatBuffer buffer=((FloatBuffer)args[0]).duplicate();float[] v=new float[buffer.remaining()];buffer.get(v);values.put(name,v);}
                    else if(args.length==1)values.put(name,args[0]);else{float[] v=new float[args.length];for(int i=0;i<v.length;i++)v[i]=(Float)args[i];values.put(name,v);}return null;
                }throw new AssertionError(method);
            });}throw new AssertionError(m);
        });
        float number(String name){return ((Number)values.get(name)).floatValue();}
    }
    private static void equal(float[] a,float[] b,String message){check(a.length==b.length,message+" length");for(int i=0;i<a.length;i++)near(a[i],b[i],message+"["+i+"]");}
    private static void near(float a,float b,String message){check(Math.abs(a-b)<.00001f,message+" actual="+a+" expected="+b);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void fails(Runnable r,String message){boolean failed=false;try{r.run();}catch(IllegalArgumentException|IllegalStateException expected){failed=true;}check(failed,message);}
}
