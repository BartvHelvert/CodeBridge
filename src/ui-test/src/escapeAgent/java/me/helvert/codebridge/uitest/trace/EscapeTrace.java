package me.helvert.codebridge.uitest.trace;

import java.awt.AWTEvent;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;

/** Bootstrap-visible, test-only observer. Never posts, consumes, or redispatches an event. */
public final class EscapeTrace {
    private EscapeTrace() {}

    public static void keyCall(String stage, int code, Object robot) {
        if (code != KeyEvent.VK_ESCAPE) return;
        try {
            write(stage + " robot=" + identity(robot) + " focus=" +
                    identity(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
        } catch (Throwable failure) {
            failed(failure);
        }
    }

    public static void event(String stage, Object candidate, Object actor) {
        if (!(candidate instanceof KeyEvent key) || key.getKeyCode() != KeyEvent.VK_ESCAPE) return;
        try {
            write(stage + " event=" + identity(key) + " id=" + key.getID() + " when=" + key.getWhen() +
                    " consumed=" + key.isConsumed() + " modifiers=" + key.getModifiersEx() +
                    " source=" + identity(key.getSource()) + " actor=" + identity(actor) +
                    " focus=" + identity(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
        } catch (Throwable failure) {
            failed(failure);
        }
    }

    /** Receives a copy of the original return value; instrumentation leaves it on the operand stack. */
    public static void booleanResult(boolean result, Object actor, Object event, String site) {
        event(site + " returned=" + result, event, actor);
    }

    public static void consumption(Object candidate) {
        if (!(candidate instanceof KeyEvent key) || key.getKeyCode() != KeyEvent.VK_ESCAPE) return;
        try {
            event("java/awt/event/InputEvent.consume return", key, key);
            write("consume stack=" + StackWalker.getInstance().walk(frames ->
                    frames.skip(1).limit(12).map(Object::toString).toList()));
        } catch (Throwable failure) {
            failed(failure);
        }
    }

    public static void objectResult(Object result, Object actor, Object event, String site) {
        // The queue's posting listeners return a boxed Boolean. Avoid invoking arbitrary toString().
        event(site + " returned=" + (result instanceof Boolean ? result : identity(result)), event, actor);
    }

    private static String identity(Object value) {
        return value == null ? "null" : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static void write(String message) {
        System.err.println("ESCAPE_TRACE pid=" + ProcessHandle.current().pid() + " nano=" + System.nanoTime() +
                " thread=" + Thread.currentThread().getName() + " " + message);
    }

    private static void failed(Throwable failure) {
        // Diagnostic failure must not replace the event's original behavior.
        try { System.err.println("ESCAPE_TRACE observer failure: " + failure.getClass().getName()); }
        catch (Throwable ignored) { }
    }
}
