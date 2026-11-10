package me.helvert.codebridge.uitest.trace;

import java.awt.AWTEvent;
import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URLClassLoader;
import java.util.jar.JarFile;

/** Checks observer behavior and transformed bytecode without launching an IDE. */
public final class EscapeTraceAgentCheck {
    public static void main(String[] args) throws Exception {
        byte[] original;
        try (var input = EscapeTraceAgentCheck.class.getResourceAsStream("EscapeTraceAgentCheck$FakeQueue.class")) {
            original = input.readAllBytes();
        }
        byte[] transformed = EscapeTraceAgent.instrument(EscapeTraceAgent.IDE_QUEUE, original);
        Class<?> fixture = new ClassLoader(EscapeTraceAgentCheck.class.getClassLoader()) {
            Class<?> define() { return defineClass(FakeQueue.class.getName(), transformed, 0, transformed.length); }
        }.define();
        var callback = new Consumer();
        var queue = fixture.getConstructor(Callback.class).newInstance(callback);
        var source = new Canvas();
        var escape = new KeyEvent(source, KeyEvent.KEY_PRESSED, 42, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
        var ordinary = new KeyEvent(source, KeyEvent.KEY_PRESSED, 43, 0, KeyEvent.VK_A, 'a');
        var output = new ByteArrayOutputStream();
        var previous = System.err;
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            Object result = fixture.getMethod("dispatchByCustomDispatchers", AWTEvent.class).invoke(queue, escape);
            if (!Boolean.TRUE.equals(result) || callback.calls != 1 || !escape.isConsumed())
                throw new AssertionError("Observation changed the consumer's result, call count, or consumption");
            fixture.getMethod("dispatchByCustomDispatchers", AWTEvent.class).invoke(queue, ordinary);
            if (callback.calls != 2 || !ordinary.isConsumed()) throw new AssertionError("Ordinary key behavior changed");
            callback.consumes = false;
            var untouched = new KeyEvent(source, KeyEvent.KEY_RELEASED, 45, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
            result = fixture.getMethod("dispatchByCustomDispatchers", AWTEvent.class).invoke(queue, untouched);
            if (!Boolean.FALSE.equals(result) || callback.calls != 3 || untouched.isConsumed())
                throw new AssertionError("Observation changed a non-consuming callback");
            var marker = new IllegalStateException("original callback failure");
            Callback throwing = event -> { throw marker; };
            var throwingQueue = fixture.getConstructor(Callback.class).newInstance(throwing);
            try {
                fixture.getMethod("dispatchByCustomDispatchers", AWTEvent.class).invoke(throwingQueue, untouched);
                throw new AssertionError("Callback failure was swallowed");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                if (expected.getCause() != marker) throw expected;
            }
            EscapeTrace.keyCall("robot", KeyEvent.VK_ESCAPE, queue);
            EscapeTrace.keyCall("ordinary robot", KeyEvent.VK_A, queue);
            // Exercise the actual bootstrap-transformed EventQueue and its module access. Its
            // target has no focus, so only queue delivery (not an action's result) is asserted.
            EventQueue.invokeAndWait(() -> EventQueue.class.getName());
            var posted = new KeyEvent(source, KeyEvent.KEY_RELEASED, 44, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
            java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(posted);
            EventQueue.invokeAndWait(() -> { });
        } finally {
            System.setErr(previous);
        }
        String log = output.toString(StandardCharsets.UTF_8);
        for (String expected : new String[]{"returned=true", "returned=false", "consumed=true", "source=java.awt.Canvas@",
                "actor=" + Consumer.class.getName(), "java/awt/EventQueue.postEvent enter",
                "java/awt/EventQueue.dispatchEvent enter", "consume stack=", "robot robot="}) {
            if (!log.contains(expected)) throw new AssertionError("Missing " + expected + " in:\n" + log);
        }
        if (log.contains("ordinary robot") || log.contains("observer failure")) throw new AssertionError(log);
        for (String root : args) {
            try (var paths = Files.walk(Path.of(root)); var loader = new URLClassLoader(
                    paths.filter(path -> path.toString().endsWith(".jar")).map(path -> {
                        try { return path.toUri().toURL(); }
                        catch (java.net.MalformedURLException failure) { throw new IllegalArgumentException(failure); }
                    }).toArray(java.net.URL[]::new), EscapeTraceAgentCheck.class.getClassLoader())) {
                verifyJar(Path.of(root, "lib/intellij.platform.ide.impl.jar"), EscapeTraceAgent.IDE_QUEUE, loader);
                // IDE plugin loaders need not expose JDK class resources. Exercise that boundary,
                // rather than letting URLClassLoader's bootstrap delegation hide a missing fallback.
                var pluginResources = new ClassLoader(loader) {
                    @Override
                    public java.io.InputStream getResourceAsStream(String name) {
                        return name.startsWith("java/") || name.startsWith("javax/") ? null : super.getResourceAsStream(name);
                    }
                };
                verifyJar(Path.of(root, "plugins/performanceTesting/lib/modules/intellij.performanceTesting.remoteDriver.jar"), EscapeTraceAgent.ROBOT, pluginResources);
            }
        }
        System.out.println("Escape hooks preserve delivery, consumer results, and ordinary-key behavior; pinned classes verified.");
    }

    private static void verifyJar(Path path, String target, ClassLoader loader) throws Exception {
        try (var jar = new JarFile(path.toFile()); var input = jar.getInputStream(jar.getJarEntry(target + ".class"))) {
            EscapeTraceAgent.instrument(target, input.readAllBytes(), loader);
        }
        System.out.println("Verified hooks for " + target);
    }

    public interface Callback { boolean dispatch(AWTEvent event); }
    public static class Consumer implements Callback {
        public int calls;
        public boolean consumes = true;
        public boolean dispatch(AWTEvent event) {
            calls++;
            if (consumes) ((KeyEvent) event).consume();
            return consumes;
        }
    }
    public static class FakeQueue {
        public final Callback callback;
        public FakeQueue(Callback callback) { this.callback = callback; }
        public boolean dispatchByCustomDispatchers(AWTEvent event) { return callback.dispatch(event); }
    }
}
