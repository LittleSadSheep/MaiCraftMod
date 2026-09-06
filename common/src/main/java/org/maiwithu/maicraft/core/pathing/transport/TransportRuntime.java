package org.maiwithu.maicraft.core.pathing.transport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** Single actor lease, including controlled landing/docking after the requesting task is cancelled. */
public final class TransportRuntime {
    private static Lease active;
    private static Map<String, Object> last = Map.of();
    private static long drivenEpoch = Long.MIN_VALUE, drivenTick = Long.MIN_VALUE;
    private static BodyControlPort drivenBody;

    private static final class Lease {
        final Object owner;
        final String mode;
        final TransportSession session;
        final Consumer<TransportSession.Result> completed;
        final long bodyEpoch;
        final long controlRevision;
        final BodyControlPort body;
        long lastTick = Long.MIN_VALUE;
        boolean cancelling;
        int errors;
        TransportSession.Result result = TransportSession.Result.running("starting");

        Lease(Object owner, String mode, TransportSession session, LocalPlayerContext context,
              Consumer<TransportSession.Result> completed) {
            this.owner = owner; this.mode = mode; this.session = session;
            this.bodyEpoch = context.bodyEpoch(); this.controlRevision = context.controlRevision();
            this.body = context.body(); this.completed = completed;
        }
    }

    private TransportRuntime() {}

    public static boolean acquire(Object owner, String mode, TransportSession session,
                                  LocalPlayerContext context, Consumer<TransportSession.Result> completed) {
        context.requireCurrent();
        if (!context.permitsNativeActions()) return false;
        if (active != null) return active.owner == owner && active.session == session;
        active = new Lease(owner, mode, session, context, completed);
        return true;
    }

    public static boolean owns(Object owner) { return active != null && active.owner == owner; }
    public static boolean occupied() { return active != null; }
    public static boolean canSafelySuspendActive() {
        if (active == null) return true;
        try { return active.session.safeToInterrupt(); } catch (RuntimeException unknown) { return false; }
    }

    public static void cancel(Object owner) {
        if (!owns(owner)) return;
        active.cancelling = true;
        try { active.session.requestStop(); }
        catch (RuntimeException failure) { abandon(); }
    }

    public static void suspendActive() {
        if (active != null) cancel(active.owner);
    }

    /** The client calls this before allowing another automation owner to acquire the body. */
    public static boolean tickCleanup(LocalPlayerContext context) {
        observeControl(context);
        if (active == null || !active.cancelling) return false;
        drive(active.owner, context);
        return true;
    }

    public static TransportSession.Result drive(Object owner, LocalPlayerContext context) {
        context.requireCurrent();
        observeControl(context);
        if (!owns(owner)) return TransportSession.Result.failed("transport_not_owned", "transport body lease ended", false, true);
        Lease lease = active;
        if (lease.lastTick == context.tickRevision()) return lease.result;
        if (drivenBody == context.body() && drivenEpoch == context.bodyEpoch()
                && drivenTick == context.tickRevision()) return lease.result;
        lease.lastTick = context.tickRevision();
        // Opening an unrelated GUI suspends control, but must not turn off a hovering pack or
        // invent an elevator arrival. Observation resumes when world controls are available.
        if (!lease.session.allowsCurrentScreen(context)) {
            return lease.result = TransportSession.Result.running("waiting_for_world_controls");
        }
        drivenBody = context.body(); drivenEpoch = context.bodyEpoch(); drivenTick = context.tickRevision();
        try {
            lease.result = lease.session.tick(context);
            lease.errors = 0;
        } catch (RuntimeException failure) {
            lease.cancelling = true;
            try { lease.session.requestStop(); } catch (RuntimeException ignored) { lease.errors = 3; }
            lease.result = TransportSession.Result.running("settling_after_transport_error");
            if (++lease.errors >= 3) {
                try { lease.session.abandon(); } catch (RuntimeException ignored) { }
                lease.result = TransportSession.Result.failed("transport_control_failed",
                        failure.getClass().getSimpleName() + ": " + failure.getMessage(), true, true);
            }
        }
        if (lease.result.terminal()) finish(lease);
        return lease.result;
    }

    public static void observeControl(LocalPlayerContext context) {
        if (active != null && (active.bodyEpoch != context.bodyEpoch()
                || active.controlRevision != context.controlRevision()
                || !context.permitsNativeActions() || !context.player().isAlive())) abandon();
    }

    /** Used on manual takeover, disconnect and death. It submits no native world action. */
    public static void abandon() {
        Lease lease = active;
        if (lease == null) return;
        try { lease.session.abandon(); }
        catch (RuntimeException failure) {
            org.maiwithu.maicraft.core.Constants.LOG.warn("Transport local cleanup failed", failure);
        } finally {
            lease.result = TransportSession.Result.failed("transport_control_transferred",
                    "transport stopped observing this body; any changed equipment modes need fresh observation", true, true);
            finish(lease);
        }
    }

    private static void finish(Lease lease) {
        if (active == lease) active = null;
        var description = description(lease);
        description.put("mode", lease.mode);
        description.put("state", lease.result.state().name().toLowerCase());
        description.put("code", lease.result.code());
        description.put("detail", lease.result.detail());
        description.put("uncertain", lease.result.uncertain());
        last = Map.copyOf(description);
        try { lease.body.releaseAll(); }
        catch (RuntimeException failure) { org.maiwithu.maicraft.core.Constants.LOG.warn("Transport input cleanup failed", failure); }
        try { lease.completed.accept(lease.result); }
        catch (RuntimeException failure) { org.maiwithu.maicraft.core.Constants.LOG.warn("Transport result callback failed", failure); }
    }

    public static Map<String, Object> diagnosticState() {
        if (active == null) return Map.of("active", false, "last_transport", last);
        var description = description(active);
        description.put("active", true);
        description.put("mode", active.mode);
        description.put("phase", active.session.phase());
        description.put("cleanup_pending", active.cancelling);
        description.put("safe_to_interrupt", canSafelySuspendActive());
        return description;
    }

    private static LinkedHashMap<String, Object> description(Lease lease) {
        var result = new LinkedHashMap<String, Object>();
        try { lease.session.diagnostics().forEach((key, value) -> { if (key != null && value != null) result.put(key, value); }); }
        catch (RuntimeException failure) { result.put("diagnostic_error", failure.getClass().getSimpleName()); }
        return result;
    }
}
