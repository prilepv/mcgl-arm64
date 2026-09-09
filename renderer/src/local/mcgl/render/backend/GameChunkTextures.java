package local.mcgl.render.backend;

import java.util.*;

/** Stable, bounded texture tables for resident chunk inputs. Slots never move while referenced. */
final class GameChunkTextures {
    static final int SIZE=8;
    private final Map<Key,List<Table>> tables=new HashMap<Key,List<Table>>();
    static final class Lease {
        final Table table;final int slot;boolean released;
        Lease(Table table,int slot){this.table=table;this.slot=slot;}
    }
    static final class Table {
        final Key key;final int[] textures=new int[SIZE],references=new int[SIZE];
        int inherited;
        Table(Key key){this.key=key;Arrays.fill(textures,-1);}
        int slot(int texture){
            if(texture==-1)return 0;
            for(int i=0;i<SIZE;i++)if(textures[i]==texture&&references[i]!=0)return i+1;
            return -1;
        }
        boolean empty(){if(inherited!=0)return false;for(int count:references)if(count!=0)return false;return true;}
    }
    private static final class Key {
        final int pass,depth;final int[] capabilities;
        Key(int pass,int depth,int[] capabilities){this.pass=pass;this.depth=depth;this.capabilities=capabilities.clone();}
        public int hashCode(){return (pass*31+depth)*31+Arrays.hashCode(capabilities);}
        public boolean equals(Object other){if(!(other instanceof Key))return false;Key key=(Key)other;return pass==key.pass&&depth==key.depth&&Arrays.equals(capabilities,key.capabilities);}
    }
    Lease acquire(int pass,int depth,int[] capabilities,int texture) {
        if(pass<0||pass>2||depth<-1||depth>1||texture<-1)throw new IllegalArgumentException("Chunk texture key");
        Key key=new Key(pass,depth,capabilities);List<Table> group=tables.computeIfAbsent(key,k->new ArrayList<Table>());
        // Prefer an existing name before assigning a free slot in another table.
        for(Table table:group){int slot=table.slot(texture);if(slot>=0)return retain(table,slot);}
        for(Table table:group)for(int i=0;i<SIZE;i++)if(table.references[i]==0){table.textures[i]=texture;return retain(table,i+1);}
        Table table=new Table(key);group.add(table);
        if(texture==-1)return retain(table,0);table.textures[0]=texture;return retain(table,1);
    }
    private Lease retain(Table table,int slot){if(slot==0)table.inherited++;else table.references[slot-1]++;return new Lease(table,slot);}
    void release(Lease lease) {
        if(lease.released)throw new IllegalStateException("Retired chunk texture lease");lease.released=true;
        Table table=lease.table;int count=lease.slot==0?--table.inherited:--table.references[lease.slot-1];
        if(count<0)throw new IllegalStateException("Retired chunk texture lease");
        if(lease.slot!=0&&count==0)table.textures[lease.slot-1]=-1;
        if(table.empty()){List<Table> group=tables.get(table.key);group.remove(table);if(group.isEmpty())tables.remove(table.key);}
    }
    void clear(){tables.clear();}
    int tableCount(){int count=0;for(List<Table> group:tables.values())count+=group.size();return count;}
}
