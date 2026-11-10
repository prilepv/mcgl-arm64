package local.mcgl.render.backend;

import java.util.*;

/** Mutation/read oracle across growth, colliding names, replacement and removal. */
public final class GameModelIndexTest {
    public static void main(String[] args){
        GameRenderer.ModelIndex index=new GameRenderer.ModelIndex();Map<Integer,GameRenderer.Model> reference=new HashMap<Integer,GameRenderer.Model>();
        Random random=new Random(0x4d43474c);int checks=0;
        for(int i=0;i<24000;i++){GameRenderer.Model value=new GameRenderer.Model();int key=i*4096;index.put(key,value);reference.put(key,value);}
        for(int round=0;round<100000;round++){
            int key=random.nextBoolean()?random.nextInt(24000)*4096:random.nextInt();
            switch(random.nextInt(4)){
                case 0:case 1:GameRenderer.Model value=new GameRenderer.Model();index.put(key,value);reference.put(key,value);break;
                case 2:if(index.remove(key)!=reference.remove(key))throw new AssertionError("removed model differs");break;
                default:if(index.get(key)!=reference.get(key))throw new AssertionError("model lookup differs");
            }checks++;
        }
        for(Map.Entry<Integer,GameRenderer.Model> entry:reference.entrySet()){if(index.get(entry.getKey())!=entry.getValue())throw new AssertionError("growth lost model");checks++;}
        index.clear();for(int key:reference.keySet()){if(index.get(key)!=null)throw new AssertionError("stale model after clear");checks++;}
        for(int key:new int[]{0,Integer.MIN_VALUE,Integer.MAX_VALUE,-1,1}){GameRenderer.Model value=new GameRenderer.Model();index.put(key,value);if(index.get(key)!=value||index.remove(key)!=value||index.get(key)!=null)throw new AssertionError("boundary name");checks++;}
        System.out.println("GAME_MODEL_INDEX_PASS checks="+checks);
    }
}
