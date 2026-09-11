import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.CRC32;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Stage 10 game wiring: Core window, explicit accumulator drain, effect objects and typed game passes. */
public final class PatchMCGLGame implements Opcodes {
    private static final String SYSTEM="local/mcgl/render/RenderSystem",COMMANDS="local/mcgl/render/GameRenderCommands",EFFECT="local/mcgl/render/GameEffect";
    private static final String RAW="([IIIIZZZZZZ)I";
    private final SortedMap<String,RenderCommandSpec> manifest;
    private final boolean originalChunks;
    private int calls,windows,effects,accumulators,chunks,worlds,fonts,weather;
    private PatchMCGLGame(Path manifest,boolean originalChunks)throws IOException{this.manifest=RenderCommandSpec.read(manifest);this.originalChunks=originalChunks;}
    public static void main(String[] args)throws Exception {
        if(args.length!=3&&(args.length!=4||!args[3].equals("--original-chunks")))throw new IllegalArgumentException("post-chunk-client-or-rendered-utility.jar NEW-output.jar render-commands.txt [--original-chunks]");
        Path output=Paths.get(args[1]).toAbsolutePath();if(Files.exists(output))throw new IOException("Game output exists: "+output);
        PatchMCGLGame patch=new PatchMCGLGame(Paths.get(args[2]),args.length==4);
        try(JarFile input=new JarFile(args[0])) {
            if(input.getEntry("org/lwjgl/Version.class")!=null)throw new IOException("Do not adapt the binding library");
            if(input.getEntry("META-INF/mcgl/game-core-v1")!=null)throw new IOException("Game migration already installed");
            boolean client=input.getEntry("net/minecraft/client/Minecraft.class")!=null;String tess=null;
            if(client) {
                ClassNode chunk=type(read(input,"net/A/U/H.class"));
                for(FieldNode f:chunk.fields)if((f.access&ACC_STATIC)!=0&&f.desc.startsWith("Lnet/A/for/")) {
                    require(tess==null,"Ambiguous original accumulator");tess=f.desc.substring(1,f.desc.length()-1);
                }require(tess!=null,"Missing original accumulator");
            }
            Map<String,byte[]> changed=new HashMap<String,byte[]>();int retained=0;
            for(Enumeration<JarEntry> entries=input.entries();entries.hasMoreElements();) {
                JarEntry entry=entries.nextElement();if(!entry.getName().endsWith(".class"))continue;
                ClassNode node=type(read(input,entry.getName()));int before=patch.calls;boolean special=false;
                // This old, opt-in experiment is no longer called by the migrated game. Keep its
                // private implementation untouched until the separate legacy-cleanup milestone.
                if(node.name.startsWith("local/mcgl/perf/ChunkVbo")){retained++;continue;}
                if(node.name.equals("net/A/U/LA")){patch.effect(node);special=true;}
                if(node.name.equals("net/A/U/oooO")){patch.weather(node);special=true;}
                if(node.name.equals(tess)){patch.accumulator(node);special=true;}
                if(node.name.equals("net/A/U/H")){patch.chunk(node,tess);special=true;}
                if(node.name.matches("net/A/U/Ooo0O{100,}")){patch.world(node);special=true;}
                if(node.name.equals("net/A/U/E/C")||node.name.equals("net/A/U/E/oOOO")){patch.font(node);special=true;}
                for(MethodNode method:node.methods) {
                    if(method.name.equals("mcglChunkLegacyDraw"))continue; // unreachable original draw, retained for audit
                    for(AbstractInsnNode instruction:method.instructions.toArray()) {
                        if(instruction instanceof MethodInsnNode) {
                            MethodInsnNode call=(MethodInsnNode)instruction;
                            if(call.owner.equals("local/mcgl/perf/ChunkVbo")&&call.name.equals("unsupported")&&call.desc.equals("(Ljava/lang/String;)V")) {
                                method.instructions.set(call,new InsnNode(POP));patch.calls++;continue;
                            }
                            if(call.owner.equals("local/mcgl/perf/ChunkVbo")&&call.name.equals("glNewList")&&call.desc.equals("(IIZ)V")) {
                                // The old boolean selected an opt-in VBO experiment. Both paths now compile owned Core models.
                                method.instructions.insertBefore(call,new InsnNode(POP));call.desc="(II)V";
                            }
                            call.owner=patch.owner(call.owner,call.name,call.desc,call.getOpcode()==INVOKESTATIC);
                        } else if(instruction instanceof InvokeDynamicInsnNode) {
                            InvokeDynamicInsnNode call=(InvokeDynamicInsnNode)instruction;call.bsm=patch.handle(call.bsm);
                            for(int i=0;i<call.bsmArgs.length;i++)if(call.bsmArgs[i] instanceof MethodHandle)call.bsmArgs[i]=patch.handle((MethodHandle)call.bsmArgs[i]);
                        } else if(instruction instanceof LdcInsnNode&&((LdcInsnNode)instruction).cst instanceof MethodHandle) {
                            LdcInsnNode ldc=(LdcInsnNode)instruction;ldc.cst=patch.handle((MethodHandle)ldc.cst);
                        }
                    }
                }
                if(special||patch.calls!=before){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);changed.put(entry.getName(),writer.toByteArray());}else retained++;
            }
            require(!client||patch.windows==1&&patch.effects==1&&patch.accumulators==1&&patch.chunks==1&&patch.worlds==1&&patch.fonts==2&&patch.weather==1,"Incomplete Core game wiring");
            require(patch.calls>0,"No game render calls found");
            Path temporary=Files.createTempFile(output.getParent(),".mcgl-game-",".jar");
            try {
                try(JarOutputStream result=new JarOutputStream(Files.newOutputStream(temporary))) {
                    for(Enumeration<JarEntry> entries=input.entries();entries.hasMoreElements();) {
                        JarEntry entry=entries.nextElement();if(entry.isDirectory())continue;byte[] bytes=changed.get(entry.getName());
                        write(result,entry.getName(),bytes==null?read(input,entry.getName()):bytes);
                    }write(result,"META-INF/mcgl/game-core-v1","Core41/game-passes-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    write(result,"META-INF/mcgl/chunk-policy",(patch.originalChunks?"original-cache\n":"global-face\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    write(result,"META-INF/mcgl/text-policy","ordered-glyphs-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if(client)write(result,"META-INF/mcgl/weather-policy","finite-center-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }Files.move(temporary,output);
            }finally{Files.deleteIfExists(temporary);}
            System.out.println("GAME_ADAPTER_PASS classes="+changed.size()+" retained="+retained+" calls="+patch.calls+" windows="+patch.windows+" effects="+patch.effects+" accumulators="+patch.accumulators+" chunks="+patch.chunks+" worlds="+patch.worlds+" fonts="+patch.fonts+" weather="+patch.weather+" chunk-policy="+(patch.originalChunks?"original-cache":"global-face"));
        }
    }
    private String owner(String owner,String name,String descriptor,boolean isStatic)throws IOException {
        if(owner.equals("org/lwjgl/opengl/Display")&&name.equals("create")) {
            require(isStatic&&(descriptor.equals("()V")||descriptor.equals("(Lorg/lwjgl/opengl/PixelFormat;)V")),"Unexpected game window creation");
            windows++;calls++;return "org/lwjgl/opengl/MCGLCoreDisplay";
        }
        String nativeOwner;
        if(owner.startsWith("local/mcgl/render/legacy/"))nativeOwner="org/lwjgl/opengl/"+owner.substring("local/mcgl/render/legacy/".length());
        else if(owner.equals("local/mcgl/perf/ChunkVbo")&&name.startsWith("gl"))nativeOwner="org/lwjgl/opengl/GL11";
        else if(owner.startsWith("org/lwjgl/opengl/")&&name.startsWith("gl"))nativeOwner=owner;
        else return owner;
        RenderCommandSpec command=manifest.get(RenderCommandSpec.key(nativeOwner,name,descriptor));
        require(command!=null&&isStatic,"Unmapped game operation: "+owner+"."+name+descriptor);calls++;
        return "local/mcgl/render/game/"+command.family;
    }
    private MethodHandle handle(MethodHandle handle)throws IOException {
        String owner=owner(handle.getOwner(),handle.getName(),handle.getDesc(),handle.getTag()==MH_INVOKESTATIC);
        return owner.equals(handle.getOwner())?handle:new MethodHandle(handle.getTag(),owner,handle.getName(),handle.getDesc());
    }
    private void weather(ClassNode node)throws IOException {
        MethodNode draw=method(node,"void","(F)V");
        require(fingerprint(draw)==2466055191L,"Changed original rain/snow renderer");
        int lengths=0;
        for(AbstractInsnNode instruction:draw.instructions.toArray())if(instruction instanceof MethodInsnNode) {
            MethodInsnNode call=(MethodInsnNode)instruction;
            if(call.getOpcode()==INVOKESTATIC&&call.owner.equals("net/A/for/VB")&&call.name.equals("Ô00000")&&call.desc.equals("(F)F")) {
                // The 32x32 weather directions use integer offsets from (16,16).
                // Every nonzero length is >= 1, so this changes ONLY the center's
                // 0/0: its zero-width quad becomes finite and stays degenerate.
                // Keep the original rain/snow emission and all Core input guards.
                InsnList guard=new InsnList();guard.add(new InsnNode(FCONST_1));
                guard.add(new MethodInsnNode(INVOKESTATIC,"java/lang/Math","max","(FF)F"));
                draw.instructions.insert(call,guard);lengths++;
            }
        }
        require(lengths==1,"Changed weather direction initializer");weather++;
    }
    private void accumulator(ClassNode node)throws IOException {
        require(node.interfaces.contains("local/mcgl/render/ChunkTessellator"),"Stage 9 CPU sink is missing");
        require(fingerprint(method(node,"new","(DDD)V"))==589273501L,"Changed original vertex emitter");
        require(fingerprint(method(node,"Ó00000","()V"))==1560815914L,"Changed original reset");
        require(fingerprint(method(node,"Ó00000","(I)V"))==4229781381L,"Changed original begin");
        MethodNode draw=method(node,"new","()I");int legacy=0,drain=0;
        for(AbstractInsnNode i:draw.instructions.toArray())if(i instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)i;if(call.name.equals("mcglChunkLegacyDraw"))legacy++;if(call.name.equals("mcglChunkDrain"))drain++;}
        require(legacy==1&&drain==1,"Changed stage 9 draw wrapper");clear(draw);Label sink=new Label();
        field(draw,node,"mcglChunkSink","Llocal/mcgl/render/ChunkMeshBuilder$Sink;");draw.visitJumpInsn(IFNONNULL,sink);
        draw.visitMethodInsn(INVOKESTATIC,SYSTEM,"game","()L"+COMMANDS+";");
        field(draw,node,"Õ00000","[I");field(draw,node,"return","I");field(draw,node,"oO0000","I");field(draw,node,"thissuper","I");
        draw.visitFieldInsn(GETSTATIC,node.name,"while","Z");
        for(String flag:new String[]{"Ôo0000","Ô00000","õO0000","o00000","Stringsuper"})field(draw,node,flag,"Z");
        draw.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"raw",RAW);draw.visitVarInsn(ISTORE,1);
        draw.visitVarInsn(ALOAD,0);draw.visitInsn(ICONST_0);draw.visitFieldInsn(PUTFIELD,node.name,"Ôo0000","Z");
        draw.visitVarInsn(ALOAD,0);draw.visitMethodInsn(INVOKESPECIAL,node.name,"Ó00000","()V");draw.visitVarInsn(ILOAD,1);draw.visitInsn(IRETURN);
        draw.visitLabel(sink);draw.visitFrame(F_SAME,0,null,0,null);draw.visitVarInsn(ALOAD,0);draw.visitMethodInsn(INVOKESPECIAL,node.name,"mcglChunkDrain","()I");draw.visitInsn(IRETURN);end(draw);
        MethodNode abort=new MethodNode(ACC_PUBLIC|ACC_FINAL,"mcglAbortGameBatch","()V",null,null);node.methods.add(abort);abort.visitCode();
        abort.visitVarInsn(ALOAD,0);abort.visitInsn(ICONST_0);abort.visitFieldInsn(PUTFIELD,node.name,"Ôo0000","Z");
        abort.visitVarInsn(ALOAD,0);abort.visitMethodInsn(INVOKESPECIAL,node.name,"Ó00000","()V");
        abort.visitVarInsn(ALOAD,0);abort.visitInsn(DCONST_0);abort.visitInsn(DCONST_0);abort.visitInsn(DCONST_0);abort.visitMethodInsn(INVOKEVIRTUAL,node.name,"o00000","(DDD)V");abort.visitInsn(RETURN);end(abort);accumulators++;
    }
    private void chunk(ClassNode node,String tess)throws IOException {
        require(tess!=null&&!node.interfaces.contains("local/mcgl/render/GameChunkHandle"),"Unexpected chunk input");
        MethodNode body=method(node,"Ö00000","()V"),resort=method(node,"Õ00000","()V");
        require(fingerprint(body)==97421622L&&fingerprint(resort)==2175929822L,"Changed original chunk rebuild");
        require(fingerprint(method(node,"ô00000","()V"))==3795128424L&&fingerprint(method(node,"o00000","(III)V"))==663035864L,"Changed original chunk lifetime");
        node.interfaces.add("local/mcgl/render/GameChunkHandle");
        MethodNode handle=new MethodNode(ACC_PUBLIC|ACC_FINAL,"mcglChunkHandle","()I",null,null);node.methods.add(handle);handle.visitCode();field(handle,node,"oo0000","I");handle.visitInsn(IRETURN);end(handle);
        if(originalChunks) {
            originalChunkBody(node,body,"mcglRebuildOriginalBody",tess,true);
            originalChunkBody(node,resort,"mcglResortOriginalBody",tess,false);
            chunks++;return;
        }
        body.name="mcglRebuildGameBody";body.access=ACC_PRIVATE;
        int sorts=0,snapshots=0;
        for(AbstractInsnNode instruction:body.instructions.toArray())if(instruction instanceof MethodInsnNode) {
            MethodInsnNode call=(MethodInsnNode)instruction;if(!call.owner.equals(tess))continue;
            if(call.name.equals("Ó00000")&&call.desc.equals("(DDD)V")) {
                InsnList discard=new InsnList();for(int i=0;i<3;i++)discard.add(new InsnNode(POP2));discard.add(new InsnNode(POP));body.instructions.insertBefore(call,discard);body.instructions.remove(call);sorts++;
            }else if(call.name.equals("Õ00000")&&call.desc.equals("()[I")) {
                body.instructions.insertBefore(call,new InsnNode(POP));body.instructions.set(call,new InsnNode(ACONST_NULL));snapshots++;
            }
        }require(sorts==1&&snapshots==1,"Changed original transparent snapshot path");
        clear(resort);resort.visitInsn(RETURN);end(resort);
        MethodNode rebuild=new MethodNode(ACC_PUBLIC,"Ö00000","()V",null,null);node.methods.add(rebuild);rebuild.visitCode();
        Label dirty=new Label(),start=new Label(),finish=new Label(),failure=new Label();
        field(rebuild,node,"õ00000","Z");rebuild.visitJumpInsn(IFNE,dirty);rebuild.visitInsn(RETURN);
        rebuild.visitLabel(dirty);rebuild.visitFrame(F_SAME,0,null,0,null);game(rebuild);
        for(String name:new String[]{"oo0000","ôO0000","thissuper","ÕO0000"})field(rebuild,node,name,"I");
        rebuild.visitFieldInsn(GETSTATIC,node.name,"nullsuper","I");rebuild.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"beginChunk","(IIIII)V");
        rebuild.visitLabel(start);rebuild.visitVarInsn(ALOAD,0);rebuild.visitMethodInsn(INVOKESPECIAL,node.name,body.name,"()V");
        game(rebuild);rebuild.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"finishChunk","()V");rebuild.visitLabel(finish);rebuild.visitInsn(RETURN);
        rebuild.visitLabel(failure);rebuild.visitFrame(F_SAME1,0,null,1,new Object[]{"java/lang/Throwable"});rebuild.visitVarInsn(ASTORE,1);
        game(rebuild);rebuild.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"abortChunk","()V");
        rebuild.visitFieldInsn(GETSTATIC,node.name,"new","L"+tess+";");rebuild.visitMethodInsn(INVOKEVIRTUAL,tess,"mcglAbortGameBatch","()V");
        rebuild.visitInsn(ICONST_0);rebuild.visitFieldInsn(PUTSTATIC,"net/A/C/voidfloat","floatclass","Z");
        rebuild.visitVarInsn(ALOAD,1);rebuild.visitInsn(ATHROW);rebuild.visitTryCatchBlock(start,finish,failure,"java/lang/Throwable");end(rebuild);chunks++;
    }
    private void world(ClassNode node)throws IOException {
        MethodNode filter=method(node,"o00000","(IIID)I"),draw=method(node,"o00000","(ID)V");
        require(fingerprint(filter)==976112134L&&fingerprint(draw)==2502148361L,"Changed original world scheduler");
        require(filter.tryCatchBlocks.isEmpty(),"Unexpected scheduler exception table");
        // The control path keeps BOTH original alpha-sort choices, selected-chunk order,
        // one-at-a-time resort queue, region groups, lightmap hooks and pass replay unchanged.
        // The common call-site pass below still routes every GL operation into Core meshes.
        if(originalChunks){originalTerrainBody(node,draw);worlds++;return;}
        AbstractInsnNode cut=null;int cameras=0;
        for(AbstractInsnNode i:filter.instructions.toArray())if(i instanceof FieldInsnNode) {
            FieldInsnNode f=(FieldInsnNode)i;
            if(f.owner.equals("net/minecraft/client/Minecraft")&&f.name.equals("ÒÓ0000")&&f.desc.equals("Lnet/A/O0oO/PB;")) {
                cut=i.getPrevious().getPrevious();require(cut instanceof VarInsnNode&&cut.getOpcode()==ALOAD&&((VarInsnNode)cut).var==0,"Changed scheduler visibility boundary");cameras++;
            }
        }require(cameras==1,"Missing scheduler visibility boundary");
        for(AbstractInsnNode i=cut;i!=null;){AbstractInsnNode next=i.getNext();filter.instructions.remove(i);i=next;}
        if(filter.localVariables!=null)filter.localVariables.clear();
        filter.visitVarInsn(ALOAD,0);filter.visitVarInsn(ILOAD,3);filter.visitVarInsn(DLOAD,4);filter.visitMethodInsn(INVOKEVIRTUAL,node.name,"o00000","(ID)V");filter.visitVarInsn(ILOAD,6);filter.visitInsn(IRETURN);end(filter);
        // Preserve the game's lightmap hooks and its exact interpolated camera, but submit the
        // already-filtered chunk handles directly. No display-list order or CPU vertex resort.
        clear(draw);lightmap(draw,node,"o00000");Label start=new Label(),finish=new Label(),failure=new Label();
        draw.visitLabel(start);field(draw,node,"õO0000","Lnet/minecraft/client/Minecraft;");draw.visitFieldInsn(GETFIELD,"net/minecraft/client/Minecraft","ÒÓ0000","Lnet/A/O0oO/PB;");draw.visitVarInsn(ASTORE,4);
        field(draw,node,"private","Ljava/util/List;");draw.visitMethodInsn(INVOKEINTERFACE,"java/util/List","clear","()V");
        game(draw);field(draw,node,"ÒÒ0000","Ljava/util/List;");draw.visitVarInsn(ILOAD,1);
        camera(draw,"StringObject","ifnew");camera(draw,"õÓ0000","ôÒ0000");camera(draw,"ôÓ0000","ØÒ0000");
        draw.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"drawChunks","(Ljava/util/List;IDDD)V");draw.visitLabel(finish);lightmap(draw,node,"Ò00000");draw.visitInsn(RETURN);
        draw.visitLabel(failure);draw.visitFrame(F_FULL,3,new Object[]{node.name,INTEGER,DOUBLE},1,new Object[]{"java/lang/Throwable"});draw.visitVarInsn(ASTORE,5);lightmap(draw,node,"Ò00000");draw.visitVarInsn(ALOAD,5);draw.visitInsn(ATHROW);
        draw.visitTryCatchBlock(start,finish,failure,"java/lang/Throwable");end(draw);worlds++;
    }
    /** Keep the original algorithm byte-for-byte apart from the common graphics-reference mapping.
     * Only GPU publication and failure cleanup are wrapped; success does not change its state policy. */
    private static void originalChunkBody(ClassNode node,MethodNode body,String helper,String tess,boolean dirtyGuard) {
        String name=body.name;body.name=helper;body.access=ACC_PRIVATE;
        MethodNode wrapper=new MethodNode(ACC_PUBLIC,name,"()V",null,null);node.methods.add(wrapper);wrapper.visitCode();
        if(dirtyGuard){Label dirty=new Label();field(wrapper,node,"õ00000","Z");wrapper.visitJumpInsn(IFNE,dirty);wrapper.visitInsn(RETURN);wrapper.visitLabel(dirty);wrapper.visitFrame(F_SAME,0,null,0,null);}
        field(wrapper,node,"ô00000","[[I");wrapper.visitMethodInsn(INVOKEVIRTUAL,"[[I","clone","()Ljava/lang/Object;");wrapper.visitTypeInsn(CHECKCAST,"[[I");wrapper.visitVarInsn(ASTORE,1);
        game(wrapper);field(wrapper,node,"oo0000","I");wrapper.visitFieldInsn(GETSTATIC,node.name,"nullsuper","I");wrapper.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"beginOriginalChunk","(II)V");
        Label start=new Label(),finish=new Label(),failure=new Label();wrapper.visitLabel(start);
        wrapper.visitVarInsn(ALOAD,0);wrapper.visitMethodInsn(INVOKESPECIAL,node.name,helper,"()V");
        game(wrapper);wrapper.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"finishOriginalChunk","()V");wrapper.visitLabel(finish);wrapper.visitInsn(RETURN);
        wrapper.visitLabel(failure);wrapper.visitFrame(F_FULL,2,new Object[]{node.name,"[[I"},1,new Object[]{"java/lang/Throwable"});wrapper.visitVarInsn(ASTORE,2);
        wrapper.visitVarInsn(ALOAD,0);wrapper.visitVarInsn(ALOAD,1);wrapper.visitFieldInsn(PUTFIELD,node.name,"ô00000","[[I");
        game(wrapper);wrapper.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"abortOriginalChunk","()V");
        wrapper.visitFieldInsn(GETSTATIC,node.name,"new","L"+tess+";");wrapper.visitMethodInsn(INVOKEVIRTUAL,tess,"mcglAbortGameBatch","()V");
        wrapper.visitInsn(ICONST_0);wrapper.visitFieldInsn(PUTSTATIC,"net/A/C/voidfloat","floatclass","Z");
        wrapper.visitVarInsn(ALOAD,2);wrapper.visitInsn(ATHROW);wrapper.visitTryCatchBlock(start,finish,failure,"java/lang/Throwable");end(wrapper);
    }
    private void font(ClassNode node)throws IOException {
        boolean cached=node.name.equals("net/A/U/E/C");
        require(node.superName.equals("net/A/U/E/class"),"Changed original font superclass");
        MethodNode floating=method(node,"o00000","(Ljava/lang/String;FFIZ)V");
        require(fingerprint(floating)==(cached?1598646215L:2769831101L),"Changed original floating string rendering");
        MethodNode integer=cached?method(node,"o00000","(Ljava/lang/String;IIIZ)V"):null;
        if(cached)require(fingerprint(integer)==1998616563L,"Changed original integer string rendering");
        if(cached) {
            MethodNode constructor=method(node,"<init>","(Lnet/A/for/o00Oo;Ljava/lang/String;Lnet/A/U/Oooo;)V");
            require(fingerprint(constructor)==4133975074L,"Changed original glyph definitions");
            int ranges=0;
            for(AbstractInsnNode instruction:constructor.instructions.toArray())if(instruction instanceof FieldInsnNode) {
                FieldInsnNode field=(FieldInsnNode)instruction;
                if(field.getOpcode()==PUTFIELD&&field.owner.equals(node.name)&&field.name.equals("class")&&field.desc.equals("I")) {
                    MethodNode hook=new MethodNode();game(hook);field(hook,node,"class","I");hook.visitIntInsn(SIPUSH,256);
                    hook.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"defineFontGlyphs","(II)V");constructor.instructions.insert(field,hook.instructions);ranges++;
                }
            }
            require(ranges==1,"Missing original font glyph range");
        } else {
            require(fingerprint(method(node,"Ó00000","(I)V"))==986493295L&&fingerprint(method(node,"Ó00000","(C)V"))==3399855950L,"Changed original immediate glyphs");
        }
        // Original formatting, widths, shadows, page selection and all glyph methods remain intact.
        // Only the draw-string lifetime supplies a finite ordered batching scope.
        if(cached)textScope(node,integer);
        textScope(node,floating);fonts++;
    }
    private static void textScope(ClassNode node,MethodNode body) {
        String descriptor=body.desc;boolean integer=descriptor.equals("(Ljava/lang/String;IIIZ)V");
        body.name="mcglDrawTextBody";body.access=ACC_PRIVATE;
        MethodNode wrapper=new MethodNode(ACC_PUBLIC,"o00000",descriptor,null,null);node.methods.add(wrapper);wrapper.visitCode();
        game(wrapper);wrapper.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"beginText","()I");wrapper.visitVarInsn(ISTORE,6);
        Label start=new Label(),finish=new Label(),failure=new Label();wrapper.visitLabel(start);wrapper.visitVarInsn(ALOAD,0);
        int local=1;for(Type type:Type.getArgumentTypes(descriptor)){wrapper.visitVarInsn(type.getOpcode(ILOAD),local);local+=type.getSize();}
        wrapper.visitMethodInsn(INVOKESPECIAL,node.name,body.name,descriptor);
        game(wrapper);wrapper.visitVarInsn(ILOAD,6);wrapper.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"endText","(I)V");
        wrapper.visitLabel(finish);wrapper.visitInsn(RETURN);
        wrapper.visitLabel(failure);wrapper.visitFrame(F_FULL,7,new Object[]{node.name,"java/lang/String",integer?INTEGER:FLOAT,integer?INTEGER:FLOAT,INTEGER,INTEGER,INTEGER},1,new Object[]{"java/lang/Throwable"});wrapper.visitVarInsn(ASTORE,7);
        game(wrapper);wrapper.visitVarInsn(ILOAD,6);wrapper.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"abortText","(I)V");wrapper.visitVarInsn(ALOAD,7);wrapper.visitInsn(ATHROW);
        wrapper.visitTryCatchBlock(start,finish,failure,"java/lang/Throwable");end(wrapper);
    }
    private static void originalTerrainBody(ClassNode node,MethodNode body) {
        String name=body.name;int access=body.access;
        body.name="mcglDrawOriginalTerrainBody";body.access=ACC_PRIVATE;
        MethodNode wrapper=new MethodNode(access,name,body.desc,body.signature,body.exceptions==null?null:(String[])body.exceptions.toArray(new String[0]));
        node.methods.add(wrapper);wrapper.visitCode();
        game(wrapper);wrapper.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"beginOriginalTerrain","()I");wrapper.visitVarInsn(ISTORE,4);
        Label start=new Label(),finish=new Label(),failure=new Label();
        wrapper.visitLabel(start);wrapper.visitVarInsn(ALOAD,0);wrapper.visitVarInsn(ILOAD,1);wrapper.visitVarInsn(DLOAD,2);
        wrapper.visitMethodInsn(INVOKESPECIAL,node.name,body.name,body.desc);
        // The cleanup call is outside the protected region: a failed flush must
        // not end the already-released scope a second time.
        wrapper.visitLabel(finish);game(wrapper);wrapper.visitVarInsn(ILOAD,4);wrapper.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"endOriginalTerrain","(I)V");wrapper.visitInsn(RETURN);
        wrapper.visitLabel(failure);wrapper.visitFrame(F_FULL,4,new Object[]{node.name,INTEGER,DOUBLE,INTEGER},1,new Object[]{"java/lang/Throwable"});wrapper.visitVarInsn(ASTORE,5);
        game(wrapper);wrapper.visitVarInsn(ILOAD,4);wrapper.visitMethodInsn(INVOKEINTERFACE,COMMANDS,"endOriginalTerrain","(I)V");wrapper.visitVarInsn(ALOAD,5);wrapper.visitInsn(ATHROW);
        wrapper.visitTryCatchBlock(start,finish,failure,"java/lang/Throwable");end(wrapper);
    }
    private static void game(MethodVisitor v){v.visitMethodInsn(INVOKESTATIC,SYSTEM,"game","()L"+COMMANDS+";");}
    private static void lightmap(MethodVisitor v,ClassNode node,String method){field(v,node,"õO0000","Lnet/minecraft/client/Minecraft;");v.visitFieldInsn(GETFIELD,"net/minecraft/client/Minecraft","nullnew","Lnet/A/U/oooO;");v.visitVarInsn(DLOAD,2);v.visitMethodInsn(INVOKEVIRTUAL,"net/A/U/oooO",method,"(D)V");}
    private static void camera(MethodVisitor v,String previous,String current){v.visitVarInsn(ALOAD,4);v.visitFieldInsn(GETFIELD,"net/A/O0oO/PB",previous,"D");v.visitVarInsn(ALOAD,4);v.visitFieldInsn(GETFIELD,"net/A/O0oO/PB",current,"D");v.visitVarInsn(ALOAD,4);v.visitFieldInsn(GETFIELD,"net/A/O0oO/PB",previous,"D");v.visitInsn(DSUB);v.visitVarInsn(DLOAD,2);v.visitInsn(DMUL);v.visitInsn(DADD);}
    private void effect(ClassNode node)throws IOException {
        require(node.superName.equals("java/lang/Object")&&node.interfaces.isEmpty(),"Changed original effect class");
        Map<String,Long> expected=new LinkedHashMap<String,Long>();
        expected.put("<init>(Ljava/lang/String;)V",983158224L);expected.put("<init>(Ljava/lang/String;Ljava/lang/String;)V",2521837969L);
        expected.put("Ò00000()V",2352916305L);expected.put("o00000()V",4147763097L);expected.put("Ö00000(Ljava/lang/String;)I",2379906575L);
        expected.put("o00000(II)V",2719232446L);expected.put("Õ00000(Ljava/lang/String;)I",2891509932L);expected.put("o00000(IF)V",4213830874L);
        expected.put("Ò00000(Ljava/lang/String;)I",3518690118L);expected.put("o00000(IFFF)V",812135473L);expected.put("Ó00000(Ljava/lang/String;)I",3693971563L);expected.put("o00000(I[F)V",3466775262L);
        List<MethodNode> publicMethods=new ArrayList<MethodNode>();
        for(MethodNode m:node.methods)if((m.access&ACC_PUBLIC)!=0){Long hash=expected.remove(m.name+m.desc);require(hash!=null&&fingerprint(m)==hash,"Changed effect method: "+m.name+m.desc);publicMethods.add(m);}
        require(expected.isEmpty(),"Missing original effect public API");
        node.methods.clear();node.fields.clear();node.fields.add(new FieldNode(ACC_PRIVATE|ACC_FINAL,"mcglEffect","L"+EFFECT+";",null,null));
        for(MethodNode m:publicMethods) {
            clear(m);node.methods.add(m);
            if(m.name.equals("<init>")) {
                m.visitVarInsn(ALOAD,0);
                if(m.desc.equals("(Ljava/lang/String;)V")) {
                    m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,1);m.visitMethodInsn(INVOKESPECIAL,node.name,"<init>","(Ljava/lang/String;Ljava/lang/String;)V");
                }else {
                    m.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V");m.visitVarInsn(ALOAD,0);m.visitTypeInsn(NEW,EFFECT);m.visitInsn(DUP);
                    m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,2);m.visitMethodInsn(INVOKESPECIAL,EFFECT,"<init>",m.desc);m.visitFieldInsn(PUTFIELD,node.name,"mcglEffect","L"+EFFECT+";");
                }m.visitInsn(RETURN);
            }else {
                m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,node.name,"mcglEffect","L"+EFFECT+";");int local=1;
                for(Type t:Type.getArgumentTypes(m.desc)){m.visitVarInsn(t.getOpcode(ILOAD),local);local+=t.getSize();}
                String target=m.desc.equals("()V")?(m.name.equals("Ò00000")?"begin":"end"):m.desc.equals("(Ljava/lang/String;)I")?"uniform":"set";
                m.visitMethodInsn(INVOKEVIRTUAL,EFFECT,target,m.desc);m.visitInsn(Type.getReturnType(m.desc).getOpcode(IRETURN));
            }end(m);
        }effects++;
    }
    private static void field(MethodVisitor v,ClassNode n,String name,String desc){v.visitVarInsn(ALOAD,0);v.visitFieldInsn(GETFIELD,n.name,name,desc);}
    private static void clear(MethodNode m){m.instructions.clear();m.tryCatchBlocks.clear();if(m.localVariables!=null)m.localVariables.clear();m.maxStack=0;m.maxLocals=0;m.visitCode();}
    private static void end(MethodNode m){m.visitMaxs(0,0);m.visitEnd();}
    private static MethodNode method(ClassNode n,String name,String desc)throws IOException{for(MethodNode m:n.methods)if(m.name.equals(name)&&m.desc.equals(desc))return m;throw new IOException("Missing game method: "+name+desc);}
    private static long fingerprint(MethodNode method){ClassWriter w=new ClassWriter(0);w.visit(50,1,"Fingerprint",null,"java/lang/Object",null);method.accept(w);w.visitEnd();method.instructions.resetLabels();CRC32 crc=new CRC32();crc.update(w.toByteArray());return crc.getValue();}
    private static ClassNode type(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    private static byte[] read(JarFile jar,String name)throws IOException{JarEntry e=jar.getJarEntry(name);if(e==null)throw new IOException("Missing entry: "+name);try(InputStream in=jar.getInputStream(e);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private static void write(JarOutputStream output,String name,byte[] data)throws IOException{JarEntry e=new JarEntry(name);e.setTime(0);output.putNextEntry(e);output.write(data);output.closeEntry();}
    private static void require(boolean condition,String message)throws IOException{if(!condition)throw new IOException(message);}
}
