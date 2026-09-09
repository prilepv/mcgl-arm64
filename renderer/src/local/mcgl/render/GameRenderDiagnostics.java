package local.mcgl.render;

import java.util.Locale;

/** Optional per-context draw counts. No scene contents, coordinates, accounts or native queries. */
final class GameRenderDiagnostics {
    private final boolean enabled=Boolean.getBoolean("mcgl.graphics.profile");
    private long started,frames,draws,raw,vertices,chunkDraws,uploads,transientCreated,textGlyphs,textDraws;
    private long terrainParts,terrainBatches;
    void frame(GameRenderCommands game) {
        if(!enabled)return;
        long now=System.nanoTime();frames++;
        if(started==0){snapshot(game,now);return;}
        if(now-started<5_000_000_000L)return;
        System.out.println(String.format(Locale.ROOT,"[MCGL Core Draws] %.1fs frames=%d | per-frame geometry=%.1f raw-batches=%.1f vertices=%.1f terrain=%.1f text-glyphs=%.1f text-draws=%.1f | resident-chunks=%d index-uploads=%d transient-created=%d transient-live=%d/%.2fMiB | CPU submission counts, not GPU time",
                (now-started)/1e9,frames,(game.drawCalls()-draws)/(double)frames,(game.rawBatches()-raw)/(double)frames,
                (game.submittedVertices()-vertices)/(double)frames,(game.chunkDrawCalls()-chunkDraws)/(double)frames,
                (game.textGlyphs()-textGlyphs)/(double)frames,(game.textDraws()-textDraws)/(double)frames,
                game.residentChunks(),game.chunkIndexUploads()-uploads,game.transientMeshCreations()-transientCreated,game.transientMeshCount(),game.transientMeshBytes()/1048576.0));
        System.out.println(String.format(Locale.ROOT,"[MCGL Terrain Runs] per-frame batched-parts=%.1f multi-submissions=%.1f | arena-members=%d pages=%d bytes=%.2fMiB | pending-recovery=%d recovered-total=%d transfer-total=%.2fMiB | ordered CPU submissions, not GPU draw count or time",
                (game.terrainBatchParts()-terrainParts)/(double)frames,(game.terrainBatches()-terrainBatches)/(double)frames,
                game.terrainArenaMembers(),game.terrainArenaPages(),game.terrainArenaBytes()/1048576.0,
                game.terrainPendingRecoveries(),game.terrainRecoveries(),game.terrainRecoveryBytes()/1048576.0));
        snapshot(game,now);
    }
    private void snapshot(GameRenderCommands game,long now){started=now;frames=0;draws=game.drawCalls();raw=game.rawBatches();vertices=game.submittedVertices();chunkDraws=game.chunkDrawCalls();uploads=game.chunkIndexUploads();transientCreated=game.transientMeshCreations();textGlyphs=game.textGlyphs();textDraws=game.textDraws();terrainParts=game.terrainBatchParts();terrainBatches=game.terrainBatches();}
}
