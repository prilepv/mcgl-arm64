package local.mcgl.render.backend;

import java.nio.*;
import local.mcgl.render.*;

/** Ordered, bounded text runs. Per-glyph matrices stay on the GPU; no CPU transform approximation. */
final class GameTextBatch {
    private static final int MAX_GLYPHS=512, STRIDE=124;
    private static final int[] QUAD={0,1,2,0,2,3}, STRIP={0,1,2,2,1,3};
    private static final VertexLayout LAYOUT=new VertexLayout(STRIDE,
            a(0,3,0),a(1,4,12),a(2,2,28),a(3,2,36),a(5,4,44),
            a(8,4,60),a(9,4,76),a(10,4,92),a(11,4,108));
    private static final IndexData[] INDICES=new IndexData[6];
    static {
        for(int bucket=0;bucket<INDICES.length;bucket++) {
            int[] indices=new int[(16<<bucket)*6];for(int i=0;i<indices.length;i++)indices[i]=i;
            INDICES[bucket]=IndexData.of(indices);
        }
    }
    private final GameRenderer renderer;
    private final RenderContext context;
    private final ByteBuffer staging=ByteBuffer.allocateDirect(MAX_GLYPHS*6*STRIDE).order(ByteOrder.nativeOrder());
    private final GameDynamicMeshes streams;
    private ShaderProgram program;
    private int glyphs;
    private boolean flushing,reported;
    private long submittedGlyphs,submissions;
    GameTextBatch(GameRenderer renderer,RenderContext context) {
        this.renderer=renderer;this.context=context;streams=new GameDynamicMeshes(context.meshes());
    }
    private static VertexLayout.Attribute a(int location,int count,int offset) {
        return new VertexLayout.Attribute(location,count,VertexLayout.Storage.FLOAT32,false,offset);
    }
    boolean immediate(ByteBuffer input,int mode) {
        if((mode!=5&&mode!=7)||input.remaining()!=240)return false;
        for(int offset=input.position();offset<input.limit();offset+=4)
            if(!Float.isFinite(input.getFloat(offset)))throw new IllegalArgumentException("Non-finite text vertex");
        room();double[] matrix=renderer.state.matrices.modelView();int[] indices=mode==7?QUAD:STRIP;
        for(int i=0;i<6;i++) {
            int offset=input.position()+indices[i]*60;
            // Immediate text has resolved current attributes at each original vertex call.
            for(int word=0;word<11;word++)staging.putFloat(input.getFloat(offset+word*4));
            int provoking=input.position()+(mode==7?3:indices[i/3*3+2])*60+12;
            for(int word=0;word<4;word++)staging.putFloat(input.getFloat(provoking+word*4));
            matrix(matrix);
        }
        glyphs++;return true;
    }
    void glyph(float[] positionsAndUvs) {
        room();double[] matrix=renderer.state.matrices.modelView();
        float[] color=renderer.state.color(),lightmap=renderer.state.uv(1);
        for(int index:QUAD) {
            int at=index*5;for(int i=0;i<3;i++)staging.putFloat(positionsAndUvs[at+i]);
            for(float value:color)staging.putFloat(value);
            staging.putFloat(positionsAndUvs[at+3]).putFloat(positionsAndUvs[at+4]);
            staging.putFloat(lightmap[0]).putFloat(lightmap[1]);
            for(float value:color)staging.putFloat(value);
            matrix(matrix);
        }
        glyphs++;
    }
    private void matrix(double[] matrix){for(double value:matrix)staging.putFloat((float)value);}
    private void room(){if(glyphs==MAX_GLYPHS)flush();}
    void flush() {
        if(glyphs==0||flushing)return;
        int count=glyphs;glyphs=0;flushing=true;
        try {
            int bucket=0;while((16<<bucket)<count)bucket++;
            ByteBuffer upload=staging.duplicate().order(ByteOrder.nativeOrder());upload.position(0);upload.limit((16<<bucket)*6*STRIDE);
            Mesh mesh=streams.acquire(new MeshData(LAYOUT,upload,INDICES[bucket]));
            if(mesh==null)throw new IllegalStateException("Bounded text run exceeds stream budget");
            if(program==null)program=context.shaders().create(GameMaterialProgram.textSources());
            renderer.drawText(mesh,count*6,program);submissions++;submittedGlyphs+=count;
            if(!reported){reported=true;if(Boolean.getBoolean("mcgl.graphics.profile"))System.out.println("[MCGL Text Batch] ordered glyph runs; separate text shader; original glyph matrices; bounded streams");}
        } finally {staging.clear();flushing=false;}
    }
    void discard(){glyphs=0;staging.clear();}
    void abandon(){discard();streams.abandon();program=null;}
    long glyphs(){return submittedGlyphs;}
    long submissions(){return submissions;}
    long creations(){return streams.creations();}
    int meshes(){return streams.residentMeshes();}
    long bytes(){return streams.residentBytes();}
}
