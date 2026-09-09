package local.mcgl.render;

import java.nio.ByteBuffer;
import java.util.*;

/** Bounded context-local reuse for transient UI/particle geometry. No CPU vertex data is retained. */
public final class GameDynamicMeshes {
    private static final int MAX_FORMATS=24, COPIES=3;
    private static final long MAX_BYTES=8L*1024*1024;
    private final MeshPipeline pipeline;
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<Key,Entry>(32,.75f,true);
    private long reserved,creations,bytes;
    private int meshes;
    public GameDynamicMeshes(MeshPipeline pipeline){this.pipeline=Objects.requireNonNull(pipeline);}
    /** Null means the single geometry exceeds the bounded cache; caller retains ordinary scoped ownership. */
    public Mesh acquire(MeshData data) {
        Key key=new Key(data);long cost=((long)data.layout().stride()*data.vertexCount()+(long)data.indices().count()*data.indices().type().bytes);
        if(cost>MAX_BYTES/COPIES)return null;
        Entry entry=entries.get(key);
        if(entry==null) {
            while(entries.size()>=MAX_FORMATS||reserved+cost*COPIES>MAX_BYTES) {
                Iterator<Entry> oldest=entries.values().iterator();Entry retired=oldest.next();oldest.remove();reserved-=retired.cost*COPIES;
                for(Mesh mesh:retired.ring)if(mesh!=null){mesh.close();meshes--;bytes-=retired.cost;}
            }
            entry=new Entry(cost);entries.put(key,entry);reserved+=cost*COPIES;
        }
        int index=entry.next;entry.next=(index+1)%COPIES;
        Mesh mesh=entry.ring[index];
        if(mesh==null){mesh=pipeline.create("game/transient",data,MeshPipeline.Usage.STREAM);entry.ring[index]=mesh;creations++;meshes++;bytes+=cost;}
        else mesh.updateVertices(0,data.vertices());
        return mesh;
    }
    public long creations(){return creations;}
    public int residentMeshes(){return meshes;}
    public long residentBytes(){return bytes;}
    /** Context destruction already owns native cleanup. Never issue GL calls after detach. */
    public void abandon(){entries.clear();reserved=bytes=0;meshes=0;}
    private static final class Entry {
        final Mesh[] ring=new Mesh[COPIES];final long cost;int next;
        Entry(long cost){this.cost=cost;}
    }
    private static final class Key {
        final VertexLayout layout;final int vertices,hash;final IndexData.Type type;final ByteBuffer indices;
        Key(MeshData data){layout=data.layout();vertices=data.vertexCount();type=data.indices().type();indices=data.indices().bytes();hash=((layout.hashCode()*31+vertices)*31+type.ordinal())*31+indices.hashCode();}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){if(!(other instanceof Key))return false;Key key=(Key)other;return vertices==key.vertices&&type==key.type&&layout.equals(key.layout)&&indices.equals(key.indices);}
    }
}
