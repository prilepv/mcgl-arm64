package local.mcgl.render;

import java.nio.*;
import java.util.*;

/** Appends split high-region/low-translation transforms without changing original shader inputs. */
public final class GameChunkGeometry {
    private GameChunkGeometry() {}
    public static ChunkMeshData withTransforms(ChunkMeshData source,Map<ChunkMaterial,float[]> transforms) {
        List<ChunkMeshData.Part> parts=new ArrayList<ChunkMeshData.Part>();
        for(ChunkMeshData.Part part:source.parts()) {
            float[] transform=transforms.get(part.material);
            if(transform==null){parts.add(part);continue;}
            if((transform.length!=7&&transform.length!=8)||transform[3]<=0)throw new IllegalArgumentException("Chunk batch transform");
            for(float value:transform)if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite chunk batch transform");
            if(transform.length==8&&(transform[7]!=(int)transform[7]||transform[7]<16||transform[7]>504||(((int)transform[7])&15)>8||(((int)transform[7])&16)==0))
                throw new IllegalArgumentException("Chunk texture slot/attribute mask");
            VertexLayout old=part.mesh.layout();List<VertexLayout.Attribute> attributes=new ArrayList<VertexLayout.Attribute>(old.attributes());
            attributes.add(new VertexLayout.Attribute(11,4,VertexLayout.Storage.FLOAT32,false,old.stride()));
            attributes.add(new VertexLayout.Attribute(12,3,VertexLayout.Storage.FLOAT32,false,old.stride()+16));
            if(transform.length==8)attributes.add(new VertexLayout.Attribute(13,1,VertexLayout.Storage.FLOAT32,false,old.stride()+28));
            VertexLayout layout=new VertexLayout(old.stride()+transform.length*4,attributes.toArray(new VertexLayout.Attribute[0]));
            ByteBuffer original=part.mesh.vertices(),bytes=ByteBuffer.allocateDirect(Math.multiplyExact(layout.stride(),part.mesh.vertexCount())).order(ByteOrder.nativeOrder());
            while(original.hasRemaining()){for(int i=0;i<old.stride();i++)bytes.put(original.get());for(float value:transform)bytes.putFloat(value);}bytes.flip();
            parts.add(new ChunkMeshData.Part(part.material,new MeshData(layout,bytes,part.mesh.indices()),part.faceWidth,part.centers(),part.bounds()));
        }
        return new ChunkMeshData(source.x,source.y,source.z,parts,source.vertexCount);
    }
}
