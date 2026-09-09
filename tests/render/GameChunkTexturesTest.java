package local.mcgl.render.backend;

import java.util.*;

/** CPU-only table ownership, bounded slots and stable reuse. */
public final class GameChunkTexturesTest {
    private static int checks;
    public static void main(String[] ignored){
        GameChunkTextures pool=new GameChunkTextures();List<GameChunkTextures.Lease> leases=new ArrayList<GameChunkTextures.Lease>();
        GameChunkTextures.Lease inherited=pool.acquire(2,-1,new int[0],-1);leases.add(inherited);
        check(inherited.slot==0,"inherited binding is not captured as a texture name");
        for(int i=0;i<8;i++){GameChunkTextures.Lease lease=pool.acquire(2,-1,new int[0],100+i);leases.add(lease);check(lease.table==inherited.table&&lease.slot==i+1,"first bounded table slots");}
        GameChunkTextures.Lease ninth=pool.acquire(2,-1,new int[0],108);leases.add(ninth);
        check(ninth.table!=inherited.table&&pool.tableCount()==2,"ninth texture starts another bounded table");
        GameChunkTextures.Lease same=pool.acquire(2,-1,new int[0],103);leases.add(same);
        check(same.table==inherited.table&&same.slot==4,"existing live names reuse the exact slot");
        GameChunkTextures.Lease retired=leases.remove(4);pool.release(retired);
        check(same.table.textures[3]==103,"another source retains the slot through a rebuild");
        leases.remove(same);pool.release(same);
        check(inherited.table.textures[3]==-1,"last release frees only that slot");
        GameChunkTextures.Lease reuse=pool.acquire(2,-1,new int[0],109);leases.add(reuse);
        check(reuse.table==inherited.table&&reuse.slot==4,"new name reuses a retired slot without moving live slots");
        GameChunkTextures.Lease state=pool.acquire(2,-1,new int[]{-2884},100);leases.add(state);
        GameChunkTextures.Lease depth=pool.acquire(2,0,new int[0],100);leases.add(depth);
        GameChunkTextures.Lease pass=pool.acquire(0,-1,new int[0],100);leases.add(pass);
        check(state.table!=inherited.table&&depth.table!=inherited.table&&pass.table!=inherited.table,"state, depth and pass remain separate");
        boolean rejected=false;try{pool.release(same);}catch(IllegalStateException expected){rejected=true;}check(rejected&&reuse.table.textures[3]==109,"double release cannot retire a later occupant");
        for(GameChunkTextures.Lease lease:leases)pool.release(lease);
        check(pool.tableCount()==0,"all resident references retire their tables");
        System.out.println("GAME_CHUNK_TEXTURES_CPU_PASS checks="+checks);
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
