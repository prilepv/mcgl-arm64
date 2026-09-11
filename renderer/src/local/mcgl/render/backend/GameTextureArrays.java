package local.mcgl.render.backend;

import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Bounded GPU-only replicas of compatible original 2D textures. No texel readback,
 * image resampling, atlas packing, mip generation or source texture mutation. */
final class GameTextureArrays {
    static final int UNIT=12,LAYERS=32;
    private static final int TARGET=GL30C.GL_TEXTURE_2D_ARRAY;
    private static final long LIMIT=64L*1024*1024;
    private final GameRasterState raster;
    private final Map<Integer,Entry> entries=new HashMap<Integer,Entry>();
    private final Set<Integer> rejected=new HashSet<Integer>(),renderTargets=new HashSet<Integer>();
    private final LinkedHashSet<Page> pages=new LinkedHashSet<Page>();
    private long bytes,copies,copyBytes,hits;
    private int copyFramebuffer,savedBinding=-1,bound,savedSampler;
    GameTextureArrays(GameRasterState raster){this.raster=raster;}
    boolean supportedState(){int active=GL13C.GL_TEXTURE0+raster.originalActiveTexture();GL13C.glActiveTexture(GL13C.GL_TEXTURE0);try{return GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING)==0;}finally{GL13C.glActiveTexture(active);}}
    static final class Entry {
        final int texture,layer;final Page page;final int[] dirty;
        Entry(int texture,int layer,Page page){this.texture=texture;this.layer=layer;this.page=page;dirty=new int[page.description.levels*4];Arrays.fill(dirty,-1);}
    }
    static final class Page {
        final int texture;final Description description;final Entry[] layers;final long bytes;
        Page(int texture,Description description,int layerCount){this.texture=texture;this.description=description;layers=new Entry[layerCount];bytes=description.bytes*layerCount;}
        int free(){for(int i=0;i<layers.length;i++)if(layers[i]==null)return i;return -1;}
    }
    private static final class Description {
        final int width,height,levels,format;final int[] parameters;final long bytes;
        Description(int width,int height,int levels,int format,int[] parameters){this.width=width;this.height=height;this.levels=levels;this.format=format;this.parameters=parameters.clone();long size=0;for(int level=0;level<levels;level++)size+=(long)Math.max(1,width>>level)*Math.max(1,height>>level)*4;bytes=size;}
        boolean same(Description d){return width==d.width&&height==d.height&&levels==d.levels&&format==d.format&&Arrays.equals(parameters,d.parameters);}
    }
    Entry get(int texture,Page protect){
        if(texture<=0||renderTargets.contains(texture))return null;
        int[] parameters=raster.originalTextureParameters(texture);if(parameters==null)return null;
        Entry entry=entries.get(texture);
        if(entry!=null&&!Arrays.equals(entry.page.description.parameters,parameters)){remove(texture);entry=null;}
        if(entry!=null){if(!copyDirty(entry)){remove(texture);reject(texture);return null;}touch(entry.page);hits++;return entry;}
        if(rejected.contains(texture))return null;
        int active=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        int sourceBinding=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try{
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,texture);Description description=describe(parameters);
            if(description==null){reject(texture);return null;}
            Page selected=null;int layer=-1;
            for(Page page:pages)if(page.description.same(description)&&(layer=page.free())>=0){selected=page;break;}
            if(selected==null){
                int layers=(int)Math.min(LAYERS,Math.max(2,(16L*1024*1024)/description.bytes));if((long)layers*description.bytes>LIMIT){reject(texture);return null;}
                long required=description.bytes*layers;
                while(required>LIMIT-bytes||pages.size()>=32){Page victim=null;for(Page page:pages)if(page!=protect){victim=page;break;}if(victim==null){return null;}retire(victim);}
                selected=create(description,layers);if(selected==null){reject(texture);return null;}pages.add(selected);bytes+=selected.bytes;layer=0;
            }
            entry=new Entry(texture,layer,selected);
            for(int level=0;level<description.levels;level++){int at=level*4;entry.dirty[at]=entry.dirty[at+1]=0;entry.dirty[at+2]=Math.max(1,description.width>>level);entry.dirty[at+3]=Math.max(1,description.height>>level);}
            if(!copyDirty(entry)){reject(texture);return null;}
            selected.layers[layer]=entry;entries.put(texture,entry);touch(selected);return entry;
        }finally{GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,sourceBinding);GL13C.glActiveTexture(active);}
    }
    private void reject(int texture){if(rejected.size()>=4096)rejected.clear();rejected.add(texture);}
    private void touch(Page page){pages.remove(page);pages.add(page);}
    private Description describe(int[] parameters){
        int base=GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D,GL12C.GL_TEXTURE_BASE_LEVEL),maximum=GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D,GL12C.GL_TEXTURE_MAX_LEVEL);
        if(base!=0||maximum<0)return null;
        if(GL11C.glGetTexParameterf(GL11C.GL_TEXTURE_2D,GL14C.GL_TEXTURE_LOD_BIAS)!=0||GL11C.glGetTexParameterf(GL11C.GL_TEXTURE_2D,GL12C.GL_TEXTURE_MIN_LOD)!=-1000||GL11C.glGetTexParameterf(GL11C.GL_TEXTURE_2D,GL12C.GL_TEXTURE_MAX_LOD)!=1000)return null;
        if(GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D,GL14C.GL_TEXTURE_COMPARE_MODE)!=GL11C.GL_NONE)return null;
        for(int i=0;i<4;i++)if(GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_SWIZZLE_R+i)!=new int[]{GL11C.GL_RED,GL11C.GL_GREEN,GL11C.GL_BLUE,GL11C.GL_ALPHA}[i])return null;
        if(GL.getCapabilities().GL_EXT_texture_filter_anisotropic&&GL11C.glGetTexParameterf(GL11C.GL_TEXTURE_2D,EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT)!=1)return null;
        // The game exposes repeat, mirror and edge clamp. Border-color sampling is not admitted.
        for(int i=2;i<4;i++)if(parameters[i]!=GL11C.GL_REPEAT&&parameters[i]!=GL12C.GL_CLAMP_TO_EDGE&&parameters[i]!=GL14C.GL_MIRRORED_REPEAT)return null;
        int width=GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,0,GL11C.GL_TEXTURE_WIDTH),height=GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,0,GL11C.GL_TEXTURE_HEIGHT),format=GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,0,GL11C.GL_TEXTURE_INTERNAL_FORMAT);
        if(width<1||height<1||width>4096||height>4096||(format!=GL11C.GL_RGBA8&&format!=GL11C.GL_RGB8&&format!=GL11C.GL_RGBA&&format!=GL11C.GL_RGB))return null;
        boolean mipmap=parameters[0]!=GL11C.GL_NEAREST&&parameters[0]!=GL11C.GL_LINEAR;
        int levels=mipmap?Math.min(maximum,31-Integer.numberOfLeadingZeros(Math.max(width,height)))+1:1;
        for(int level=0;level<levels;level++)if(GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,level,GL11C.GL_TEXTURE_WIDTH)!=Math.max(1,width>>level)||GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,level,GL11C.GL_TEXTURE_HEIGHT)!=Math.max(1,height>>level)||GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,level,GL11C.GL_TEXTURE_INTERNAL_FORMAT)!=format)return null;
        return new Description(width,height,levels,format,parameters);
    }
    private Page create(Description d,int layers){
        int active=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);GL13C.glActiveTexture(GL13C.GL_TEXTURE0+UNIT);int previous=GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY),unpack=GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int texture=GL11C.glGenTextures();boolean ready=false;
        try{
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER,0);GL11C.glBindTexture(TARGET,texture);
            for(int level=0;level<d.levels;level++){int width=Math.max(1,d.width>>level),height=Math.max(1,d.height>>level);GL12C.glTexImage3D(TARGET,level,GL11C.GL_RGBA8,width,height,layers,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,(ByteBuffer)null);if(GL11C.glGetTexLevelParameteri(TARGET,level,GL11C.GL_TEXTURE_WIDTH)!=width)return null;}
            for(int i=0;i<4;i++)GL11C.glTexParameteri(TARGET,new int[]{10241,10240,10242,10243}[i],d.parameters[i]);GL11C.glTexParameteri(TARGET,GL12C.GL_TEXTURE_MAX_LEVEL,d.levels-1);ready=true;return new Page(texture,d,layers);
        }finally{GL11C.glBindTexture(TARGET,previous);GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER,unpack);GL13C.glActiveTexture(active);if(!ready)GL11C.glDeleteTextures(texture);}
    }
    private boolean copyDirty(Entry entry){
        boolean dirty=false;for(int level=0;level<entry.page.description.levels;level++)dirty|=entry.dirty[level*4]>=0;if(!dirty)return true;
        int active=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);GL13C.glActiveTexture(GL13C.GL_TEXTURE0+UNIT);int previous=GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY),read=GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        if(copyFramebuffer==0)copyFramebuffer=GL30C.glGenFramebuffers();
        try{
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,copyFramebuffer);GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);GL11C.glBindTexture(TARGET,entry.page.texture);
            for(int level=0;level<entry.page.description.levels;level++){int at=level*4;if(entry.dirty[at]<0)continue;
                GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,entry.texture,level);
                if(GL30C.glCheckFramebufferStatus(GL30C.GL_READ_FRAMEBUFFER)!=GL30C.GL_FRAMEBUFFER_COMPLETE)return false;
                int x=entry.dirty[at],y=entry.dirty[at+1],w=entry.dirty[at+2]-x,h=entry.dirty[at+3]-y;
                GL12C.glCopyTexSubImage3D(TARGET,level,x,y,entry.layer,x,y,w,h);copies++;copyBytes+=(long)w*h*4;Arrays.fill(entry.dirty,at,at+4,-1);
            }
            return true;
        }finally{GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,0,0);GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,read);GL11C.glBindTexture(TARGET,previous);GL13C.glActiveTexture(active);}
    }
    void changed(int texture,int level,int x,int y,int width,int height){
        rejected.remove(texture);Entry entry=entries.get(texture);if(entry==null||level<0||level>=entry.page.description.levels||width<=0||height<=0)return;
        int maxWidth=Math.max(1,entry.page.description.width>>level),maxHeight=Math.max(1,entry.page.description.height>>level);if(x<0||y<0||(long)x+width>maxWidth||(long)y+height>maxHeight)return;
        int at=level*4;if(entry.dirty[at]<0){entry.dirty[at]=x;entry.dirty[at+1]=y;entry.dirty[at+2]=x+width;entry.dirty[at+3]=y+height;}
        else {entry.dirty[at]=Math.min(entry.dirty[at],x);entry.dirty[at+1]=Math.min(entry.dirty[at+1],y);entry.dirty[at+2]=Math.max(entry.dirty[at+2],x+width);entry.dirty[at+3]=Math.max(entry.dirty[at+3],y+height);}
    }
    void remove(int texture){Entry entry=entries.remove(texture);if(entry!=null)entry.page.layers[entry.layer]=null;rejected.remove(texture);}
    void deleted(int texture){remove(texture);renderTargets.remove(texture);}
    void renderTarget(int texture){if(texture>0){remove(texture);renderTargets.add(texture);}}
    void bind(Page page){if(savedBinding>=0&&bound==page.texture)return;int active=GL13C.GL_TEXTURE0+raster.originalActiveTexture();GL13C.glActiveTexture(GL13C.GL_TEXTURE0+UNIT);try{if(savedBinding<0){savedBinding=bound=GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY);savedSampler=GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);if(savedSampler!=0)GL33C.glBindSampler(UNIT,0);}if(bound!=page.texture){GL11C.glBindTexture(TARGET,page.texture);bound=page.texture;}}finally{GL13C.glActiveTexture(active);}}
    void end(){if(savedBinding<0)return;int active=GL13C.GL_TEXTURE0+raster.originalActiveTexture();GL13C.glActiveTexture(GL13C.GL_TEXTURE0+UNIT);try{if(bound!=savedBinding)GL11C.glBindTexture(TARGET,savedBinding);if(savedSampler!=0)GL33C.glBindSampler(UNIT,savedSampler);}finally{savedBinding=-1;bound=0;savedSampler=0;GL13C.glActiveTexture(active);}}
    private void retire(Page page){if(bound==page.texture)end();pages.remove(page);bytes-=page.bytes;for(Entry e:page.layers)if(e!=null)entries.remove(e.texture);GL11C.glDeleteTextures(page.texture);}
    void close(){end();while(!pages.isEmpty())retire(pages.iterator().next());if(copyFramebuffer!=0)GL30C.glDeleteFramebuffers(copyFramebuffer);copyFramebuffer=0;entries.clear();rejected.clear();renderTargets.clear();}
    /** Context destruction owns native deletion. This path never calls OpenGL. */
    void abandon(){if(Boolean.getBoolean("mcgl.graphics.profile"))System.out.println("[MCGL Terrain Arrays] pages="+pages.size()+" bytes="+bytes+" entries="+entries.size()+" hits="+hits+" gpu_copies="+copies+" copied_bytes="+copyBytes);entries.clear();rejected.clear();renderTargets.clear();pages.clear();bytes=0;copyFramebuffer=0;savedBinding=-1;bound=0;savedSampler=0;}
}
