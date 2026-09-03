import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ListIterator;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Moves MCGL's LWJGL fullscreen transition ahead of its fragile GUI resize.
 *
 * The obfuscated client catches an exception around the whole toggle method.
 * On macOS 15 its GUI resize can throw before Display.setFullscreen executes,
 * leaving a desktop-sized Cocoa window containing the final 900x700 frame.
 * This patch inserts the same Display.setFullscreen(boolean) call immediately
 * before that resize. The original later call is retained as a harmless no-op
 * when the resize succeeds.
 */
public final class PatchMCGLFullscreen {
    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: input.class output.class");
        }

        ClassNode owner = new ClassNode();
        try (FileInputStream input = new FileInputStream(args[0])) {
            new ClassReader(input).accept(owner, 0);
        }

        int patchedMethods = 0;
        for (MethodNode method : owner.methods) {
            MethodInsnNode fullscreenCall = null;
            FieldInsnNode fullscreenField = null;
            MethodInsnNode fragileResize = null;

            for (ListIterator<AbstractInsnNode> iterator = method.instructions.iterator(); iterator.hasNext();) {
                AbstractInsnNode instruction = iterator.next();
                if (!(instruction instanceof MethodInsnNode)) {
                    continue;
                }

                MethodInsnNode call = (MethodInsnNode)instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && "org/lwjgl/opengl/Display".equals(call.owner)
                        && "setFullscreen".equals(call.name)
                        && "(Z)V".equals(call.desc)) {
                    AbstractInsnNode previous = previousReal(call);
                    if (previous instanceof FieldInsnNode
                            && previous.getOpcode() == Opcodes.GETFIELD
                            && owner.name.equals(((FieldInsnNode)previous).owner)
                            && "Z".equals(((FieldInsnNode)previous).desc)) {
                        fullscreenCall = call;
                        fullscreenField = (FieldInsnNode)previous;
                    }
                }

                if (call.getOpcode() == Opcodes.INVOKESPECIAL
                        && owner.name.equals(call.owner)
                        && "(II)V".equals(call.desc)) {
                    fragileResize = call;
                }
            }

            if (fullscreenCall == null || fullscreenField == null || fragileResize == null) {
                continue;
            }
            if (fragileResize == fullscreenCall ||
                    method.instructions.indexOf(fragileResize) > method.instructions.indexOf(fullscreenCall)) {
                continue;
            }

            InsnList earlyToggle = new InsnList();
            earlyToggle.add(new VarInsnNode(Opcodes.ALOAD, 0));
            earlyToggle.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name,
                    fullscreenField.name, fullscreenField.desc));
            earlyToggle.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "org/lwjgl/opengl/Display", "setFullscreen", "(Z)V"));
            method.instructions.insertBefore(fragileResize, earlyToggle);
            patchedMethods++;
        }

        if (patchedMethods != 1) {
            throw new IllegalStateException("expected exactly one fullscreen method, patched " + patchedMethods);
        }

        ClassWriter outputClass = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(outputClass);
        try (FileOutputStream output = new FileOutputStream(args[1])) {
            output.write(outputClass.toByteArray());
        }
        System.out.println("Patched MCGL fullscreen method: 1");
    }
}
