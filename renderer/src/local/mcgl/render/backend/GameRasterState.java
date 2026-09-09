package local.mcgl.render.backend;

import java.nio.*;
import java.util.*;
import org.lwjgl.opengl.*;

/** Core-valid raster state with group-aware CPU snapshots, replacing native attribute stacks. */
final class GameRasterState {
    private final Map<String, Entry> values = new LinkedHashMap<String, Entry>();
    private int activeTexture;
    private final int[] textures=new int[4];
    private static final int[] TEXTURE_PARAMETERS={10241,10240,10242,10243};
    private final Map<Integer,int[]> textureParameters=new HashMap<Integer,int[]>();
    private final int whiteTexture;
    private int substituted;
    private int[] savedChunkTextures,chunkTextureBindings;
    private boolean cullEnabled,polygonOffsetFill,lineScope;
    private boolean depthWrite;
    private final Set<Integer> enabled=new HashSet<Integer>();
    private float lineWidth;
    private int viewportWidth,viewportHeight;
    private static final class Entry {
        final String key; final int groups; final Runnable apply;
        Entry(String key, int groups, Runnable apply) { this.key=key;this.groups=groups;this.apply=apply; }
    }
    private void remember(String key,int group,Runnable command,boolean apply) {
        values.put(key,new Entry(key,group,command));if(apply)command.run();
    }
    GameRasterState() {
        for(int cap:new int[]{3042,2884,2929,2960,3089,32823,2848,3024,3058,32925,32926,32927,32928,34370}) {
            final boolean on=GL11C.glIsEnabled(cap);if(on)enabled.add(cap);if(cap==2884)cullEnabled=on;if(cap==32823)polygonOffsetFill=on;remember("enable/"+cap,enableGroup(cap)|0x2000,()->nativeEnable(cap,on),false);
        }
        final int[] viewport=integers(GL11C.GL_VIEWPORT,4);viewportWidth=viewport[2];viewportHeight=viewport[3];remember("viewport",0x800,()->applyViewport(viewport[0],viewport[1],viewport[2],viewport[3]),false);
        final float[] range=floats(GL11C.GL_DEPTH_RANGE,2);remember("depthRange",0x800,()->GL11C.glDepthRange(range[0],range[1]),false);
        final int depth=GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);remember("depthFunc",0x100,()->GL11C.glDepthFunc(depth),false);
        final boolean depthMask=GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);depthWrite=depthMask;remember("depthMask",0x100,()->nativeDepthMask(depthMask),false);
        final float clearDepth=GL11C.glGetFloat(GL11C.GL_DEPTH_CLEAR_VALUE);remember("clearDepth",0x100,()->GL11C.glClearDepth(clearDepth),false);
        final int src=GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB),dst=GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        final int srcA=GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA),dstA=GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        remember("blendFunc",0x4000,()->GL14C.glBlendFuncSeparate(src,dst,srcA,dstA),false);
        final int equation=GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB),equationA=GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA);
        remember("blendEquation",0x4000,()->GL20C.glBlendEquationSeparate(equation,equationA),false);
        final int logic=GL11C.glGetInteger(GL11C.GL_LOGIC_OP_MODE);remember("logicOp",0x4000,()->GL11C.glLogicOp(logic),false);
        final float[] clearColor=floats(GL11C.GL_COLOR_CLEAR_VALUE,4);remember("clearColor",0x4000,()->GL11C.glClearColor(clearColor[0],clearColor[1],clearColor[2],clearColor[3]),false);
        final int[] colorMask=integers(GL11C.GL_COLOR_WRITEMASK,4);remember("colorMask",0x4000,()->GL11C.glColorMask(colorMask[0]!=0,colorMask[1]!=0,colorMask[2]!=0,colorMask[3]!=0),false);
        final int cull=GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE),front=GL11C.glGetInteger(GL11C.GL_FRONT_FACE);
        remember("cullFace",8,()->GL11C.glCullFace(cull),false);remember("frontFace",8,()->GL11C.glFrontFace(front),false);
        final float factor=GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_FACTOR),units=GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_UNITS);
        remember("polygonOffset",8,()->GL11C.glPolygonOffset(factor,units),false);
        final float line=GL11C.glGetFloat(GL11C.GL_LINE_WIDTH);lineWidth=line;remember("lineWidth",4,()->lineWidth=line,false);
        final int stencilClear=GL11C.glGetInteger(GL11C.GL_STENCIL_CLEAR_VALUE);remember("clearStencil",0x400,()->GL11C.glClearStencil(stencilClear),false);
        final int stencilFunc=GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC),ref=GL11C.glGetInteger(GL11C.GL_STENCIL_REF),mask=GL11C.glGetInteger(GL11C.GL_STENCIL_VALUE_MASK);
        remember("stencilFunc",0x400,()->GL11C.glStencilFunc(stencilFunc,ref,mask),false);
        final int fail=GL11C.glGetInteger(GL11C.GL_STENCIL_FAIL),zfail=GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_FAIL),pass=GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_PASS);
        remember("stencilOp",0x400,()->GL11C.glStencilOp(fail,zfail,pass),false);
        activeTexture=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)-GL13C.GL_TEXTURE0;
        final int initialTexture=activeTexture;
        for(int unit=0;unit<4;unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);final int texture=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D),u=unit;
            textures[unit]=texture;remember("texture/"+unit,0x40000,()->texture(u,texture),false);rememberTexture(texture);
        }
        // Both material samplers remain active after linking, even when their CPU enable is off.
        // A complete neutral object avoids sampling incomplete object zero on macOS drivers.
        whiteTexture=GL11C.glGenTextures();GL13C.glActiveTexture(GL13C.GL_TEXTURE0);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,whiteTexture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_NEAREST);
        ByteBuffer white=ByteBuffer.allocateDirect(4);white.putInt(-1).flip();
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL11C.GL_RGBA8,1,1,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,white);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,textures[0]);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0+initialTexture);
    }
    private static int[] integers(int parameter,int count) {IntBuffer b=ByteBuffer.allocateDirect(Math.max(count,16)*4).order(ByteOrder.nativeOrder()).asIntBuffer();GL11C.glGetIntegerv(parameter,b);int[] r=new int[count];for(int i=0;i<count;i++)r[i]=b.get(i);return r;}
    private static float[] floats(int parameter,int count) {FloatBuffer b=ByteBuffer.allocateDirect(Math.max(count,16)*4).order(ByteOrder.nativeOrder()).asFloatBuffer();GL11C.glGetFloatv(parameter,b);float[] r=new float[count];for(int i=0;i<count;i++)r[i]=b.get(i);return r;}
    static int enableGroup(int capability) {
        switch(capability) {
            case 3042:case 3024:case 3058:return 0x4000;
            case 2884:case 32823:return 8;
            case 2929:return 0x100;case 2960:return 0x400;case 3089:return 0x80000;
            case 2848:return 4;case 34370:return 2;
            case 32925:case 32926:case 32927:case 32928:return 0x200000;
            default:throw new IllegalArgumentException("Unsupported game raster capability: "+capability);
        }
    }
    private void nativeEnable(int capability,boolean on){if(on)enabled.add(capability);else enabled.remove(capability);if(capability==2884)cullEnabled=on;if(capability==32823)polygonOffsetFill=on;if(on)GL11C.glEnable(capability);else GL11C.glDisable(capability);}
    boolean enabled(int capability){enableGroup(capability);return enabled.contains(capability);}
    boolean depthMask(){return depthWrite;}
    void depthMask(boolean value){set("depthMask",0x100,()->nativeDepthMask(value));}
    private void nativeDepthMask(boolean value){depthWrite=value;GL11C.glDepthMask(value);}
    int texture(){return textures[activeTexture];}
    private void applyViewport(int x,int y,int width,int height){viewportWidth=width;viewportHeight=height;GL11C.glViewport(x,y,width,height);}
    void viewport(int x,int y,int width,int height){set("viewport",0x800,()->applyViewport(x,y,width,height));}
    int viewportWidth(){return viewportWidth;}int viewportHeight(){return viewportHeight;}
    void lineWidth(float value){if(!Float.isFinite(value)||value<=0)throw new IllegalArgumentException("Game line width");set("lineWidth",4,()->lineWidth=value);}
    float lineWidth(){return lineWidth;}
    void beginLines(){if(lineScope)throw new IllegalStateException("Nested line scope");lineScope=true;if(cullEnabled)GL11C.glDisable(GL11C.GL_CULL_FACE);if(polygonOffsetFill)GL11C.glDisable(GL11C.GL_POLYGON_OFFSET_FILL);}
    void endLines(){if(!lineScope)return;lineScope=false;if(cullEnabled)GL11C.glEnable(GL11C.GL_CULL_FACE);if(polygonOffsetFill)GL11C.glEnable(GL11C.GL_POLYGON_OFFSET_FILL);}
    void enable(int capability,boolean enabled){set("enable/"+capability,enableGroup(capability)|0x2000,()->nativeEnable(capability,enabled));}
    void set(String key,int group,Runnable command){remember(key,group,command,true);}
    void activeTexture(int unit){activeTexture=unit;GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);}
    private void rememberTexture(int texture) {
        if(texture==0||textureParameters.containsKey(texture))return;
        int[] parameters=new int[TEXTURE_PARAMETERS.length];
        for(int i=0;i<parameters.length;i++)parameters[i]=GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D,TEXTURE_PARAMETERS[i]);
        textureParameters.put(texture,parameters);
    }
    private void texture(int unit,int texture){textures[unit]=texture;GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,texture);rememberTexture(texture);}
    void bindTexture(int target,int texture){if(target!=GL11C.GL_TEXTURE_2D)throw new IllegalArgumentException("Game texture target");final int unit=activeTexture;set("texture/"+unit,0x40000,()->texture(unit,texture));}
    void textureParameter(int target,int parameter,int value) {
        if(target!=GL11C.GL_TEXTURE_2D||textures[activeTexture]==0)throw new IllegalArgumentException("Texture parameter requires an owned 2D texture");
        int index=-1;for(int i=0;i<TEXTURE_PARAMETERS.length;i++)if(TEXTURE_PARAMETERS[i]==parameter)index=i;
        if(index<0)throw new IllegalArgumentException("Unsupported game texture parameter: "+parameter);
        if(value==10496)value=33071;
        GL11C.glTexParameteri(target,parameter,value);textureParameters.get(textures[activeTexture])[index]=value;
    }
    void beginSamplers() {
        if(substituted!=0)throw new IllegalStateException("Nested game sampler scope");
        for(int unit=0;unit<2;unit++)if(textures[unit]==0){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,whiteTexture);substituted|=1<<unit;}
        if(substituted!=0)GL13C.glActiveTexture(GL13C.GL_TEXTURE0+activeTexture);
    }
    void endSamplers() {
        if(substituted==0)return;
        for(int unit=0;unit<2;unit++)if((substituted&(1<<unit))!=0){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,textures[unit]);}
        substituted=0;GL13C.glActiveTexture(GL13C.GL_TEXTURE0+activeTexture);
    }
    /** Extra units are scoped around one terrain pass, separate from the game's four logical units.
     * No image copying, texture-parameter changes or persistent ownership of external texture names. */
    void chunkTextures(int[] table) {
        if(table.length!=GameChunkTextures.SIZE)throw new IllegalArgumentException("Chunk texture table");
        if(savedChunkTextures==null){
            savedChunkTextures=new int[GameChunkTextures.SIZE];chunkTextureBindings=new int[GameChunkTextures.SIZE];
            for(int i=0;i<table.length;i++){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+4+i);savedChunkTextures[i]=chunkTextureBindings[i]=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);}
        }
        try{
            for(int i=0;i<table.length;i++){
                int texture=table[i]<=0?whiteTexture:table[i];
                if(chunkTextureBindings[i]!=texture){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+4+i);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,texture);chunkTextureBindings[i]=texture;}
            }
        }finally{GL13C.glActiveTexture(GL13C.GL_TEXTURE0+activeTexture);}
    }
    void endChunkTextures(){
        if(savedChunkTextures==null)return;
        try{for(int i=0;i<savedChunkTextures.length;i++)if(chunkTextureBindings[i]!=savedChunkTextures[i]){
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0+4+i);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,savedChunkTextures[i]);
        }}finally{savedChunkTextures=null;chunkTextureBindings=null;GL13C.glActiveTexture(GL13C.GL_TEXTURE0+activeTexture);}
    }
    void deleteTexture(int texture) {
        GL11C.glDeleteTextures(texture);
        textureParameters.remove(texture);
        // Texture deletion changes live bindings; snapshots intentionally retain the original name,
        // matching GL's object-name behavior if a caller deletes a texture inside an attribute scope.
        int unit=activeTexture;
        for(int i=0;i<4;i++){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+i);int bound=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);final int u=i,t=bound;textures[i]=bound;remember("texture/"+i,0x40000,()->texture(u,t),false);}
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);
    }
    Runnable snapshot(int mask) {
        List<Entry> saved=new ArrayList<Entry>();for(Entry e:values.values())if((e.groups&mask)!=0)saved.add(e);
        final Map<Integer,int[]> parameters=new LinkedHashMap<Integer,int[]>();
        if((mask&0x40000)!=0)for(int texture:textures)if(texture!=0)parameters.put(texture,textureParameters.get(texture).clone());
        final int textureUnit=activeTexture;
        return ()->{
            for(Entry e:saved){e.apply.run();values.put(e.key,e);}
            if((mask&0x40000)!=0)activeTexture=textureUnit;GL13C.glActiveTexture(GL13C.GL_TEXTURE0+activeTexture);
            for(Map.Entry<Integer,int[]> e:parameters.entrySet()) {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,e.getKey());
                int[] current=textureParameters.get(e.getKey());
                for(int i=0;i<TEXTURE_PARAMETERS.length;i++)if(current[i]!=e.getValue()[i]){GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,TEXTURE_PARAMETERS[i],e.getValue()[i]);current[i]=e.getValue()[i];}
            }
            if(!parameters.isEmpty())GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,textures[activeTexture]);
        };
    }
}
