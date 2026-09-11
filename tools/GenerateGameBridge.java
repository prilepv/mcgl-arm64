import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Finite, typed game boundary. Only Core-valid native commands may pass through; removed APIs fail closed. */
public final class GenerateGameBridge {
    private static final String ROOT="local.mcgl.render";
    private static final Set<String> REMOVED=new HashSet<String>(Arrays.asList("glColorPointer","glNormalPointer","glVertexPointer","glTexCoordPointer",
            "glEnableClientState","glDisableClientState","glPushClientAttrib","glPopClientAttrib","glDrawArrays"));
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("manifest NEW-generated-directory");
        Path output=Paths.get(args[1]);if(Files.exists(output))throw new IOException("Game bridge output exists");
        Map<String,StringBuilder> families=new TreeMap<String,StringBuilder>();
        StringBuilder contract=start(ROOT,"public interface GameRenderCommands");
        contract.append("    void check();\n    boolean insideBeginEnd();\n    void abandon();\n    void abortModel();\n")
                .append("    void selectEffect(ShaderProgram program);\n    long drawCalls();\n    long rawBatches();\n    long submittedVertices();\n")
                .append("    int cachedModels();\n    long cachedModelDraws();\n    void glNewList(int name, int mode, boolean terrain);\n")
                .append("    long transientMeshCreations();\n    int transientMeshCount();\n    long transientMeshBytes();\n")
                .append("    void beginChunk(int handle, int x, int y, int z, int passes);\n    void finishChunk();\n    void abortChunk();\n")
                .append("    void beginOriginalChunk(int handle, int passes);\n    void finishOriginalChunk();\n    void abortOriginalChunk();\n")
                .append("    int beginOriginalTerrain();\n    void endOriginalTerrain(int scope);\n")
                .append("    void defineFontGlyphs(int first, int count);\n    int beginText();\n    void endText(int scope);\n    void abortText(int scope);\n    long textGlyphs();\n    long textDraws();\n")
                .append("    long terrainBatchParts();\n    long terrainBatches();\n    long terrainArenaBytes();\n    int terrainArenaPages();\n    int terrainArenaMembers();\n    long terrainArenaCreations();\n")
                .append("    int terrainPendingRecoveries();\n    long terrainRecoveries();\n    long terrainRecoveryBytes();\n")
                .append("    void drawChunks(java.util.List<?> selected, int pass, double x, double y, double z);\n")
                .append("    int residentChunks();\n    long chunkDrawCalls();\n    long chunkIndexUploads();\n")
                .append("    int raw(int[] data, int words, int count, int mode, boolean converted, boolean begun, boolean color, boolean texture, boolean light, boolean normals);\n");
        Set<String> declared=new HashSet<String>();
        StringBuilder guarded=start(ROOT,"final class GuardedGameRenderCommands implements GameRenderCommands");
        guarded.append("    private final RenderContext context;\n    private final GameRenderCommands target;\n")
                .append("    GuardedGameRenderCommands(RenderContext context, GameRenderCommands target) { this.context=context; this.target=target; }\n")
                .append("    public void check() { context.checkOwner(); target.check(); }\n")
                .append("    public boolean insideBeginEnd() { context.checkOwner(); return target.insideBeginEnd(); }\n")
                .append("    public void abandon() { if (!context.isClosed()) throw new IllegalStateException(\"Live game context cannot be abandoned\"); target.abandon(); }\n")
                .append("    public void abortModel() { context.checkOwner(); target.abortModel(); }\n")
                .append("    public void selectEffect(ShaderProgram p) { context.checkOwner(); target.selectEffect(p); }\n")
                .append("    public long drawCalls() { context.checkOwner(); return target.drawCalls(); }\n")
                .append("    public long rawBatches() { context.checkOwner(); return target.rawBatches(); }\n")
                .append("    public long submittedVertices() { context.checkOwner(); return target.submittedVertices(); }\n")
                .append("    public long cachedModelDraws() { context.checkOwner(); return target.cachedModelDraws(); }\n")
                .append("    public int cachedModels() { context.checkOwner(); return target.cachedModels(); }\n")
                .append("    public long transientMeshCreations() { context.checkOwner(); return target.transientMeshCreations(); }\n")
                .append("    public int transientMeshCount() { context.checkOwner(); return target.transientMeshCount(); }\n")
                .append("    public long transientMeshBytes() { context.checkOwner(); return target.transientMeshBytes(); }\n")
                .append("    public void beginChunk(int h, int x, int y, int z, int p) { context.checkOwner(); target.beginChunk(h,x,y,z,p); }\n")
                .append("    public void finishChunk() { context.checkOwner(); target.finishChunk(); }\n")
                .append("    public void abortChunk() { context.checkOwner(); target.abortChunk(); }\n")
                .append("    public void beginOriginalChunk(int h, int p) { context.checkOwner(); target.beginOriginalChunk(h,p); }\n")
                .append("    public void finishOriginalChunk() { context.checkOwner(); target.finishOriginalChunk(); }\n")
                .append("    public void abortOriginalChunk() { context.checkOwner(); target.abortOriginalChunk(); }\n")
                .append("    public int beginOriginalTerrain() { context.checkOwner(); return target.beginOriginalTerrain(); }\n")
                .append("    public void endOriginalTerrain(int scope) { context.checkOwner(); target.endOriginalTerrain(scope); }\n")
                .append("    public void defineFontGlyphs(int first, int count) { context.checkOwner(); target.defineFontGlyphs(first,count); }\n")
                .append("    public int beginText() { context.checkOwner(); return target.beginText(); }\n")
                .append("    public void endText(int scope) { context.checkOwner(); target.endText(scope); }\n")
                .append("    public void abortText(int scope) { context.checkOwner(); target.abortText(scope); }\n")
                .append("    public long textGlyphs() { context.checkOwner(); return target.textGlyphs(); }\n")
                .append("    public long textDraws() { context.checkOwner(); return target.textDraws(); }\n")
                .append("    public long terrainBatchParts() { context.checkOwner(); return target.terrainBatchParts(); }\n")
                .append("    public long terrainBatches() { context.checkOwner(); return target.terrainBatches(); }\n")
                .append("    public long terrainArenaBytes() { context.checkOwner(); return target.terrainArenaBytes(); }\n")
                .append("    public int terrainArenaPages() { context.checkOwner(); return target.terrainArenaPages(); }\n")
                .append("    public int terrainArenaMembers() { context.checkOwner(); return target.terrainArenaMembers(); }\n")
                .append("    public long terrainArenaCreations() { context.checkOwner(); return target.terrainArenaCreations(); }\n")
                .append("    public int terrainPendingRecoveries() { context.checkOwner(); return target.terrainPendingRecoveries(); }\n")
                .append("    public long terrainRecoveries() { context.checkOwner(); return target.terrainRecoveries(); }\n")
                .append("    public long terrainRecoveryBytes() { context.checkOwner(); return target.terrainRecoveryBytes(); }\n")
                .append("    public void drawChunks(java.util.List<?> s, int p, double x, double y, double z) { context.checkOwner(); target.drawChunks(s,p,x,y,z); }\n")
                .append("    public int residentChunks() { context.checkOwner(); return target.residentChunks(); }\n")
                .append("    public long chunkDrawCalls() { context.checkOwner(); return target.chunkDrawCalls(); }\n")
                .append("    public long chunkIndexUploads() { context.checkOwner(); return target.chunkIndexUploads(); }\n")
                .append("    public void glNewList(int n, int m, boolean t) { context.checkOwner(); target.glNewList(n,m,t); }\n")
                .append("    public int raw(int[] d, int w, int c, int m, boolean q, boolean b, boolean col, boolean tex, boolean l, boolean n) { context.checkOwner(); return target.raw(d,w,c,m,q,b,col,tex,l,n); }\n");
        StringBuilder commands=start(ROOT+".backend","public final class GameCommands extends GameRenderer implements "+ROOT+".GameRenderCommands");
        commands.append("    public GameCommands(").append(ROOT).append(".RenderContext context) { super(context); }\n");
        int count=0;
        for(RenderCommandSpec command:RenderCommandSpec.read(Paths.get(args[0])).values()) {
            String parameters=parameters(command),arguments=arguments(command),result=command.result.getClassName();
            String returns=result.equals("void")?"":"return ",target;
            boolean removed=false,nativeCall=false;
            switch(command.family) {
                case "GL11":target=command.name;removed=REMOVED.contains(command.name);break;
                case "GL13":target=command.name;break;
                case "ARBMultitexture":target=command.name.replaceFirst("ARB$","");break;
                case "GL14":target=command.name;break;
                case "GL20":case "ARBShaderObjects":target=command.dispatch;removed=true;break;
                default:target=command.dispatch;nativeCall=true;break;
            }
            StringBuilder family=families.get(command.family);
            if(family==null){family=start(ROOT+".game","public final class "+command.family);family.append("    private ").append(command.family).append("() {}\n");families.put(command.family,family);}
            family.append("    public static ").append(result).append(' ').append(command.name).append('(').append(parameters).append(") { ");
            if(removed) family.append(ROOT).append(".RenderSystem.game().check(); throw new IllegalStateException(\"Unmigrated client-array/shader call: ").append(command.family).append('.').append(command.name).append("\"); }\n");
            else family.append(returns).append(ROOT).append(".RenderSystem.game().").append(target).append('(').append(arguments).append("); }\n");
            if(!removed&&declared.add(target+command.descriptor)) {
                contract.append("    ").append(result).append(' ').append(target).append('(').append(parameters).append(");\n");
                guarded.append("    public ").append(result).append(' ').append(target).append('(').append(parameters).append(") { context.checkOwner(); ")
                        .append(returns).append("target.").append(target).append('(').append(arguments).append("); }\n");
            }
            if(nativeCall) {
                String nativeOwner,nativeName=command.name;
                switch(command.family) {
                    case "GL30":nativeOwner="GL30C";break;
                    case "ARBFramebufferObject":nativeOwner="GL30C";break;
                    case "EXTFramebufferObject":nativeOwner="GL30C";nativeName=nativeName.replaceFirst("EXT$","");break;
                    case "ARBOcclusionQuery":nativeOwner="GL15C";nativeName=nativeName.replaceFirst("ARB$","");break;
                    case "APPLEVertexArrayObject":nativeOwner="GL30C";nativeName=nativeName.replaceFirst("APPLE$","");break;
                    case "ARBVertexBufferObject":nativeOwner="GL15C";nativeName=nativeName.replaceFirst("ARB$","");break;
                    case "GL15":nativeOwner="GL15C";break;
                    default:throw new IOException("Unaudited native family: "+command.family);
                }
                commands.append("    public ").append(result).append(' ').append(target).append('(').append(parameters).append(") { flushExternal(); ");
                if(nativeName.equals("glFramebufferTexture2D"))commands.append("originalTextureRenderTarget(p3); ");
                commands.append(returns).append("org.lwjgl.opengl.").append(nativeOwner).append('.').append(nativeName).append('(').append(arguments).append("); }\n");
            }
            count++;
        }
        write(output,ROOT+".backend","GameCommands",commands);
        write(output,ROOT,"GameRenderCommands",contract);
        write(output,ROOT,"GuardedGameRenderCommands",guarded);
        for(Map.Entry<String,StringBuilder> e:families.entrySet())write(output,ROOT+".game",e.getKey(),e.getValue());
        System.out.println("GAME_BRIDGE_GENERATED signatures="+count+" native=fixed-function-free");
    }
    private static String parameters(RenderCommandSpec command){StringJoiner r=new StringJoiner(", ");for(int i=0;i<command.arguments.length;i++)r.add(command.arguments[i].getClassName()+" p"+i);return r.toString();}
    private static String arguments(RenderCommandSpec command){StringJoiner r=new StringJoiner(", ");for(int i=0;i<command.arguments.length;i++)r.add("p"+i);return r.toString();}
    private static StringBuilder start(String pack,String declaration){return new StringBuilder("// Generated by GenerateGameBridge; do not edit.\npackage ").append(pack).append(";\n\n").append(declaration).append(" {\n");}
    private static void write(Path root,String pack,String name,StringBuilder source)throws IOException{Path path=root.resolve(pack.replace('.','/')).resolve(name+".java");Files.createDirectories(path.getParent());Files.write(path,source.append("}\n").toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
}
