package local.mcgl.render.backend;

import java.util.*;

/** Pure allocation bookkeeping: must not load LWJGL or a native library. */
public final class MeshArenaRangesTest {
    private static int checks;
    public static void main(String[] args){
        MeshArenaRanges ranges=new MeshArenaRanges(32);
        check(ranges.allocate(8)==0&&ranges.allocate(8)==8&&ranges.allocate(16)==16,"contiguous first-fit ranges");
        check(ranges.allocate(1)==-1,"full allocator has no implicit growth");
        ranges.release(8,8);ranges.release(0,8);check(ranges.allocate(16)==0,"left/right free ranges coalesce");
        ranges.release(16,16);ranges.release(0,16);check(ranges.used()==0&&ranges.allocate(32)==0,"complete coalescing restores original allocation");
        ranges.release(0,32);rejects(()->ranges.release(0,1));rejects(()->ranges.release(-1,1));rejects(()->ranges.release(31,2));rejects(()->ranges.allocate(0));
        Random random=new Random(184);List<int[]> live=new ArrayList<int[]>();boolean[] occupied=new boolean[257];MeshArenaRanges varied=new MeshArenaRanges(occupied.length);
        for(int step=0;step<12000;step++){
            if(!live.isEmpty()&&random.nextBoolean()){
                int[] old=live.remove(random.nextInt(live.size()));varied.release(old[0],old[1]);for(int i=old[0];i<old[0]+old[1];i++){check(occupied[i],"release owns every element");occupied[i]=false;}
            }else{
                int size=1+random.nextInt(65),expected=-1,run=0;
                for(int i=0;i<occupied.length;i++){run=occupied[i]?0:run+1;if(run==size){expected=i-size+1;break;}}
                int actual=varied.allocate(size);check(actual==expected,"first fit agrees with independent occupancy oracle");
                if(actual>=0){live.add(new int[]{actual,size});for(int i=actual;i<actual+size;i++){check(!occupied[i],"new range never aliases a live member");occupied[i]=true;}}
            }
            int used=0;for(boolean value:occupied)if(value)used++;check(varied.used()==used,"exact live capacity after each mutation");
        }
        for(int[] old:live)varied.release(old[0],old[1]);check(varied.used()==0&&varied.allocate(257)==0,"fragmented lifetimes recover the full page");
        System.out.println("MESH_ARENA_RANGES_PASS checks="+checks+" bounded/independent-ranges/coalescing/no-native");
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid range rejected");}
}
