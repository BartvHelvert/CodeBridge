package me.helvert.codebridge.uitest.trace;

import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

import static java.lang.constant.ConstantDescs.*;

/** Observes the pinned IDE's implementation through JDK instrumentation; links no platform API. */
public final class EscapeTraceAgent {
    static final String ROBOT = "com/jetbrains/performancePlugin/remotedriver/robot/InputEventsRobot";
    static final String IDE_QUEUE = "com/intellij/ide/IdeEventQueue";
    static final String AWT_QUEUE = "java/awt/EventQueue";
    static final String INPUT_EVENT = "java/awt/event/InputEvent";
    private static final Set<String> TARGETS = Set.of(ROBOT, IDE_QUEUE, AWT_QUEUE, INPUT_EVENT);
    private static final ClassDesc TRACE = ClassDesc.of("me.helvert.codebridge.uitest.trace.EscapeTrace");
    private static final MethodTypeDesc EVENT = MethodTypeDesc.of(CD_void, CD_String, CD_Object, CD_Object);

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        // Only the small observer is on Boot-Class-Path, so bootstrap EventQueue can call it too.
        var observer = Class.forName("me.helvert.codebridge.uitest.trace.EscapeTrace", false, null);
        var desktop = ModuleLayer.boot().findModule("java.desktop").orElseThrow();
        instrumentation.redefineModule(desktop, Set.of(observer.getModule()), Map.of(), Map.of(), Set.of(), Map.of());
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] bytes) {
                if (!TARGETS.contains(name)) return null;
                try {
                    byte[] result = instrument(name, bytes, loader);
                    System.setProperty("codebridge.escape.trace." + name, "installed");
                    System.err.println("ESCAPE_TRACE installed " + name + " pid=" + ProcessHandle.current().pid());
                    return result;
                } catch (Throwable failure) {
                    // A missing hook must be visible in the isolated test IDE, never silently look
                    // like a key that was not posted. Leave the implementation untouched on failure.
                    System.err.println("ESCAPE_TRACE INSTALL FAILED " + name);
                    System.setProperty("codebridge.escape.trace." + name, "failed");
                    failure.printStackTrace(System.err);
                    return null;
                }
            }
        }, true);
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (TARGETS.contains(loaded.getName().replace('.', '/'))) instrumentation.retransformClasses(loaded);
        }
    }

    static byte[] instrument(String target, byte[] bytes) {
        return instrument(target, bytes, EscapeTraceAgent.class.getClassLoader());
    }

    static byte[] instrument(String target, byte[] bytes, ClassLoader loader) {
        // Parse class resources without initializing IDE classes inside their own transformer.
        var platform = ClassHierarchyResolver.ofResourceParsing(ClassLoader.getPlatformClassLoader());
        var hierarchy = loader == null ? platform : ClassHierarchyResolver.ofResourceParsing(loader).orElse(platform);
        var format = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy.cached()));
        var model = format.parse(bytes);
        int[] hooks = {0};
        byte[] result = format.transformClass(model, (builder, element) -> {
            if (element instanceof MethodModel method) {
                builder.transformMethod(method, MethodTransform.transformingCode(
                new CodeTransform() {
                    final String name = method.methodName().stringValue();
                    final String descriptor = method.methodType().stringValue();
                    final boolean consumption = target.equals(INPUT_EVENT) && name.equals("consume") && descriptor.equals("()V");
                    final boolean keyCall = target.equals(ROBOT) &&
                            (name.equals("doPressAndReleaseKey") || name.equals("postKeyEvents")) && descriptor.equals("(II)V");
                    final boolean event = (target.equals(ROBOT) && name.equals("postInputEvent") &&
                            descriptor.equals("(Ljava/awt/event/InputEvent;)V")) ||
                            (!target.equals(ROBOT) && Set.of("postEvent", "dispatchEvent", "_dispatchEvent",
                                    "dispatchByCustomDispatchers", "dispatchKeyEvent").contains(name) &&
                                    (descriptor.startsWith("(Ljava/awt/AWTEvent;)") || descriptor.startsWith("(Ljava/awt/event/KeyEvent;)")));

                    @Override
                    public void atStart(CodeBuilder code) {
                        if (keyCall || event || consumption) {
                            hooks[0]++;
                            log(code, "enter");
                        }
                    }

                    void log(CodeBuilder code, String phase) {
                        if (consumption && phase.equals("return")) {
                            code.aload(0).invokestatic(TRACE, "consumption", MethodTypeDesc.of(CD_void, CD_Object));
                            return;
                        }
                        String stage = target + "." + name + " " + phase;
                        code.loadConstant(stage);
                        if (keyCall) {
                            code.iload(1).aload(0).invokestatic(TRACE, "keyCall",
                                    MethodTypeDesc.of(CD_void, CD_String, CD_int, CD_Object));
                        } else {
                            code.aload(consumption ? 0 : 1).aload(0).invokestatic(TRACE, "event", EVENT);
                        }
                    }

                    @Override
                    public void accept(CodeBuilder code, CodeElement element) {
                        if ((keyCall || event || consumption) && element instanceof ReturnInstruction) log(code, "return");
                        if (target.equals(IDE_QUEUE) && element instanceof InvokeInstruction call && observedCallback(call)) {
                            hooks[0]++;
                            // Save the existing receiver/argument, call exactly once, then observe a
                            // duplicate of the result. Preserve the original result and exception path.
                            int argument = code.allocateLocal(TypeKind.REFERENCE);
                            int receiver = code.allocateLocal(TypeKind.REFERENCE);
                            code.astore(argument).astore(receiver).aload(receiver).aload(argument).with(element).dup()
                                    .aload(receiver).aload(argument)
                                    .loadConstant(target + "." + name + " -> " + call.owner().asInternalName() + "." + call.name().stringValue());
                            boolean boxed = call.type().stringValue().endsWith("Ljava/lang/Object;");
                            code.invokestatic(TRACE, boxed ? "objectResult" : "booleanResult",
                                    MethodTypeDesc.of(CD_void, boxed ? CD_Object : CD_boolean, CD_Object, CD_Object, CD_String));
                        } else code.with(element);
                    }
                }));
            } else builder.with(element);
        });
        if (hooks[0] == 0) throw new IllegalStateException("No matching Escape hooks in " + target);
        var errors = format.verify(result);
        if (!errors.isEmpty()) throw new IllegalStateException("Invalid Escape hooks: " + errors);
        return result;
    }

    private static boolean observedCallback(InvokeInstruction call) {
        String owner = call.owner().asInternalName();
        String name = call.name().stringValue();
        String type = call.type().stringValue();
        return ((name.equals("dispatch") || name.equals("dispatchKeyEvent")) &&
                (type.equals("(Ljava/awt/AWTEvent;)Z") || type.equals("(Ljava/awt/event/KeyEvent;)Z")) &&
                !owner.equals(IDE_QUEUE)) ||
                (owner.equals("kotlin/jvm/functions/Function1") && name.equals("invoke") &&
                        type.equals("(Ljava/lang/Object;)Ljava/lang/Object;"));
    }
}
