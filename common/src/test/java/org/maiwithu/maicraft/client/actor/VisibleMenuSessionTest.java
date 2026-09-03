package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Proxy;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;

/** Completion must wait for the visible GUI's close receipt, including failure and no-op paths. */
public final class VisibleMenuSessionTest {
    public static void main(String[] args) {
        Harness harness = new Harness();
        VisibleMenuSession unused = new VisibleMenuSession();
        check(unused.close(harness.context), "an unused session needs no close");
        check(harness.closes == 0, "unused session must not touch another task's menu");

        VisibleMenuSession session = new VisibleMenuSession();
        check(!session.ready(harness.context), "wait for the GUI to become visible");
        harness.visible = true;
        check(session.ready(harness.context), "allow operations only after the GUI is ready");
        check(!session.close(harness.context), "close submission is not completion");
        check(!session.close(harness.context), "wait while the close receipt is pending");
        check(harness.closes == 1, "never submit duplicate closes while awaiting confirmation");
        harness.receipt.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "closed");
        check(session.close(harness.context), "complete after confirmed close");
        check(session.close(harness.context), "confirmed completion is idempotent");
        check(harness.closes == 1, "completion must not reopen or reclose a GUI");

        VisibleMenuSession interrupted = new VisibleMenuSession();
        check(interrupted.ready(harness.context), "second session is ready");
        check(!interrupted.close(harness.context), "second close awaits its own receipt");
        harness.receipt.finish(MenuReceipt.Status.UNCERTAIN, "control revoked");
        try {
            interrupted.close(harness.context);
            throw new AssertionError("an uncertain close must never report successful completion");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("control revoked"), "retain the close failure detail");
        }
        System.out.println("VisibleMenuSessionTest: passed");
    }

    private static final class Harness {
        boolean visible;
        int closes;
        MenuReceipt receipt;
        final LocalPlayerContext context;

        Harness() {
            MenuPort menus = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(),
                    new Class<?>[]{MenuPort.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "ensureVisible" -> visible;
                        case "close" -> {
                            closes++;
                            receipt = new MenuReceipt(MenuReceipt.Kind.CLOSE,
                                    (LocalPlayerContext) args[0], 0, 0, 20, true, null);
                            yield receipt;
                        }
                        case "poll" -> args[1];
                        default -> throw new AssertionError("unexpected menu operation: " + method.getName());
                    });
            context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                    new Class<?>[]{LocalPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "menus" -> menus;
                        case "bodyEpoch", "controlRevision", "tickRevision" -> 1L;
                        default -> throw new AssertionError("unexpected actor access: " + method.getName());
                    });
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
