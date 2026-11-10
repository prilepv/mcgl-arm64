package local.mcgl.render.backend;

import java.nio.*;
import local.mcgl.render.*;

/** One GPU storage format for the original raw triangle/fan and quad streams.
 * Original bytes and indices are unchanged. Only otherwise-unused flat inputs
 * are appended to non-quads; their existing material mask keeps them disabled. */
final class GameTerrainFormat {
    static final VertexLayout LAYOUT=GameGeometry.RAW_QUAD_LAYOUT;
    static MeshData storage(MeshData source){
        if(!source.layout().equals(ChunkMeshBuilder.LAYOUT))return source;
        ByteBuffer input=source.vertices(),output=ByteBuffer.allocateDirect(Math.multiplyExact(source.vertexCount(),40)).order(ByteOrder.nativeOrder());
        for(int vertex=0;vertex<source.vertexCount();vertex++){
            int at=input.position()+vertex*32;
            for(int word=0;word<8;word++)output.putInt(input.getInt(at+word*4));
            output.putInt(input.getInt(at+20));
            output.put(input.get(at+24)).put(input.get(at+25)).put(input.get(at+26)).put((byte)0);
        }
        output.flip();return MeshData.takeOwnedVertices(LAYOUT,output,source.indices());
    }
}
