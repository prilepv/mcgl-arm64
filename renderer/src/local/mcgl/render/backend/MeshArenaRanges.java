package local.mcgl.render.backend;

import java.util.Map;
import java.util.TreeMap;

/** First-fit independent vertex/index ranges. No producer geometry is retained. */
final class MeshArenaRanges {
    private final int capacity;
    private final TreeMap<Integer,Integer> free=new TreeMap<Integer,Integer>();
    private int used;
    MeshArenaRanges(int capacity){if(capacity<1)throw new IllegalArgumentException("Arena capacity");this.capacity=capacity;free.put(0,capacity);}
    int allocate(int count){
        if(count<1)throw new IllegalArgumentException("Arena allocation");
        for(Map.Entry<Integer,Integer> entry:free.entrySet())if(entry.getValue()>=count){
            int at=entry.getKey(),remaining=entry.getValue()-count;
            free.remove(at);if(remaining!=0)free.put(at+count,remaining);used+=count;return at;
        }
        return -1;
    }
    void release(int at,int count){
        if(at<0||count<1||(long)at+count>capacity)throw new IllegalArgumentException("Arena release");
        Map.Entry<Integer,Integer> previous=free.floorEntry(at),next=free.ceilingEntry(at);
        if(previous!=null&&(long)previous.getKey()+previous.getValue()>at||next!=null&&(long)at+count>next.getKey())throw new IllegalArgumentException("Overlapping arena release");
        int start=at,length=count;
        if(previous!=null&&previous.getKey()+previous.getValue()==at){start=previous.getKey();length+=previous.getValue();free.remove(previous.getKey());}
        if(next!=null&&(long)at+count==next.getKey()){length+=next.getValue();free.remove(next.getKey());}
        free.put(start,length);used-=count;
    }
    int used(){return used;}
}
