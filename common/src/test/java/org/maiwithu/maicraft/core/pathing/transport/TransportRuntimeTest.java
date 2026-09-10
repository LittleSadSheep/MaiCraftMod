package org.maiwithu.maicraft.core.pathing.transport;

import java.lang.reflect.Proxy;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import sun.misc.Unsafe;

/**
 * 用简化交通会话检查谁能控制身体、每刻只推进一次、停止后的持续收尾、控制权丢失、菜单遮挡和受伤后的退出；不模拟真实电梯或飞行。
 */
public final class TransportRuntimeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Unsafe memory = (Unsafe) field.get(null);
        try {
            exclusiveOwner(memory);
            oncePerTickAndTerminal(memory);
            handoffWaitsForNextTick(memory);
            cancellationCleanup(memory);
            controlLoss(memory);
            modalScreen(memory);
            throwingSessionCleanup(memory);
            damageStopsFurtherTransport(memory);
            System.out.println("TransportRuntimeTest: passed");
        } finally { TransportRuntime.abandon(); }
    }

    private static void exclusiveOwner(Unsafe memory) throws Exception {
        var f = new Fixture(memory); var owner = new Object(); var session = new Session();
        f.allowed = false;
        check(!f.acquire(owner, session), "unowned controls cannot acquire a transport lease");
        f.allowed = true;
        check(f.acquire(owner, session), "first owner acquires the body");
        check(f.acquire(owner, session), "same owner and session acquire is idempotent");
        check(!f.acquire(owner, new Session()), "same owner cannot silently substitute an unacquired session");
        check(!f.acquire(new Object(), new Session()), "second owner cannot acquire an occupied body");
        check(!TransportRuntime.canSafelySuspendActive(), "airborne session controls the suspension boundary");
        TransportRuntime.cancel(new Object());
        check(session.stops == 0, "unrelated owner cannot cancel a session");
        TransportRuntime.abandon();
        check(!TransportRuntime.occupied() && session.abandons == 1 && f.callbacks == 1, "explicit abandon settles lease once");
    }

    private static void oncePerTickAndTerminal(Unsafe memory) throws Exception {
        var f = new Fixture(memory); var owner = new Object(); var session = new Session();
        check(f.acquire(owner, session), "acquire");
        var first = TransportRuntime.drive(owner, f.context);
        check(TransportRuntime.drive(owner, f.context) == first && session.ticks == 1, "duplicate drive in one tick must reuse result");
        session.complete = true; f.nextTick();
        check(TransportRuntime.drive(owner, f.context).state() == TransportSession.State.SUCCEEDED, "terminal result propagated");
        check(!TransportRuntime.occupied() && f.body.movement.equals(BodyControlPort.Movement.STOPPED), "terminal releases lease and injected input");
        check(f.callbacks == 1 && !f.ownedDuringCallback && !f.movingDuringCallback, "callback sees released body exactly once");
        check(TransportRuntime.drive(owner, f.context).code().equals("transport_not_owned") && session.ticks == 2, "completed session cannot drive again");
        check(!TransportRuntime.tickCleanup(f.context) && f.callbacks == 1, "completed cleanup is inert");
        check(TransportRuntime.canSafelySuspendActive(), "released transport is interruptible");
        check(TransportRuntime.diagnosticState().get("active").equals(false), "terminal diagnostic state");
    }

    private static void cancellationCleanup(Unsafe memory) throws Exception {
        var f = new Fixture(memory); var owner = new Object(); var session = new Session();
        f.acquire(owner, session); TransportRuntime.drive(owner, f.context);
        TransportRuntime.cancel(owner);
        check(session.stops == 1 && TransportRuntime.owns(owner), "cancel requests landing and retains lease");
        f.nextTick();
        check(TransportRuntime.tickCleanup(f.context) && session.cleanupTicks == 1 && TransportRuntime.occupied(), "orphaned cleanup continues until landing");
        check(TransportRuntime.tickCleanup(f.context) && session.cleanupTicks == 1, "cleanup obeys same tick drive limit");
        f.nextTick();
        check(TransportRuntime.tickCleanup(f.context) && !TransportRuntime.occupied(), "terminal cleanup still consumes this actor turn");
        check(session.cleanupTicks == 2 && session.abandons == 0 && f.callbacks == 1, "normal cancellation settles rather than abandoning airborne state");
        check(f.completed.code().equals("cancelled") && !f.completed.uncertain(), "confirmed controlled cleanup result retained");
    }

    private static void handoffWaitsForNextTick(Unsafe memory) throws Exception {
        var f = new Fixture(memory); var firstOwner = new Object(); var first = new Session(); first.complete = true;
        f.acquire(firstOwner, first);
        TransportRuntime.drive(firstOwner, f.context);
        var nextOwner = new Object(); var next = new Session();
        check(f.acquire(nextOwner, next), "terminal handoff can reserve the next session");
        TransportRuntime.drive(nextOwner, f.context);
        check(first.ticks == 1 && next.ticks == 0, "new lease must not bypass the per-body tick drive limit");
        f.nextTick(); TransportRuntime.drive(nextOwner, f.context);
        check(next.ticks == 1, "reserved session begins on the next actor tick");
        TransportRuntime.abandon();
        var callbackOwner = new Object(); var callbackSession = new Session(); callbackSession.complete = true;
        f.nextTick();
        check(TransportRuntime.acquire(callbackOwner, "jetpack", callbackSession, f.context,
                ignored -> { throw new IllegalStateException("fixture completion callback"); }), "callback fixture acquire");
        TransportRuntime.drive(callbackOwner, f.context);
        check(!TransportRuntime.occupied() && f.body.movement.equals(BodyControlPort.Movement.STOPPED),
                "throwing completion callback cannot strand the lease or input");
    }

    private static void controlLoss(Unsafe memory) throws Exception {
        for (int reason = 0; reason < 4; reason++) {
            var f = new Fixture(memory); var owner = new Object(); var session = new Session();
            f.acquire(owner, session); TransportRuntime.drive(owner, f.context); f.nextTick();
            if (reason == 0) f.allowed = false;
            if (reason == 1) f.epoch++;
            if (reason == 2) f.revision++;
            if (reason == 3) f.player.alive = false;
            TransportRuntime.observeControl(f.context);
            check(session.abandons == 1 && session.stops == 0 && session.ticks == 1, "control loss must abandon without another native tick: " + reason);
            check(!TransportRuntime.occupied() && f.callbacks == 1 && f.completed.uncertain(), "control loss releases lease and reports uncertainty");
            TransportRuntime.observeControl(f.context);
            check(session.abandons == 1 && f.callbacks == 1, "control loss cleanup is idempotent");
        }
    }

    private static void modalScreen(Unsafe memory) throws Exception {
        var f = new Fixture(memory); var owner = new Object(); var session = new Session(); f.acquire(owner, session);
        f.minecraft.screen = (PauseScreen) memory.allocateInstance(PauseScreen.class);
        check(TransportRuntime.drive(owner, f.context).code().equals("waiting_for_world_controls") && session.ticks == 0,
                "a modal screen must prevent all session actions");
        f.minecraft.screen = null;
        TransportRuntime.drive(owner, f.context);
        check(session.ticks == 0, "closing modal screen does not repeat an already consumed actor tick");
        f.nextTick(); f.minecraft.screen = (ChatScreen) memory.allocateInstance(ChatScreen.class);
        TransportRuntime.drive(owner, f.context);
        check(session.ticks == 1, "ChatScreen permits world movement and native transport controls");
        TransportRuntime.cancel(owner); f.nextTick();
        f.minecraft.screen = (PauseScreen) memory.allocateInstance(PauseScreen.class);
        check(TransportRuntime.tickCleanup(f.context) && session.cleanupTicks == 0, "cancelled session also waits behind modal UI");
        f.minecraft.screen = null;
        for (int i=0; i<2; i++) { f.nextTick(); TransportRuntime.tickCleanup(f.context); }
        check(!TransportRuntime.occupied() && f.callbacks == 1, "closing modal UI resumes controlled cleanup");
    }

    // 让退出和诊断故意抛异常，检查错误不会留下占用状态或阻挡下一次交通任务。
    private static void throwingSessionCleanup(Unsafe memory) throws Exception {
        var f = new Fixture(memory); var owner = new Object(); var session = new Session();
        session.throwAbandon = true; session.throwDiagnostics = true;
        f.acquire(owner, session);
        TransportRuntime.abandon();
        check(!TransportRuntime.occupied() && f.callbacks == 1, "faulty abandon/diagnostics must not strand the body or completion callback");
        var next = new Session(); f.acquire(new Object(), next);
        TransportRuntime.abandon();
        check(next.abandons == 1, "a cleanup error must not prevent later transport ownership");
    }

    private static void damageStopsFurtherTransport(Unsafe memory) throws Exception {
        var f = new Fixture(memory); var owner = new Object(); var session = new Session();
        f.player.health = 20; f.player.absorption = 4;
        f.acquire(owner, session); TransportRuntime.drive(owner, f.context);
        f.nextTick(); f.player.health = 21; f.player.absorption = 2;
        TransportRuntime.drive(owner, f.context);
        check(session.stops == 1 && TransportRuntime.occupied(), "damage must request controlled cleanup instead of releasing an airborne body");
        f.nextTick(); TransportRuntime.tickCleanup(f.context);
        check(!TransportRuntime.occupied() && f.completed.uncertain() && f.completed.code().equals("transport_damage_observed"),
                "healing must not mask absorption loss or permit another transport attempt after cleanup");
    }

    // 这个替身在收到停止请求后再更新两次才结束，用来验证运行时不会过早松开交通控制。
    private static final class Session implements TransportSession {
        int ticks, stops, abandons, cleanupTicks;
        boolean complete, throwAbandon, throwDiagnostics;
        public Result tick(LocalPlayerContext context) {
            ticks++;
            context.body().applyMovement(new BodyControlPort.Movement(1,0,false,false,false), context.tickRevision());
            if (stops > 0 && ++cleanupTicks >= 2) return Result.failed("cancelled", "landed", true, false);
            return complete ? Result.success("arrived") : Result.running("flying");
        }
        public void requestStop() { stops++; }
        public void abandon() { abandons++; if (throwAbandon) throw new IllegalStateException("fixture abandon"); }
        public boolean safeToInterrupt() { return false; }
        public boolean livenessActive() { return true; }
        public String phase() { return "fixture_flight"; }
        public Map<String,Object> diagnostics() {
            if (throwDiagnostics) throw new IllegalStateException("fixture diagnostics");
            return Map.of("ticks", ticks);
        }
    }

    private static final class Fixture {
        final Minecraft minecraft;
        final AlivePlayer player;
        final Body body = new Body();
        final LocalPlayerContext context;
        long tick = 1, epoch = 1, revision = 1;
        boolean allowed = true, ownedDuringCallback, movingDuringCallback;
        int callbacks;
        TransportSession.Result completed;
        Fixture(Unsafe memory) throws Exception {
            minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
            player = (AlivePlayer) memory.allocateInstance(AlivePlayer.class); player.alive = true;
            minecraft.player = player;
            context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                    new Class<?>[]{LocalPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "requireCurrent" -> null;
                        case "isCurrent" -> true;
                        case "permitsNativeActions", "mutationAvailable" -> allowed;
                        case "bodyEpoch" -> epoch;
                        case "controlRevision" -> revision;
                        case "tickRevision" -> tick;
                        case "minecraft" -> minecraft;
                        case "player" -> player;
                        case "body" -> body;
                        default -> throw new AssertionError("unexpected context access " + method.getName());
                    });
        }
        boolean acquire(Object owner, Session session) {
            return TransportRuntime.acquire(owner, "jetpack", session, context, result -> {
                callbacks++; completed = result; ownedDuringCallback = TransportRuntime.owns(owner);
                movingDuringCallback = !body.movement.equals(BodyControlPort.Movement.STOPPED);
            });
        }
        void nextTick() { tick++; body.movement = BodyControlPort.Movement.STOPPED; }
    }
    private static final class Body implements BodyControlPort {
        Movement movement = Movement.STOPPED;
        public boolean automationOwnsControls() { return true; }
        public void applyMovement(Movement movement, long revision) { this.movement = movement; }
        public void requestLook(float yaw, float pitch, long revision) { }
        public void clearLook() { }
        public void releaseAll() { movement = Movement.STOPPED; }
    }
    private static final class AlivePlayer extends LocalPlayer {
        boolean alive;
        float health = 20, absorption;
        private AlivePlayer() { super(null, null, null, null, null, false, false); }
        @Override public boolean isAlive() { return alive; }
        @Override public float getHealth() { return health; }
        @Override public float getAbsorptionAmount() { return absorption; }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
