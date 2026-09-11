package local.mcgl.render.tests;

import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Actual shared-page drawing and matrix-array ownership, against independently
 * drawn reference meshes. No game classes, client account or world are loaded. */
public final class MeshArenaProbe {
    private static int checks;
    private static MeshArena retainedArena;
    private static Mesh retainedMember;
    private static ShaderProgram retainedProgram;
    private static ShaderUniform retainedPalette;
    private static final String VERTEX="#version 410 core\nlayout(location=0) in vec3 p;layout(location=1) in vec4 color;layout(location=14) in float tag;uniform mat4 palette[32];out vec4 c;void main(){gl_Position=palette[int(tag)]*vec4(p,1);c=color;}";
    private static final String FRAGMENT="#version 410 core\nin vec4 c;out vec4 outputColor;void main(){outputColor=c;}";
    public static int run(RenderContext context)throws Exception{
        checks=0;MeshPipeline pipeline=context.meshes();
        rejects(()->pipeline.createArena("bad label",8,12,4096));rejects(()->pipeline.createArena("bad/count",0,12,4096));rejects(()->pipeline.createArena("bad/bytes",8,12,321L*1024*1024));
        ShaderProgram program=context.shaders().create(new ShaderSources("arena/palette",VERTEX,FRAGMENT));ShaderUniform palette=program.uniform("palette");
        check(palette.type()==ShaderUniform.Type.MAT4_ARRAY&&program.uniformNames().contains("palette"),"bounded matrix array has an explicit uniform type");
        FloatBuffer matrices=buffer(32*64+16).asFloatBuffer();matrices.position(2);matrices.limit(32*16+2);for(int tag=0;tag<32;tag++)for(int i=0;i<16;i++)matrices.put(2+tag*16+i,i%5==0?1:0);
        int previousProgram=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);palette.setMatrix4Array(matrices);palette.setMatrix4Array(matrices);
        check(matrices.position()==2&&matrices.limit()==514&&GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)==previousProgram,"array upload retains caller cursor and current program");
        rejects(()->palette.setMatrix4Array(FloatBuffer.allocate(512)));rejects(()->palette.setMatrix4Array(buffer(64).asFloatBuffer()));rejects(()->palette.setMatrix4(buffer(64).asFloatBuffer()));rejects(()->palette.setFloat(1));
        rejects(()->context.shaders().create(new ShaderSources("arena/large-array",VERTEX.replace("[32]","[33]"),FRAGMENT)));
        int sentinelVao=GL30C.glGenVertexArrays(),sentinel=GL15C.glGenBuffers();GL30C.glBindVertexArray(sentinelVao);GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,sentinel);GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,sentinel);GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER,sentinel);
        MeshArena arena=pipeline.createArena("arena/test",8,12,512);MeshData red=data(255,0,0,128),blue=data(0,0,255,128);
        Mesh a=arena.create("arena/red",red),b=arena.create("arena/blue",blue),c=arena.create("arena/other-page",red);
        check(a!=null&&b!=null&&c!=null&&arena.residentPages()==2&&arena.residentBytes()==416,"fixed-size pages stay inside byte budget");
        check(arena.compatible(a,b)&&!arena.compatible(a,c)&&arena.residentMembers()==3,"only common GPU pages are compatible");
        check(GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)==sentinelVao&&GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING)==sentinel&&GL11C.glGetInteger(GL31C.GL_COPY_READ_BUFFER)==sentinel&&GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER)==sentinel,"arena allocation and uploads preserve all caller bindings");
        Mesh d=arena.create("arena/fourth",blue);check(d!=null&&arena.create("arena/no-space",red)==null,"budget exhaustion returns a clean standalone-fallback signal");
        check(a.layout().stride()==20&&a.indexType()==IndexData.Type.UINT32&&a.usage()==MeshPipeline.Usage.STATIC,"tagged member keeps exact original fields and wide local indices");
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);GL11C.glDisable(GL11C.GL_CULL_FACE);GL11C.glEnable(GL11C.GL_BLEND);GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA,GL11C.GL_ONE_MINUS_SRC_ALPHA);program.bind();
        clear();a.draw(Mesh.Primitive.TRIANGLES);b.draw(Mesh.Primitive.TRIANGLES);byte[] reference=pixels();
        try(Mesh plainA=pipeline.create("arena/reference-red",red,MeshPipeline.Usage.STATIC);Mesh plainB=pipeline.create("arena/reference-blue",blue,MeshPipeline.Usage.STATIC)){
            clear();plainA.draw(Mesh.Primitive.TRIANGLES);plainB.draw(Mesh.Primitive.TRIANGLES);check(Arrays.equals(reference,pixels()),"shared storage preserves independently uploaded original vertex/index bytes");
        }
        check((reference[(32*64+32)*4]&255)>0&&(reference[(32*64+32)*4+2]&255)>0,"reference renders both colored layers, not an empty frame");
        clear();arena.draw(Mesh.Primitive.TRIANGLES,Arrays.asList(a,b));check(Arrays.equals(reference,pixels()),"multi-draw matches independent ordered alpha compositing");
        clear();arena.draw(Mesh.Primitive.TRIANGLES,Arrays.asList(b,a));check(!Arrays.equals(reference,pixels()),"reversing the supplied order changes transparency, without hidden sorting");
        clear();a.draw(Mesh.Primitive.TRIANGLES);b.draw(Mesh.Primitive.TRIANGLES);a.draw(Mesh.Primitive.TRIANGLES);reference=pixels();clear();arena.draw(Mesh.Primitive.TRIANGLES,Arrays.asList(a,b,a));check(Arrays.equals(reference,pixels()),"intentional repeated members are preserved");
        matrices.put(2+arena.tag(a)*16+12,-.4f);matrices.put(2+arena.tag(b)*16+12,.4f);palette.setMatrix4Array(matrices);
        clear();a.draw(Mesh.Primitive.TRIANGLES);b.draw(Mesh.Primitive.TRIANGLES);reference=pixels();clear();arena.draw(Mesh.Primitive.TRIANGLES,Arrays.asList(a,b));check(Arrays.equals(reference,pixels()),"each member uses its own exact matrix-array element");
        rejects(()->arena.draw(Mesh.Primitive.TRIANGLES,Arrays.asList(a,c)));rejects(()->arena.draw(Mesh.Primitive.TRIANGLES,Collections.nCopies(257,a)));rejects(()->a.updateVertices(0,red.vertices()));rejects(()->a.updateIndices(red.indices()));
        try(Mesh standalone=pipeline.create("arena/foreign-standalone",red,MeshPipeline.Usage.STATIC)){rejects(()->arena.tag(standalone));rejects(()->arena.draw(Mesh.Primitive.TRIANGLES,Arrays.asList(a,standalone)));}
        try(MeshArena other=pipeline.createArena("arena/other",8,12,512)){Mesh value=other.create("arena/other-member",red);rejects(()->arena.compatible(a,value));rejects(()->other.tag(a));}
        rejects(()->arena.create("arena/duplicate-tag",new MeshData(a.layout(),buffer(0),IndexData.of())));
        try(MeshArena small=pipeline.createArena("arena/bounds",3,6,512)){check(small.create("arena/too-many-vertices",red)==null&&small.residentPages()==0,"oversized members allocate no partial page");}
        wideIndices(pipeline,program,palette,matrices);
        rebuildSpare(pipeline,red);
        imports(pipeline,program,palette,matrices);
        partialPage(pipeline,red);
        packedTags(context);
        foreign(()->{rejects(()->arena.tag(a));rejects(()->arena.draw(Mesh.Primitive.TRIANGLES,Arrays.asList(a,b)));rejects(()->arena.create("arena/foreign-thread",red));rejects(arena::close);rejects(a::close);rejects(()->palette.setMatrix4Array(matrices));});
        long created=arena.pageCreations();a.close();a.close();Mesh replacement=arena.create("arena/reused-hole",red);
        check(arena.pageCreations()==created&&arena.residentMembers()==4&&!b.isClosed()&&replacement!=null,"retiring one member reuses its range without touching neighbors or creating a page");
        rejects(()->arena.tag(a));rejects(()->a.draw(Mesh.Primitive.TRIANGLES));
        // Generic mesh combination remains valid even when a source is a page slice.
        try(Mesh combined=pipeline.combine("arena/combined",Arrays.asList(replacement,b),IndexData.of(0,1,2,0,2,3,4,5,6,4,6,7))){
            clear();replacement.draw(Mesh.Primitive.TRIANGLES);b.draw(Mesh.Primitive.TRIANGLES);reference=pixels();clear();combined.draw(Mesh.Primitive.TRIANGLES);check(Arrays.equals(reference,pixels()),"explicit combination reads only each live slice, with its actual vertex offset");
        }
        b.close();replacement.close();c.close();d.close();check(arena.residentPages()==0&&arena.residentBytes()==0&&arena.residentMembers()==0,"last members release every GPU page");arena.close();arena.close();rejects(()->arena.create("arena/closed",red));
        GL11C.glDisable(GL11C.GL_BLEND);program.close();GL30C.glBindVertexArray(0);GL30C.glDeleteVertexArrays(sentinelVao);GL15C.glDeleteBuffers(sentinel);
        retainedArena=pipeline.createArena("arena/retained",8,12,512);retainedMember=retainedArena.create("arena/retained-member",red);retainedProgram=context.shaders().create(new ShaderSources("arena/retained-program",VERTEX,FRAGMENT));retainedPalette=retainedProgram.uniform("palette");
        check(GL11C.glGetError()==0,"shared pages and matrix arrays are Core-valid");System.out.println("MESH_ARENA_GPU_PASS checks="+checks);return checks;
    }
    public static int verifyRetired(){int before=checks;check(retainedArena.isClosed()&&retainedMember.isClosed()&&retainedProgram.isClosed(),"context destruction invalidates live arenas, member views and palettes");rejects(()->retainedMember.draw(Mesh.Primitive.TRIANGLES));rejects(()->retainedArena.residentBytes());rejects(()->retainedPalette.setMatrix4Array(buffer(2048).asFloatBuffer()));retainedMember.close();retainedArena.close();retainedProgram.close();return checks-before;}
    private static void packedTags(RenderContext context){
        VertexLayout layout=new VertexLayout(20,new VertexLayout.Attribute(0,3,VertexLayout.Storage.FLOAT32,false,0),new VertexLayout.Attribute(1,4,VertexLayout.Storage.UINT8,true,12));
        rejects(()->new MeshArena.Tags(0));rejects(()->new MeshArena.Tags(33));rejects(()->new MeshArena.Tags(256));
        rejects(()->new MeshArena.Tags(128,layout,12));rejects(()->new MeshArena.Tags(128,layout,20));rejects(()->new MeshArena.Tags(128,null,19));
        ByteBuffer bytes=buffer(80),source=data(180,70,140,128).vertices();
        for(int v=0;v<4;v++){for(int b=0;b<16;b++)bytes.put(source.get(v*16+b));bytes.putInt(0xa1b2c3d4);}bytes.flip();
        MeshData input=new MeshData(layout,bytes,IndexData.quads(4));
        int previousProgram=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),previousRead=GL11C.glGetInteger(GL31C.GL_COPY_READ_BUFFER);
        try(MeshArena arena=context.meshes().createArena("arena/packed",1024,1536,32768,new MeshArena.Tags(128,layout,19));
                ShaderProgram shader=context.shaders().create(new ShaderSources("arena/packed-palette",VERTEX.replace("palette[32]","uOriginalModelPalette[128]").replace("palette[int(tag)]","uOriginalModelPalette[int(tag)]"),FRAGMENT));
                Mesh reference=context.meshes().create("arena/packed-reference",input,MeshPipeline.Usage.STATIC)){
            FloatBuffer matrix=buffer(128*64).asFloatBuffer();for(int tag=0;tag<128;tag++)for(int i=0;i<16;i++)matrix.put(tag*16+i,i%5==0?1:0);shader.uniform("uOriginalModelPalette").setMatrix4Array(matrix);shader.bind();
            List<Mesh> members=new ArrayList<Mesh>();for(int i=0;i<130;i++){Mesh member=arena.create("arena/packed-member",input);members.add(member);check(arena.tag(member)==i%128&&member.layout().stride()==20,"byte tags cover all 128 slots without changing source stride");}
            clear();for(int i=0;i<130;i++)reference.draw(Mesh.Primitive.TRIANGLES);byte[] expected=pixels();clear();arena.draw(Mesh.Primitive.TRIANGLES,members);check(Arrays.equals(expected,pixels()),"packed tags preserve independently drawn ordered pixels");
            int vbo=GL20C.glGetVertexAttribi(0,GL20C.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,vbo);ByteBuffer uploaded=buffer(130*80);GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER,0,uploaded);
            for(int member=0;member<130;member++)for(int v=0;v<4;v++)for(int b=0;b<20;b++)check(uploaded.get(member*80+v*20+b)==(b==19?(byte)(member%128):bytes.get(v*20+b)),"only the explicitly unused byte changes during upload");
            for(Mesh member:members)member.close();Mesh imported=arena.importMesh("arena/packed-import",reference,1024);check(imported!=null&&imported.layout().stride()==20,"immutable import uses the same compact tag format");
            clear();reference.draw(Mesh.Primitive.TRIANGLES);expected=pixels();clear();imported.draw(Mesh.Primitive.TRIANGLES);check(Arrays.equals(expected,pixels()),"compact import preserves original geometry");
        }finally{GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,previousRead);GL20C.glUseProgram(previousProgram);}
    }
    private static void rebuildSpare(MeshPipeline pipeline,MeshData data){
        try(MeshArena arena=pipeline.createArena("arena/rebuild-spare",8,12,512)){
            Mesh stable=arena.create("arena/stable",data),current=arena.create("arena/rebuilt",data);
            for(int i=0;i<34;i++){Mesh replacement=arena.create("arena/staged",data);check(replacement!=null&&!current.isClosed()&&!stable.isClosed(),"staged replacement leaves old/shared members alive");current.close();current=replacement;}
            check(arena.pageCreations()==2&&arena.residentPages()==2&&arena.residentMembers()==2,"full-page atomic rebuilds reuse a bounded spare without GPU page churn");
            // End on the original full page, leaving one spare. A new layout may
            // reclaim that idle capacity instead of falling back prematurely.
            ByteBuffer vertices=buffer(4*24);for(int i=0;i<4;i++)vertices.putFloat(0).putFloat(0).putFloat(0).putInt(-1).putFloat(0).putFloat(0);vertices.flip();
            Mesh different=arena.create("arena/other-layout",new MeshData(VertexFormats.TEXTURED,vertices,IndexData.quads(4)));
            check(different!=null&&arena.residentBytes()<=512&&arena.residentPages()==2,"another layout evicts only the idle page before consuming the hard budget");
            current.close();stable.close();different.close();check(arena.residentBytes()==0&&arena.residentPages()==0,"unloading all members releases the spare too");
        }
    }
    private static void wideIndices(MeshPipeline pipeline,ShaderProgram program,ShaderUniform palette,FloatBuffer restore){
        FloatBuffer identity=buffer(2048).asFloatBuffer();for(int tag=0;tag<32;tag++)for(int i=0;i<16;i++)identity.put(tag*16+i,i%5==0?1:0);palette.setMatrix4Array(identity);program.bind();
        MeshData[] sources=new MeshData[2];
        for(int n=0;n<2;n++){ByteBuffer vertices=buffer(70000*16),quad=data(n==0?255:0,0,n==1?255:0,128).vertices();vertices.position(65536*16);vertices.put(quad);vertices.position(0);sources[n]=new MeshData(VertexFormats.POSITION_COLOR,vertices,IndexData.of(65536,65537,65538,65536,65538,65539));}
        try(MeshArena arena=pipeline.createArena("arena/wide",140000,12,4L*1024*1024);Mesh a=pipeline.create("arena/wide-reference-a",sources[0],MeshPipeline.Usage.STATIC);Mesh b=pipeline.create("arena/wide-reference-b",sources[1],MeshPipeline.Usage.STATIC)){
            Mesh first=arena.create("arena/wide-a",sources[0]),second=arena.create("arena/wide-b",sources[1]);
            clear();a.draw(Mesh.Primitive.TRIANGLES);b.draw(Mesh.Primitive.TRIANGLES);byte[] reference=pixels();clear();arena.draw(Mesh.Primitive.TRIANGLES,Arrays.asList(first,second));
            check(Arrays.equals(reference,pixels()),"UINT32 source indices plus nonzero base vertices above 65535 preserve exact pixels");
            clear();arena.draw(Mesh.Primitive.TRIANGLES,Collections.singletonList(second));byte[] sparse=pixels();clear();b.draw(Mesh.Primitive.TRIANGLES);check(Arrays.equals(sparse,pixels()),"sparse member selection never draws unused shared storage");
            first.close();second.close();
            Mesh importedA=arena.importMesh("arena/wide-import-a",a,4L*1024*1024),importedB=arena.importMesh("arena/wide-import-b",b,4L*1024*1024);
            check(importedA!=null&&importedB!=null&&importedA.indexType()==IndexData.Type.UINT32,"bounded import preserves original wide indices");
            clear();arena.draw(Mesh.Primitive.TRIANGLES,Arrays.asList(importedA,importedB));check(Arrays.equals(reference,pixels()),"imported UINT32 indices and nonzero base vertices retain exact pixels");
        }finally{palette.setMatrix4Array(restore);}
    }
    private static void partialPage(MeshPipeline pipeline,MeshData data){
        try(MeshArena arena=pipeline.createArena("arena/partial-tail",8,12,312)){
            Mesh a=arena.create("arena/partial-a",data),b=arena.create("arena/partial-b",data),c=arena.create("arena/partial-c",data);
            check(a!=null&&b!=null&&c!=null&&arena.residentPages()==2&&arena.residentBytes()==312,"remaining budget becomes one useful partial page");
            check(arena.compatible(a,b)&&!arena.compatible(a,c)&&arena.create("arena/partial-full",data)==null,"partial page remains independently bounded");
            long creations=arena.pageCreations();c.close();Mesh replacement=arena.create("arena/partial-reuse",data);
            check(replacement!=null&&arena.pageCreations()==creations,"partial-page spare is reused without allocation churn");
        }
    }
    private static void imports(MeshPipeline pipeline,ShaderProgram program,ShaderUniform palette,FloatBuffer restore)throws Exception{
        FloatBuffer identity=buffer(2048).asFloatBuffer();for(int tag=0;tag<32;tag++)for(int i=0;i<16;i++)identity.put(tag*16+i,i%5==0?1:0);palette.setMatrix4Array(identity);program.bind();
        MeshData red=data(255,0,0,128),blue=data(0,0,255,128);
        try(MeshArena arena=pipeline.createArena("arena/import",8,12,208);
                Mesh a=pipeline.create("arena/import-source-a",red,MeshPipeline.Usage.STATIC);
                Mesh b=pipeline.create("arena/import-source-b",blue,MeshPipeline.Usage.STATIC)){
            rejects(()->arena.importMesh("bad label",a,1024));rejects(()->arena.importMesh("arena/bad-budget",a,0));rejects(()->arena.importMesh("arena/big-budget",a,4L*1024*1024+1));
            try(Mesh mutable=pipeline.create("arena/import-mutable",red,MeshPipeline.Usage.DYNAMIC)){rejects(()->arena.importMesh("arena/mutable",mutable,1024));}
            foreign(()->rejects(()->arena.importMesh("arena/foreign-thread",a,1024)));
            check(arena.importMesh("arena/too-large",a,75)==null&&arena.residentPages()==0,"transfer cap is checked before destination allocation/readback");
            clear();a.draw(Mesh.Primitive.TRIANGLES);b.draw(Mesh.Primitive.TRIANGLES);byte[] reference=pixels();
            int vao=GL30C.glGenVertexArrays(),sentinel=GL15C.glGenBuffers();
            try{
                GL30C.glBindVertexArray(vao);GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,sentinel);GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,sentinel);GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER,sentinel);
                Mesh importedA=arena.importMesh("arena/import-a",a,1024),importedB=arena.importMesh("arena/import-b",b,1024);
                check(importedA!=null&&importedB!=null&&!a.isClosed()&&!b.isClosed(),"import leaves source ownership and lifetime with caller");
                check(GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)==vao&&GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING)==sentinel
                        &&GL11C.glGetInteger(GL31C.GL_COPY_READ_BUFFER)==sentinel&&GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER)==sentinel,"import preserves every caller VAO/buffer binding");
                check(arena.importMesh("arena/import-full",a,1024)==null&&arena.residentMembers()==2,"full arena imports nothing and leaves sources alive");
                check(arena.importMesh("arena/already-pooled",importedA,1024)==null,"tagged arena members are not imported twice");
                a.close();b.close();clear();arena.draw(Mesh.Primitive.TRIANGLES,Arrays.asList(importedA,importedB));
                check(Arrays.equals(reference,pixels()),"imported independent geometry survives source deletion with exact ordered alpha pixels");
                rejects(()->arena.importMesh("arena/retired-source",a,1024));
            }finally{GL30C.glBindVertexArray(0);GL30C.glDeleteVertexArrays(vao);GL15C.glDeleteBuffers(sentinel);}
        }finally{palette.setMatrix4Array(restore);}
    }
    private static MeshData data(int r,int g,int b,int a){ByteBuffer bytes=buffer(64);for(float[] xy:new float[][]{{-.8f,-.8f},{.8f,-.8f},{.8f,.8f},{-.8f,.8f}})bytes.putFloat(xy[0]).putFloat(xy[1]).putFloat(0).put((byte)r).put((byte)g).put((byte)b).put((byte)a);bytes.flip();return new MeshData(VertexFormats.POSITION_COLOR,bytes,IndexData.quads(4));}
    private static ByteBuffer buffer(int bytes){return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());}
    private static void clear(){GL11C.glClearColor(0,0,0,1);GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);}
    private static byte[] pixels(){ByteBuffer bytes=buffer(64*64*4);GL11C.glReadPixels(0,0,64,64,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,bytes);byte[] result=new byte[bytes.remaining()];bytes.get(result);return result;}
    private static void foreign(Runnable action)throws Exception{AtomicReference<Throwable> failure=new AtomicReference<Throwable>();Thread thread=new Thread(()->{try{action.run();}catch(Throwable error){failure.set(error);}},"arena owner check");thread.start();thread.join(2500);check(!thread.isAlive()&&failure.get()==null,"foreign owner guards: "+failure.get());}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}check(rejected,"invalid arena/palette operation rejected");}
}
