package me.helvert.codebridge.uitest;

import java.lang.classfile.*;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;

/**
 * Test-process agent: position IDE frames before AWT can make them visible. Ships in no plugin.
 */
public final class WindowPlacementAgent {
    public static final String FRAME_CLASS = "com/intellij/openapi/wm/impl/IdeFrameImpl";

    public static void premain(String arguments, Instrumentation instrumentation) {
        int[] bounds = Arrays.stream(arguments.split(",")).mapToInt(Integer::parseInt).toArray();
        if (bounds.length != 4 || bounds[2] <= 0 || bounds[3] <= 0) {
            throw new IllegalArgumentException("Expected x,y,width,height for the test frame");
        }
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] bytes) {
                if (!FRAME_CLASS.equals(name)) return null;
                try {
                    return placeBeforeShow(bytes, bounds);
                } catch (Throwable failure) {
                    // Transformer exceptions normally let the original class load. That would put
                    // the window on the primary screen again, so fail this isolated IDE instead.
                    failure.printStackTrace(System.err);
                    Runtime.getRuntime().halt(1);
                    throw new AssertionError(failure);
                }
            }
        });
    }

    static byte[] placeBeforeShow(byte[] bytes, int[] bounds) {
        var model = ClassFile.of().parse(bytes);
        long showMethods = model.methods().stream()
                .filter(method -> method.methodName().equalsString("show")
                        && method.methodType().equalsString("()V") && method.code().isPresent()).count();
        if (showMethods != 1)
            throw new IllegalStateException("IDE frame no longer declares show(); placement hook needs updating");
        var parent = model.superclass().orElseThrow().asSymbol();
        return ClassFile.of().transformClass(model, ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString("show") && method.methodType().equalsString("()V"),
                new CodeTransform() {
                    @Override
                    public void atStart(CodeBuilder code) {
                        code.aload(0)
                                .loadConstant(bounds[0]).loadConstant(bounds[1]).loadConstant(bounds[2]).loadConstant(bounds[3])
                                .invokevirtual(parent, "setBounds", MethodTypeDesc.of(ConstantDescs.CD_void,
                                        ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
                    }

                    @Override
                    public void accept(CodeBuilder code, CodeElement element) {
                        code.with(element);
                    }
                }
        ));
    }
}
