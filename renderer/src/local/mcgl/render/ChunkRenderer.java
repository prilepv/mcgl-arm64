package local.mcgl.render;

import java.util.*;

/** Render-owner chunk registry. CPU rebuilds hand off immutable data; only this layer owns GPU meshes. */
public final class ChunkRenderer implements AutoCloseable {
    /** The enclosing world pass owns shader uniforms, textures, depth/blend/cull state and camera matrices.
     * begin/end delimit that state scope; bind must apply this material and the supplied block origin.
     * Callbacks must not mutate/re-enter the registry. There is no implicit fixed-function fallback. */
    public interface MaterialBinder {
        void begin(int pass);
        void bind(ChunkMaterial material, int originX, int originY, int originZ);
        default void afterDraw(ChunkMaterial material, int originX, int originY, int originZ) {}
        default boolean batching(){return false;}
        /** Stable identity of the batching policy. A different policy invalidates prepared draw plans. */
        default Object batchPolicy(){return this;}
        /** Equal immutable keys promise compatible per-vertex transforms and material state.
         * A key must remain stable for a published part within one batch policy. */
        default Object batchKey(ChunkMaterial material,int originX,int originY,int originZ){return null;}
        default void bindBatch(ChunkMaterial material,int originX,int originY,int originZ){throw new UnsupportedOperationException("No combined material binding");}
        void end(int pass);
    }
    public static final class Ticket {
        private final ChunkRenderer renderer;
        private final Key key;
        public final long revision;
        private Ticket(ChunkRenderer renderer, Key key, long revision) {
            this.renderer = renderer; this.key = key; this.revision = revision;
        }
        public int x() { return key.x; }
        public int y() { return key.y; }
        public int z() { return key.z; }
    }
    public static final class DrawStats {
        public final int visibleParts, culledParts, drawCalls, indices, indexUploads;
        private DrawStats(int visible, int culled, int calls, int indices, int uploads) {
            visibleParts = visible; culledParts = culled; drawCalls = calls; this.indices = indices; indexUploads = uploads;
        }
    }
    private final RenderContext context;
    private final MeshPipeline pipeline;
    private final int batchVertexLimit;
    // Insertion order is the deterministic tie break for equal-distance faces and opaque batches.
    private final Map<Key, Slot> slots = new LinkedHashMap<Key, Slot>();
    private final Plan[] plans = new Plan[3];
    private final FaceOrder[] faceOrders = new FaceOrder[3];
    @SuppressWarnings("unchecked") private final Map<Object,Batch>[] batches=new Map[]{new LinkedHashMap<Object,Batch>(),new LinkedHashMap<Object,Batch>(),new LinkedHashMap<Object,Batch>()};
    private long serial,faceSorts;
    private boolean closed, drawing;
    public ChunkRenderer(RenderContext context) {
        this(context,1024*1024);
    }
    /** Package-local smaller budget for exercising real split-group logic without oversized fixtures. */
    ChunkRenderer(RenderContext context,int batchVertexLimit) {
        if (context == null) throw new NullPointerException("context");
        if(batchVertexLimit<1)throw new IllegalArgumentException("Invalid batch vertex budget");
        context.checkOwner();
        if (context.profile() != RenderProfile.CORE_41) throw new IllegalArgumentException("Chunks require Core 4.1");
        this.context = context; this.pipeline = context.meshes();this.batchVertexLimit=batchVertexLimit;
    }
    public boolean isClosed() { return closed || context.isClosed(); }
    public Ticket request(int x, int y, int z) {
        check();
        Key key = new Key(x, y, z);
        if (serial == Long.MAX_VALUE) throw new IllegalStateException("Chunk revision exhausted");
        Ticket ticket = new Ticket(this, key, ++serial);
        Slot slot = slots.get(key);
        if (slot == null) { slot = new Slot(); slots.put(key, slot); }
        slot.ticket = ticket; // The last good mesh remains visible during the rebuild.
        return ticket;
    }
    /** False rejects a superseded/unloaded build BEFORE GPU allocation. Upload failure preserves the old mesh. */
    public boolean publish(Ticket ticket, ChunkMeshData data) {
        check();
        if (ticket == null || data == null) throw new NullPointerException("chunk handoff");
        if (ticket.renderer != this) throw new IllegalArgumentException("Ticket belongs to another renderer");
        if (ticket.x() != data.x || ticket.y() != data.y || ticket.z() != data.z)
            throw new IllegalArgumentException("Chunk handoff origin mismatch");
        Slot slot = slots.get(ticket.key);
        if (slot == null || slot.ticket != ticket || slot.published == ticket) return false;
        List<Part> next = new ArrayList<Part>();
        try {
            int index = 0;
            for (ChunkMeshData.Part source : data.parts()) {
                Mesh mesh = pipeline.create("chunk/" + ticket.revision + "/" + index++, source.mesh,
                        source.material.translucent() ? MeshPipeline.Usage.DYNAMIC : MeshPipeline.Usage.STATIC);
                try { next.add(new Part(ticket.key, source, mesh)); }
                catch (Throwable failure) { try { mesh.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); } throw failure; }
            }
        } catch (Throwable failure) {
            for (Part part : next) try { part.mesh.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        List<Part> previous = slot.parts;
        slot.parts = next; slot.published = ticket; invalidate(previous);invalidate(next);
        closeParts(previous);
        return true;
    }
    public void unload(int x, int y, int z) {
        check(); Slot old = slots.remove(new Key(x, y, z));
        if (old != null) { invalidate(old.parts); closeParts(old.parts); }
    }
    /** A retired scheduler object cannot unload a replacement that reused the same coordinates. */
    public boolean unload(Ticket ticket) {
        check(); if (ticket == null || ticket.renderer != this) throw new IllegalArgumentException("Foreign chunk ticket");
        Slot old = slots.get(ticket.key); if (old == null || old.ticket != ticket) return false;
        slots.remove(ticket.key); invalidate(old.parts); closeParts(old.parts); return true;
    }
    public int residentChunks() {
        check(); int count = 0; for (Slot slot : slots.values()) if (slot.published != null) count++; return count;
    }
    long faceSortCount(){check();return faceSorts;}
    /** Sorted passes are global across visible chunks AND texture batches, not just per-buffer sorting. */
    public DrawStats drawPass(int pass, ChunkFrustum frustum, double cameraX, double cameraY, double cameraZ, MaterialBinder binder) {
        return drawPass(pass, frustum, cameraX, cameraY, cameraZ, binder, null);
    }
    /** Scheduler visibility is explicit; selection is by coordinate and may include a pending revision. */
    public DrawStats drawPass(int pass, ChunkFrustum frustum, double cameraX, double cameraY, double cameraZ,
                              MaterialBinder binder, Collection<Ticket> selected) {
        check();
        if (pass < 0 || pass > 2) throw new IllegalArgumentException("Invalid chunk pass");
        if (frustum == null || binder == null) throw new NullPointerException("chunk draw inputs");
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ))
            throw new IllegalArgumentException("Non-finite camera");
        Set<Key> selection = null;
        if (selected != null) {
            selection = new HashSet<Key>();
            for (Ticket ticket : selected) {
                if (ticket == null || ticket.renderer != this) throw new IllegalArgumentException("Foreign selected chunk");
                selection.add(ticket.key);
            }
        }
        boolean batching;Object batchPolicy;drawing=true;try{batching=binder.batching();batchPolicy=batching?binder.batchPolicy():null;}finally{drawing=false;}
        Plan plan = plans[pass]; int uploads = 0;
        if (plan == null || plan.batchPolicy!=batchPolicy || plan.frustum != frustum || !Objects.equals(plan.selection, selection) || (pass != 0
                && (plan.x != cameraX || plan.y != cameraY || plan.z != cameraZ))) {
            drawing=true;try{plan = plan(pass, frustum, cameraX, cameraY, cameraZ, selection,binder,batching,batchPolicy);}finally{drawing=false;}
            uploads = plan.uploads; plans[pass] = plan;
        }
        drawing = true;
        try {
            binder.begin(pass);
            try {
                for (Run run : plan.runs) {
                    Part part = run.part;
                    if(run.batch==null)binder.bind(part.material, part.key.x, part.key.y, part.key.z);
                    else binder.bindBatch(part.material,part.key.x,part.key.y,part.key.z);
                    try { (run.batch==null?part.mesh:run.batch.mesh).draw(Mesh.Primitive.TRIANGLES, run.first, run.count); }
                    finally { binder.afterDraw(part.material, part.key.x, part.key.y, part.key.z); }
                }
            } finally { binder.end(pass); }
        } finally { drawing = false; }
        return new DrawStats(plan.visible, plan.culled, plan.runs.size(), plan.indices, uploads);
    }
    private Plan plan(int pass, ChunkFrustum frustum, double x, double y, double z, Set<Key> selection,MaterialBinder binder,boolean batching,Object batchPolicy) {
        Plan plan = new Plan(frustum, x, y, z, selection,batchPolicy);
        List<Part> visible = new ArrayList<Part>();
        List<Part> resident = new ArrayList<Part>();
        for (Slot slot : slots.values()) for (Part part : slot.parts) {
            if (part.material.pass != pass) continue;
            resident.add(part);
            if (selection != null && !selection.contains(part.key)) continue;
            double[] b = part.bounds;
            // The original rebuild uses a 1.000001 scale near the chunk center: retain a conservative margin.
            double margin = 0.001;
            if (!frustum.intersects(b[0] + part.key.x - margin, b[1] + part.key.y - margin, b[2] + part.key.z - margin,
                    b[3] + part.key.x + margin, b[4] + part.key.y + margin, b[5] + part.key.z + margin)) { plan.culled++; continue; }
            plan.visible++; visible.add(part);
            if (pass != 0) {
                part.nextOrder = new int[part.centers.length / 3]; part.cursor = 0;
            }
        }
        {
            Map<Object,List<Part>> groups=new LinkedHashMap<Object,List<Part>>();
            // GPU membership follows published geometry, not the camera's visibility selection.
            // A turn must only change draw ranges/indices, never read back and repack unchanged VBOs.
            if(batching)for(Part part:resident){Object key=binder.batchKey(part.material,part.key.x,part.key.y,part.key.z);if(key!=null)groups.computeIfAbsent(key,k->new ArrayList<Part>()).add(part);}
            // A large view must not disable batching for its entire material. Split bounded source
            // groups and retain the same global face sequence across the resulting GPU meshes.
            Map<Object,List<Part>> bounded=new LinkedHashMap<Object,List<Part>>();
            for(Map.Entry<Object,List<Part>> entry:groups.entrySet()) {
                int segment=0;long count=0;List<Part> group=null;VertexLayout layout=null;
                for(Part part:entry.getValue()) {
                    int vertices=part.mesh.vertexCount();if(vertices>batchVertexLimit){group=null;count=0;continue;}
                    if(group==null||count+vertices>batchVertexLimit||!part.mesh.layout().equals(layout)) {
                        group=new ArrayList<Part>();count=0;layout=part.mesh.layout();bounded.put(new GroupKey(entry.getKey(),segment++),group);
                    }
                    group.add(part);count+=vertices;
                }
            }
            Set<Part> visibleSet=Collections.newSetFromMap(new IdentityHashMap<Part,Boolean>());visibleSet.addAll(visible);
            Map<Part,BatchWork> combined=new IdentityHashMap<Part,BatchWork>();Map<Object,Batch> nextBatches=new LinkedHashMap<Object,Batch>();List<BatchWork> work=new ArrayList<BatchWork>();
            for(Map.Entry<Object,List<Part>> entry:bounded.entrySet()) {
                List<Part> parts=entry.getValue();long vertices=0;boolean same=true;VertexLayout layout=parts.get(0).mesh.layout();
                for(Part part:parts){vertices+=part.mesh.vertexCount();same&=layout.equals(part.mesh.layout());}
                // Bound duplicated GPU storage; incompatible or exceptional geometry keeps the same Core draw path.
                if(parts.size()<2||vertices>batchVertexLimit||!same)continue;
                Batch cached=batches[pass].get(entry.getKey());if(cached!=null&&!cached.parts.equals(parts))cached=null;
                boolean needed=false;for(Part part:parts)if(visibleSet.contains(part)){needed=true;break;}
                // Do not allocate a never-visible group. Already-uploaded, still-resident groups
                // survive looking away; unload/rebuild invalidates their exact source membership.
                if(!needed){if(cached!=null)nextBatches.put(entry.getKey(),cached);continue;}
                Batch batch=cached!=null?cached:new Batch(parts);
                BatchWork pending=new BatchWork(batch);work.add(pending);nextBatches.put(entry.getKey(),batch);for(Part part:parts)combined.put(part,pending);
            }
            if(pass==0) {
                // Preserve opaque submission order too: only adjacent compatible ranges coalesce.
                for(Part part:visible){BatchWork batch=combined.get(part);if(batch==null){plan.add(part,0,part.mesh.indexCount());continue;}
                    int first=batch.cursor;for(int face=0;face<part.mesh.vertexCount()/part.faceWidth;face++)batch.append(part,face);
                    plan.add(batch.batch,first,part.mesh.indexCount());
                }
            }else {
                // Visibility changes do not change distance order. Filter the same resident face
                // order while turning in place; actual camera translation or publication re-sorts it.
                for (Face face : visible.isEmpty()?Collections.<Face>emptyList():faceOrder(pass,resident,x,y,z)) {
                    Part part = face.part;
                    if(!visibleSet.contains(part))continue;
                    BatchWork batch=combined.get(part);
                    if(batch!=null){int first=batch.cursor;batch.append(part,face.index);plan.add(batch.batch,first,part.indicesPerFace);continue;}
                    int first = part.cursor * part.indicesPerFace;
                    part.nextOrder[part.cursor++] = face.index;
                    plan.add(part, first, part.indicesPerFace);
                }
            }
            try {
                for(BatchWork pending:work){Batch batch=pending.batch;
                    // Fixed storage size/type, variable visible prefix. The unused tail is never
                    // drawn; a maximal valid index prevents UINT32 -> UINT16 demotion on a turn.
                    Arrays.fill(pending.indices,pending.cursor,pending.indices.length,batch.vertexCount-1);
                    if(batch.mesh==null){List<Mesh> sources=new ArrayList<Mesh>();for(Part part:batch.parts)sources.add(part.mesh);batch.mesh=pipeline.combine("chunk/batch/"+pass,sources,IndexData.of(pending.indices));batch.indices=pending.indices;}
                    else if(!Arrays.equals(batch.indices,pending.indices)){batch.mesh.updateIndices(IndexData.of(pending.indices));batch.indices=pending.indices;plan.uploads++;}
                }
            }catch(Throwable failure){for(BatchWork pending:work)if(!batches[pass].containsValue(pending.batch)&&pending.batch.mesh!=null)try{pending.batch.mesh.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
            for(Batch old:batches[pass].values())if(!nextBatches.containsValue(old))old.mesh.close();batches[pass]=nextBatches;
            for (Part part : pass==0?Collections.<Part>emptyList():visible) {
                if(combined.containsKey(part)){part.nextOrder=null;continue;}
                if (!Arrays.equals(part.order, part.nextOrder)) {
                    int[] indices = new int[part.mesh.indexCount()]; int cursor = 0;
                    for (int face : part.nextOrder) {
                        int first = face * part.faceWidth;
                        indices[cursor++] = first; indices[cursor++] = first + 1; indices[cursor++] = first + 2;
                        if (part.faceWidth == 4) { indices[cursor++] = first; indices[cursor++] = first + 2; indices[cursor++] = first + 3; }
                    }
                    part.mesh.updateIndices(IndexData.of(indices));
                    part.order = part.nextOrder; plan.uploads++;
                }
                part.nextOrder = null;
            }
        }
        return plan;
    }
    private List<Face> faceOrder(int pass,List<Part> resident,double x,double y,double z) {
        FaceOrder cached=faceOrders[pass];
        if(cached!=null&&cached.x==x&&cached.y==y&&cached.z==z)return cached.faces;
        List<Face> faces=new ArrayList<Face>();
        for(Part part:resident)for(int i=0;i<part.centers.length/3;i++) {
            double dx=part.key.x+part.centers[i*3]-x,dy=part.key.y+part.centers[i*3+1]-y,dz=part.key.z+part.centers[i*3+2]-z;
            // hypot avoids overflow for finite distant cameras; equal distances retain source order.
            faces.add(new Face(part,i,Math.hypot(Math.hypot(dx,dy),dz)));
        }
        Collections.sort(faces,(a,b)->Double.compare(b.distance,a.distance));
        faceOrders[pass]=new FaceOrder(x,y,z,faces);faceSorts++;return faces;
    }
    private void check() {
        context.checkOwner();
        if (closed || drawing) throw new IllegalStateException("Chunk renderer is closed or re-entered during drawing");
    }
    private void invalidate() { Arrays.fill(plans, null);Arrays.fill(faceOrders,null); }
    /** A solid-only rebuild does not change transparent geometry, visibility or distance order.
     * Invalidate every removed/new pass, including transitions to or from an empty pass. */
    private void invalidate(List<Part> parts){for(Part part:parts){int pass=part.material.pass;plans[pass]=null;faceOrders[pass]=null;}}
    public void close() {
        if (closed) return;
        if (context.isClosed()) { slots.clear();for(Map<Object,Batch> cache:batches)cache.clear();invalidate(); closed = true; return; }
        check();
        for (Slot slot : slots.values()) closeParts(slot.parts);
        for(Map<Object,Batch> cache:batches){for(Batch batch:cache.values())batch.mesh.close();cache.clear();}
        slots.clear(); invalidate(); closed = true;
    }
    private static void closeParts(List<Part> parts) {
        Throwable failure = null;
        for (Part part : parts) try { part.mesh.close(); }
        catch (Throwable problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
        if (failure instanceof RuntimeException) throw (RuntimeException)failure;
        if (failure instanceof Error) throw (Error)failure;
    }
    private static final class Key {
        final int x, y, z;
        Key(int x, int y, int z) {
            if (x % 16 != 0 || y % 16 != 0 || z % 16 != 0) throw new IllegalArgumentException("Unaligned chunk origin");
            this.x = x; this.y = y; this.z = z;
        }
        public boolean equals(Object value) { if (!(value instanceof Key)) return false; Key k = (Key)value; return x == k.x && y == k.y && z == k.z; }
        public int hashCode() { return (x * 31 + y) * 31 + z; }
    }
    private static final class Slot {
        Ticket ticket, published;
        List<Part> parts = Collections.emptyList();
    }
    private static final class Part {
        final Key key;
        final ChunkMaterial material;
        final Mesh mesh;
        final int faceWidth, indicesPerFace;
        final double[] centers, bounds;
        int[] order, nextOrder;
        int cursor;
        Part(Key key, ChunkMeshData.Part source, Mesh mesh) {
            this.key = key; this.material = source.material; this.mesh = mesh;
            faceWidth = source.faceWidth; indicesPerFace = faceWidth == 4 ? 6 : 3;
            centers = source.centers(); bounds = source.bounds();
            order = new int[centers.length / 3]; for (int i = 0; i < order.length; i++) order[i] = i;
        }
    }
    private static final class Face {
        final Part part; final int index; final double distance;
        Face(Part part, int index, double distance) { this.part = part; this.index = index; this.distance = distance; }
    }
    private static final class FaceOrder {
        final double x,y,z;final List<Face> faces;
        FaceOrder(double x,double y,double z,List<Face> faces){this.x=x;this.y=y;this.z=z;this.faces=faces;}
    }
    private static final class Run {
        final Part part; final Batch batch;final int first; int count;
        Run(Part part, int first, int count) { this.part = part;this.batch=null;this.first = first; this.count = count; }
        Run(Batch batch,int first,int count){this.part=batch.parts.get(0);this.batch=batch;this.first=first;this.count=count;}
    }
    private static final class Batch {
        final List<Part> parts;final Map<Part,Integer> offsets=new IdentityHashMap<Part,Integer>();final int indexCount,vertexCount;Mesh mesh;int[] indices;
        Batch(List<Part> parts){this.parts=new ArrayList<Part>(parts);int vertices=0,count=0;for(Part part:parts){offsets.put(part,vertices);vertices=Math.addExact(vertices,part.mesh.vertexCount());count=Math.addExact(count,part.mesh.indexCount());}indexCount=count;vertexCount=vertices;}
    }
    private static final class GroupKey {
        final Object material;final int segment;
        GroupKey(Object material,int segment){this.material=material;this.segment=segment;}
        @Override public boolean equals(Object other){if(!(other instanceof GroupKey))return false;GroupKey key=(GroupKey)other;return segment==key.segment&&material.equals(key.material);}
        @Override public int hashCode(){return material.hashCode()*31+segment;}
    }
    private static final class BatchWork {
        final Batch batch;final int[] indices;int cursor;
        BatchWork(Batch batch){this.batch=batch;indices=new int[batch.indexCount];}
        void append(Part part,int face){int first=batch.offsets.get(part)+face*part.faceWidth;indices[cursor++]=first;indices[cursor++]=first+1;indices[cursor++]=first+2;if(part.faceWidth==4){indices[cursor++]=first;indices[cursor++]=first+2;indices[cursor++]=first+3;}}
    }
    private static final class Plan {
        final ChunkFrustum frustum;
        final double x, y, z;
        final Set<Key> selection;
        final Object batchPolicy;
        final List<Run> runs = new ArrayList<Run>();
        int visible, culled, indices, uploads;
        Plan(ChunkFrustum frustum, double x, double y, double z, Set<Key> selection,Object batchPolicy) { this.frustum = frustum; this.x = x; this.y = y; this.z = z; this.selection = selection;this.batchPolicy=batchPolicy; }
        void add(Part part, int first, int count) {
            indices = Math.addExact(indices, count);
            if (!runs.isEmpty()) {
                Run last = runs.get(runs.size() - 1);
                if (last.batch==null && last.part == part && last.first + last.count == first) { last.count += count; return; }
            }
            runs.add(new Run(part, first, count));
        }
        void add(Batch batch,int first,int count){indices=Math.addExact(indices,count);if(!runs.isEmpty()){Run last=runs.get(runs.size()-1);if(last.batch==batch&&last.first+last.count==first){last.count+=count;return;}}runs.add(new Run(batch,first,count));}
    }
}
