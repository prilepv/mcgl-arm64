package local.mcgl.render.backend;

import java.nio.*;
import java.util.*;
import local.mcgl.render.*;

/** CPU-only byte preservation and storage compatibility, including non-quad flat shading inputs. */
public final class GameTerrainFormatTest {
    private static int checks;
    public static void main(String[] ignored){
        Random random=new Random(186);
        for(int mode:new int[]{4,5,6,7,8})for(int run=0;run<32;run++){
            int count=4*(run+1),mask=run&15;int[] words=new int[count*8];
            for(int v=0;v<count;v++){
                for(int p=0;p<5;p++)words[v*8+p]=Float.floatToRawIntBits(random.nextFloat()*32-16);
                words[v*8+5]=random.nextInt();words[v*8+6]=random.nextInt();words[v*8+7]=random.nextInt();
            }
            GameGeometry original=GameGeometry.raw(words,words.length,count,mode,false,true,(mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0);
            MeshData source=original.mesh,result=GameTerrainFormat.storage(source);
            check(result.layout().equals(GameTerrainFormat.LAYOUT),"all raw triangle/quad layouts share storage");
            check(result.indices()==source.indices()&&result.vertexCount()==source.vertexCount(),"indices and expanded topology are untouched");
            if(mode==7||mode==8){check(result==source,"ordinary quads need no extra allocation");continue;}
            ByteBuffer before=source.vertices(),after=result.vertices();int bp=before.position(),ap=after.position();
            for(int v=0;v<count;v++){
                for(int b=0;b<32;b++)check(before.get(bp+v*32+b)==after.get(ap+v*40+b),"every original raw byte is preserved");
                check(after.getInt(ap+v*40+32)==before.getInt(bp+v*32+20),"unused flat color is well-defined");
                for(int b=0;b<3;b++)check(after.get(ap+v*40+36+b)==before.get(bp+v*32+24+b),"unused flat normal is well-defined");
                check(after.get(ap+v*40+39)==0,"storage padding is deterministic");
            }
            check(before.position()==bp&&after.position()==ap,"input/output cursors remain unchanged");
            check((original.attributeMask&(32|64))==0,"non-quads still disable appended flat attributes");
        }
        System.out.println("GAME_TERRAIN_FORMAT_CPU_PASS checks="+checks);
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
