package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import local.mcgl.render.MeshData;

/** Uploads immutable world resources once while consecutive frames refer to them. */
final class MetalWorldPlayback {
    private final MetalTerrainWindow window;
    private final IdentityHashMap<MeshData, MetalTerrainWindow.Resource> meshes = new IdentityHashMap<>();
    private final IdentityHashMap<MetalWorldFrame.Texture, MetalTerrainWindow.Resource> textures = new IdentityHashMap<>();
    private final double[] model = new double[16];
    private MetalTerrainWindow.Resource[] meshTable = new MetalTerrainWindow.Resource[0];
    private MetalTerrainWindow.Resource[] textureTable = new MetalTerrainWindow.Resource[0];
    private MetalWorldFrame uploaded;

    MetalWorldPlayback(MetalTerrainWindow window) { this.window = window; }

    boolean draw(MetalWorldFrame frame, ByteBuffer pixels) {
        for (MetalWorldFrame.Pass pass : frame.passes)
            if (pass.viewport != null || pass.scissor != null) return false;
        if (frame != uploaded) upload(frame);
        window.begin(frame.clear[0], frame.clear[1], frame.clear[2]);
        for (MetalWorldFrame.Pass pass : frame.passes) {
            window.pass(FloatBuffer.wrap(pass.material), pass.raster);
            for (MetalWorldFrame.Draw draw : pass.draws) {
                for (int i = 0; i < model.length; i++) model[i] = draw.model[i];
                window.draw(meshTable[draw.mesh], draw.mask, model, textureTable[draw.atlas], textureTable[draw.lightmap]);
            }
        }
        return pixels == null ? window.present(null) : window.presentImage(pixels);
    }

    private void upload(MetalWorldFrame frame) {
        retireUnused(meshes, frame.meshes);
        retireUnused(textures, frame.textures);
        meshTable = new MetalTerrainWindow.Resource[frame.meshes.size()];
        textureTable = new MetalTerrainWindow.Resource[frame.textures.size() + 1];
        for (int i = 0; i < meshTable.length; i++) {
            MeshData mesh = frame.meshes.get(i);
            MetalTerrainWindow.Resource resource = meshes.get(mesh);
            if (resource == null) {
                resource = window.geometry(mesh);
                meshes.put(mesh, resource);
            }
            meshTable[i] = resource;
        }
        for (int i = 1; i < textureTable.length; i++) {
            MetalWorldFrame.Texture texture = frame.textures.get(i - 1);
            MetalTerrainWindow.Resource resource = textures.get(texture);
            if (resource == null) {
                resource = window.texture(texture);
                textures.put(texture, resource);
            }
            textureTable[i] = resource;
        }
        uploaded = frame;
    }

    private static <T> void retireUnused(IdentityHashMap<T, MetalTerrainWindow.Resource> old, List<T> resources) {
        Set<T> current = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        current.addAll(resources);
        Iterator<Map.Entry<T, MetalTerrainWindow.Resource>> entries = old.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<T, MetalTerrainWindow.Resource> entry = entries.next();
            if (!current.contains(entry.getKey())) {
                entry.getValue().close();
                entries.remove();
            }
        }
    }
}
