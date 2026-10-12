import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.MethodNode;

/** Exact, reviewed fingerprints. Unknown bytecode still fails closed.
 * The October client is mapped to the patcher's staging namespace first.
 * Aliases below retain the old guard IDs, not the old implementation: every
 * instruction of the updated method remains present for the existing patches.
 */
public final class MCGLBytecodeFingerprint {
    public static byte[] bytes(MethodNode method) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(50, 1, "Fingerprint", null, "java/lang/Object", null);
        method.accept(writer); writer.visitEnd(); method.instructions.resetLabels();
        return writer.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
                result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static long of(MethodNode method) {
        byte[] bytes = bytes(method);
        CRC32 crc = new CRC32(); crc.update(bytes); long actual = crc.getValue();
        // Same instructions and decoded text; only upstream XOR string keys changed.
        if (actual == 4152550693L && sha256(bytes).equals("3df13ea676b0be4fc0beb3ce2028fac29972c6f3f1c82fe237ddaeac0d654d84")) return 1608031326L;
        if (actual == 3701355363L && sha256(bytes).equals("858408fe9ea8cb90e8542a66afe199acf1a92ce19078672a391c413519e1a075")) return 3166733268L;
        if (actual == 663153171L && sha256(bytes).equals("1124c5ab6360bb6efd98e559f93025f8a7f76ff53e143c7855a3f814483f8204")) return 4229781381L;
        // The same XOR-only changes after the performance/LWJGL/render passes.
        if (actual == 1350143644L && sha256(bytes).equals("d038e2b48fec760c30fd4627474bbb74fc1b9ca39305df18b818ad2de3b5b507")) return 1998616563L;
        if (actual == 1585198083L && sha256(bytes).equals("23cda3cf5ad9558d4a93d72e7a3765fc4273efbdf467b8871c2baeabe7106194")) return 1598646215L;
        if (actual == 1157596063L && sha256(bytes).equals("1c070b8736fa8ef154c9777f2374377e66a8215c413496315a667cb787169b65")) return 2521837969L;
        if (actual == 1020479862L && sha256(bytes).equals("67772587f7950bf6a9048c4e7c8d1abe1ff88f6ea379079565f2778501451b54")) return 2379906575L;
        if (actual == 2611993019L && sha256(bytes).equals("903b638a95b3ae1ffc71eb46a67b25f4fa5ba01b1626e82b7e27ed8ff9963de9")) return 2891509932L;
        if (actual == 692411407L && sha256(bytes).equals("892b4e762e192c9bbfdd30335164e7a943e011937932229e441e681e3d3759c0")) return 3518690118L;
        if (actual == 4181134665L && sha256(bytes).equals("da0061a1ce00916eaa78d944de2365b4e5ac4a9f4d9a4afc4cd712c28fe7c169")) return 3693971563L;
        if (actual == 1331350291L && sha256(bytes).equals("c4ac04df362fd5cf698f3bba5df1ea9c29b7c9e52fb7712a13b7d46e5274f82b")) return 2466055191L;
        // Upstream now selects each block's render pass using its metadata.
        // The existing capture hooks leave that selection and its new local intact.
        if (actual == 3033709152L && sha256(bytes).equals("f180d8047aee0c7ead44c8383fcd00fbe78a11609b3e3fe5299762c105f75a89")) return 165820837L;
        if (actual == 481278432L && sha256(bytes).equals("bc62e5e38b7e1b84a5383edd5bc9aad9cd6a15f1c263dfb6dbb70d3bd921167c")) return 97421622L;
        return actual;
    }

    public static boolean updatedQuadSort(MethodNode method) {
        return sha256(bytes(method)).equals("cbfab4ec34f42b266f0ddb40c90d5ad04225e02e01eb5a0aca8ed7bb7e37a406");
    }

    private MCGLBytecodeFingerprint() {}
}
