package org.maiwithu.maicraft.core.pathing.transport;

import java.util.Map;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** One native transport leg, driven by the same first-person actor as ordinary navigation. */
public interface TransportSession {
    enum State { RUNNING, SUCCEEDED, FAILED }

    record Result(State state, String code, String detail, boolean effectsStarted, boolean uncertain) {
        public boolean terminal() { return state != State.RUNNING; }
        public static Result running(String phase) {
            return new Result(State.RUNNING, phase, "", false, false);
        }
        public static Result success(String detail) {
            return new Result(State.SUCCEEDED, "arrived", detail, true, false);
        }
        public static Result failed(String code, String detail, boolean effectsStarted, boolean uncertain) {
            return new Result(State.FAILED, code, detail, effectsStarted, uncertain);
        }
    }

    /** Called at most once per actor tick; all native effects use that tick's action ports. */
    Result tick(LocalPlayerContext context);

    /** Request a controlled exit. The runtime continues ticking this session until it settles. */
    void requestStop();

    /** Manual takeover or body/world loss: release owned local inputs without further world actions. */
    void abandon();

    /** True only when pausing can safely hand the body to another automation owner. */
    boolean safeToInterrupt();

    /** Native movement, observed vehicle progress or a pending confirmed action keeps the task alive. */
    boolean livenessActive();

    String phase();

    /** Current measured state and limitations; an estimate must not claim a completed journey. */
    Map<String, Object> diagnostics();

    /** Only an adapter's own staged inventory transaction may temporarily use a non-world GUI. */
    default boolean allowsCurrentScreen(LocalPlayerContext context) {
        return org.maiwithu.maicraft.client.actor.DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen);
    }
}
