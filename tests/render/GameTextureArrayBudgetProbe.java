package local.mcgl.render.backend;

import java.nio.*;
import java.util.*;
import local.mcgl.render.GameRenderCommands;
import org.lwjgl.opengl.*;

/** Resource-limit checks use only disposable generated textures. */
public final class GameTextureArrayBudgetProbe {
    public static void run(GameRenderCommands g)throws Exception {
        java.lang.reflect.Field target=g.getClass().getDeclaredField("target");target.setAccessible(true);
        GameTextureArrays cache=((GameRenderer)target.get(g)).createTextureArrays();
        List<Integer> names=new ArrayList<Integer>();
        try{
            int keep=texture(g,32);names.add(keep);GameTextureArrays.Entry protectedEntry=cache.get(keep,null);
            if(protectedEntry==null)throw new AssertionError("protected source not admitted");
            GameTextureArrays.Page protectedPage=protectedEntry.page;
            for(int i=0;i<72;i++){int name=texture(g,64+i);names.add(name);if(cache.get(name,protectedPage)==null)throw new AssertionError("small page admission");checkBounds(cache,protectedEntry,keep);}
            int before=pageCount(cache);if(before!=32)throw new AssertionError("page count cap not exercised");
            for(int i=0;i<10;i++){int name=texture(g,512+i*16);names.add(name);if(cache.get(name,protectedPage)==null)throw new AssertionError("budgeted page admission");checkBounds(cache,protectedEntry,keep);}
            if(pageCount(cache)>=before)throw new AssertionError("byte limit not exercised");
            cache.bind(protectedPage);cache.close();
            if(GL11C.glIsTexture(protectedPage.texture)||pageCount(cache)!=0||bytes(cache)!=0)throw new AssertionError("explicit resource retirement");
            System.out.println("ARRAY_TEXTURE_BUDGET_PASS count-limit=32 byte-limit=67108864 protected-page/live-source/retirement");
        }finally{cache.close();for(int name:names)g.glDeleteTextures(name);}
    }
    private static void checkBounds(GameTextureArrays cache,GameTextureArrays.Entry expected,int name)throws Exception {
        if(pageCount(cache)>32||bytes(cache)>64L*1024*1024)throw new AssertionError("unbounded texture cache");
        if(cache.get(name,expected.page)!=expected||!GL11C.glIsTexture(expected.page.texture))throw new AssertionError("queued page was evicted");
    }
    private static int pageCount(GameTextureArrays cache)throws Exception{java.lang.reflect.Field f=GameTextureArrays.class.getDeclaredField("pages");f.setAccessible(true);return ((Set<?>)f.get(cache)).size();}
    private static long bytes(GameTextureArrays cache)throws Exception{java.lang.reflect.Field f=GameTextureArrays.class.getDeclaredField("bytes");f.setAccessible(true);return f.getLong(cache);}
    private static int texture(GameRenderCommands g,int size){int texture=g.glGenTextures();g.glBindTexture(3553,texture);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);ByteBuffer data=ByteBuffer.allocateDirect(size*size*4);while(data.hasRemaining())data.putInt(-1);data.flip();g.glTexImage2D(3553,0,32856,size,size,0,6408,5121,data);return texture;}
}
