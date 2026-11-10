package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import local.mcgl.render.GameGeometry;
import local.mcgl.render.GameRenderState;
import local.mcgl.render.IndexData;
import local.mcgl.render.Mesh;
import local.mcgl.render.MeshData;
import local.mcgl.render.MeshPipeline;
import local.mcgl.render.RenderSystem;

public final class MetalSceneModelProbe {
    public static void verify() {
        int mask = 2 | 4 | 16;
        ByteBuffer vertices = ByteBuffer.allocateDirect(3 * GameGeometry.FLOAT_LAYOUT.stride())
                .order(ByteOrder.nativeOrder());
        vertices.putFloat(0, .4f);
        vertices.putFloat(12, .8f);
        vertices.putFloat(28, .7f);
        MeshData source = MeshData.takeOwnedVertices(GameGeometry.FLOAT_LAYOUT, vertices, IndexData.of(new int[]{0, 1, 2}));
        MetalWorldCapture capture = MetalWorldCapture.open(new GameRenderState(), new GameRasterState(),
                Collections.emptySet());
        if (capture == null) throw new AssertionError("Metal model capture is unavailable");
        try (Mesh mesh = RenderSystem.current().meshes().create("test/held-item", source, MeshPipeline.Usage.STATIC)) {
            capture.rememberScene(mesh, source, mask);
            MeshData cached = capture.sceneMesh(mesh, mask);
            if (cached == null) throw new AssertionError("Missing initial item mesh");
            capture.forget(mesh);
            MeshData restored = capture.sceneMesh(mesh, mask);
            if (restored == null || restored == cached || !restored.vertices().equals(cached.vertices())
                    || restored.indices() != cached.indices() || capture.sceneMesh(mesh, mask) != restored)
                throw new AssertionError("Evicted item mesh was not restored and cached");
        } finally {
            capture.close();
        }
        System.out.println("METAL_SCENE_MODEL_RECOVERY_PASS");
    }
}
