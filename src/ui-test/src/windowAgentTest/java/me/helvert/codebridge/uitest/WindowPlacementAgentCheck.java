package me.helvert.codebridge.uitest;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Exercises the actual transformed show() ordering without constructing any AWT window.
 */
public final class WindowPlacementAgentCheck {
    static void main(String[] args) throws Exception {
        var parent = ClassDesc.of(FakeFrame.class.getName());
        var emptyMethod = MethodTypeDesc.of(ConstantDescs.CD_void);
        byte[] original = ClassFile.of().build(ClassDesc.of(WindowPlacementAgent.FRAME_CLASS.replace('/', '.')), builder -> builder
                .withSuperclass(parent)
                .withMethodBody("<init>", emptyMethod, ClassFile.ACC_PUBLIC,
                        code -> code.aload(0).invokespecial(parent, "<init>", emptyMethod).return_())
                .withMethodBody("show", emptyMethod, ClassFile.ACC_PUBLIC,
                        code -> code.aload(0).invokespecial(parent, "show", emptyMethod).return_()));
        int[] bounds = {-1600, 200, 1400, 1000};
        byte[] transformed = WindowPlacementAgent.placeBeforeShow(original, bounds);
        Class<?> frame = new ClassLoader(WindowPlacementAgentCheck.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(null, transformed, 0, transformed.length);
            }
        }.define();
        frame.getMethod("show").invoke(frame.getConstructor().newInstance());
        // Also load the untouched class: the real -javaagent must transform it during class loading.
        Class<?> instrumented = new ClassLoader(WindowPlacementAgentCheck.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(WindowPlacementAgent.FRAME_CLASS.replace('/', '.'), original, 0, original.length);
            }
        }.define();
        instrumented.getMethod("show").invoke(instrumented.getConstructor().newInstance());
        // Reject incompatible IDE classes rather than silently launching an unpositioned frame.
        byte[] unsupported = ClassFile.of().build(ClassDesc.of("UnsupportedFrame"), builder -> builder.withSuperclass(parent));
        try {
            WindowPlacementAgent.placeBeforeShow(unsupported, bounds);
            throw new AssertionError("Missing show() was accepted");
        } catch (IllegalStateException expected) {
            // Expected: the agent terminates the test IDE if its hook cannot be installed.
        }
        for (String argument : args) {
            try (var jar = new JarFile(Path.of(argument).toFile())) {
                var entry = jar.getJarEntry(WindowPlacementAgent.FRAME_CLASS + ".class");
                if (entry == null) throw new AssertionError("No IDE frame in " + argument);
                try (var input = jar.getInputStream(entry)) {
                    ClassFile.of().parse(WindowPlacementAgent.placeBeforeShow(input.readAllBytes(), bounds));
                }
            }
            System.out.println("Placement hook matches " + argument);
        }
        System.out.println("Frame placement precedes show(); no UI was launched.");
    }

    // Called by the placement hook the agent writes into the class's bytecode.
    @SuppressWarnings("unused")
    public static class FakeFrame {
        private boolean placed;

        public void setBounds(int x, int y, int width, int height) {
            placed = x == -1600 && y == 200 && width == 1400 && height == 1000;
        }

        public void show() {
            if (!placed) throw new AssertionError("Frame became visible before placement");
        }
    }
}
