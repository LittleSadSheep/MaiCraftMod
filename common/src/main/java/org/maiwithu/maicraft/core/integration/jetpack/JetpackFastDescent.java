// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;

/**
 * 沿已对准的落点加快下降：短且无伤时暂时关背包，较高时提前重启悬停，最后用潜行下降。
 * 地形改变或请求停止时转入恢复飞行模式，已经开始的恢复不能因超时就丢掉。
 */
public final class JetpackFastDescent {
    enum Phase { IDLE, DISABLING, FALLING, SHORT_FALL, ENABLING, BRAKING, DONE }
    enum Command { NONE, OFF, ON, HOVER }
    record Observation(long tick, Vec3 position, Vec3 velocity, Vec3 landing,
                       JetpackNativeAdapter.Snapshot power, boolean clearColumn, boolean upright,
                       boolean grounded, int ping, Command receiptCommand, NativeActionReceipt.Status receiptStatus, boolean harmlessDrop) {
        Observation(long tick,Vec3 position,Vec3 velocity,Vec3 landing,JetpackNativeAdapter.Snapshot power,
                    boolean clearColumn,boolean upright,boolean grounded,int ping,Command command,NativeActionReceipt.Status status) {
            this(tick,position,velocity,landing,power,clearColumn,upright,grounded,ping,command,status,false);
        }
    }

    private final LongSet forbidden;
    private Phase phase = Phase.IDLE;
    private Command receiptCommand = Command.NONE;
    private NativeActionReceipt receipt;
    private Vec3 descentLanding, descentExit;
    private long phaseTick, progressTick;
    private double lowestHeight = Double.POSITIVE_INFINITY;
    private boolean stopping, recovering, effects, touchdown = true;
    private boolean shortDrop, modeChanges;
    private String detail = "awaiting an aligned descent column";
    private double restartHeight;

    public JetpackFastDescent() { this(LongSets.emptySet()); }
    public JetpackFastDescent(LongSet forbidden) { this.forbidden = forbidden; }
    public boolean active() { return phase != Phase.IDLE && phase != Phase.DONE; }
    public boolean hasEffects() { return effects; }
    public boolean hasModeChanges() { return modeChanges; }
    public boolean finished() { return phase == Phase.DONE; }
    public void requestStop() { stopping = true; if (phase == Phase.IDLE) phase = Phase.DONE; }
    /** Manual control/body loss revokes native authority; leave mode restoration to the new owner. */
    public void abandon() { phase = Phase.DONE; receipt = null; }
    public String phase() { return phase.name().toLowerCase(java.util.Locale.ROOT); }
    public Map<String, Object> diagnostics() {
        return Map.of("phase", phase(), "detail", detail, "restart_height", Double.isFinite(restartHeight) ? restartHeight : "unavailable",
                "recovering", recovering, "effects_started", effects, "touchdown", touchdown);
    }

    /** Returns true while this sequence owns this tick's input, including its final recovery tick. */
    public boolean tick(LocalPlayerContext ctx, Vec3 landing, JetpackNativeAdapter.Snapshot power, boolean allowStart) {
        return tick(ctx, landing, power, allowStart, true);
    }
    public boolean tick(LocalPlayerContext ctx, Vec3 landing, JetpackNativeAdapter.Snapshot power,
                        boolean allowStart, boolean touchdown) {
        return tick(ctx,landing,power,allowStart,touchdown,JetpackRoute.observed(ctx,forbidden));
    }
    /** Use the same fresh static/physical-deck geometry as the owning flight session. */
    public boolean tick(LocalPlayerContext ctx, Vec3 landing, JetpackNativeAdapter.Snapshot power,
                        boolean allowStart, boolean touchdown, JetpackRoute.Space space) {
        ctx.requireCurrent();
        if (finished() || !active() && !allowStart) return false;
        var player = ctx.player();
        if (receipt != null && !receipt.terminal()) ctx.actions().poll(ctx, receipt);
        boolean landingMode = active() ? this.touchdown : touchdown;
        boolean column = columnAvailable(space, player.position(), landing, landingMode) && !player.isInWater()
                && !player.isPassenger() && !player.isFallFlying();
        if (!landingMode) {
            Vec3 exit = landing == null ? null : space.landingBelow(landing.add(0, 0.1, 0));
            if (!active()) descentExit = exit;
            column &= exit != null && descentExit != null && exit.distanceToSqr(descentExit) < 0.0001;
        }
        var info = ctx.connection().getPlayerInfo(player.getUUID());
        // 当前把已下落距离先加进参数，伤害预算又因最后的 true 再加一次；短落差可能因此被误判为有伤害。
        boolean harmless=landing!=null && org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget.capture(player).damage(
                player.fallDistance+Math.max(0,player.getY()-landing.y),
                org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget.Landing.ORDINARY,true)<=0;
        var observation = new Observation(ctx.tickRevision(), player.position(), player.getDeltaMovement(), landing,
                power, column, JetpackNativeAdapter.uprightActive(JetpackNativeAdapter.activeEvidence(player)),
                player.onGround(), info == null ? -1 : info.getLatency(), receiptCommand,
                receipt == null ? null : receipt.status(),harmless);
        boolean wasActive = active();
        Command command = advance(observation, allowStart, touchdown);
        if (!wasActive && !active()) return false;
        if (command != Command.NONE && ctx.mutationAvailable()) {
            if (receipt != null && !receipt.terminal()) {
                ctx.actions().retireOneShotForTaskBoundary(ctx, receipt,
                        "fast descent changed to mode recovery before the previous switch settled");
            }
            receipt = JetpackNativeAdapter.setMode(ctx, command == Command.HOVER, command != Command.OFF);
            receiptCommand = command;
            effects = modeChanges = true;
        }
        Vec3 position = player.position(), velocity = player.getDeltaMovement();
        // No UP during the restart window: released UP lets native hover clamp a fast downward velocity.
        boolean sneak = phase == Phase.BRAKING;
        if (sneak) effects = true;
        ctx.body().applySteering(yaw -> {
            var brake = power.controllable()
                    ? sneak ? shiftSteering(position, velocity, landing, yaw, power)
                            : JetpackSteering.toward(position, velocity, position, yaw, true, power)
                    : BodyControlPort.Movement.STOPPED;
            return new BodyControlPort.Movement(brake.forward(), brake.strafe(), false, sneak, false);
        }, player.getYRot(), ctx.tickRevision());
        return true;
    }

    /** The same sequence is exercised without a game client by the timing/state regressions. */
    Command advance(Observation o, boolean allowStart) {
        return advance(o, allowStart, true);
    }
    // 根据当前高度、惯性、模式确认和落点是否改变，决定关背包、开背包或继续下降；这里本身不发送协议。
    Command advance(Observation o, boolean allowStart, boolean touchdown) {
        if (finished()) return Command.NONE;
        var power = o.power();
        boolean aligned = o.landing() != null && aligned(o.position(), o.velocity(), o.landing());
        if (phase == Phase.IDLE) {
            String rejection = !allowStart || stopping ? "descent not requested"
                    : !o.clearColumn() ? "landing column or exit not verified"
                    : !aligned ? "aligning position and horizontal momentum with descent column"
                    : o.grounded() ? "already grounded"
                    : !o.upright() || !power.controllable() || !power.active() || !power.hover() ? "native upright hover unavailable"
                    : o.ping() > 2000 ? "native control latency exceeds descent budget" : null;
            if (rejection != null) { detail = rejection; return Command.NONE; }
            restartHeight = restartHeight(o.velocity().y, power.gravity(), o.ping());
            double height = o.position().y - o.landing().y;
            shortDrop=touchdown && height>=.35 && height<3 && o.harmlessDrop();
            boolean freeDrop = shortDrop || height >= Math.max(3, restartHeight + 0.75);
            if (o.velocity().y > 0.1 || !freeDrop && height <= (touchdown ? .1 : shiftReleaseHeight(power, o.ping()))) {
                detail = "coasting upward or inside the final hover-braking margin"; return Command.NONE;
            }
            this.touchdown = touchdown;
            descentLanding = o.landing();
            lowestHeight = height; progressTick = o.tick();
            enter(freeDrop ? Phase.DISABLING : Phase.BRAKING, o.tick(), freeDrop
                    ? "disabling over the aligned landing column" : "native shift descent without a mode toggle");
        }
        boolean columnChanged = !o.clearColumn() || !aligned || o.landing() == null
                || o.landing().distanceToSqr(descentLanding) > 0.0001;
        if (stopping || columnChanged || !power.controllable() || !power.hover() || shortDrop && !o.harmlessDrop() && !o.grounded()) {
            recovering = true;
            if (phase != Phase.ENABLING) enter(Phase.ENABLING, o.tick(), "restoring hover after cancellation or changed landing");
        }
        double height = o.position().y - descentLanding.y;
        if (height < lowestHeight - .01) { lowestHeight = height; progressTick = o.tick(); }
        restartHeight = restartHeight(o.velocity().y, power.gravity(), o.ping());
        if (!shortDrop && (phase == Phase.DISABLING || phase == Phase.FALLING)
                && (height <= restartHeight || o.grounded() || o.tick() - phaseTick >= 60)) {
            enter(Phase.ENABLING, o.tick(), "restarting before the predicted control latency reaches the platform");
        }
        switch (phase) {
            case DISABLING -> {
                if (confirmed(o, Command.OFF) && !power.active()) enter(shortDrop ? Phase.SHORT_FALL : Phase.FALLING, o.tick(), "native free descent");
                else if (!pending(o, Command.OFF)) return Command.OFF;
            }
            case FALLING -> {
                if (power.active()) { recovering = true; enter(Phase.ENABLING, o.tick(), "active mode changed during free descent"); }
            }
            case SHORT_FALL -> {
                if(o.grounded()) enter(Phase.DONE,o.tick(),"harmless short touchdown; owning flight restores its requested modes");
                else if(power.active()) { recovering=true; enter(Phase.ENABLING,o.tick(),"active mode changed during short descent"); }
            }
            case ENABLING -> {
                if (!power.hover()) return pending(o, Command.HOVER) ? Command.NONE : Command.HOVER;
                if (confirmed(o, Command.ON) && power.active() && (o.upright() || o.grounded())) {
                    boolean atCruiseHeight = !this.touchdown && height <= shiftReleaseHeight(power, o.ping());
                    enter(recovering || o.grounded() || atCruiseHeight ? Phase.DONE : Phase.BRAKING, o.tick(),
                            recovering || atCruiseHeight ? "hover restored; returning to normal altitude control"
                                    : "upright native restart observed; shift descent");
                } else if (!pending(o, Command.ON)) return Command.ON;
                if (o.tick() - phaseTick >= 60) detail = "recovery_stalled: retaining control until the native pack is enabled";
            }
            case BRAKING -> {
                if (!power.active() || !o.upright() && !o.grounded()) {
                    recovering = true; enter(Phase.ENABLING, o.tick(), "restart context changed; release shift and restore hover");
                } else if (o.grounded()) enter(Phase.DONE, o.tick(), "platform touchdown observed");
                else if (!this.touchdown && height <= shiftReleaseHeight(power, o.ping())) {
                    enter(Phase.DONE, o.tick(), "releasing shift above the cruise height; returning to normal altitude control");
                }
                else if (o.tick() - progressTick >= 40) {
                    recovering = true; enter(Phase.ENABLING, o.tick(), "native shift descent stopped making height progress; restoring hover");
                }
            }
            default -> {}
        }
        // State transitions into recovery send the ON command on this tick, never a tick after the threshold.
        if (phase == Phase.ENABLING) {
            Command wanted = power.hover() ? Command.ON : Command.HOVER;
            return pending(o, wanted) ? Command.NONE : wanted;
        }
        return Command.NONE;
    }

    static boolean aligned(Vec3 position, Vec3 velocity, Vec3 landing) {
        return Math.hypot(position.x - landing.x, position.z - landing.z) < 0.18
                && velocity.horizontalDistance() < 0.06;
    }
    /** Correct drift only when the next native impulse stays inside the verified descent alignment. */
    static BodyControlPort.Movement shiftSteering(Vec3 position, Vec3 velocity, Vec3 landing, float yaw,
                                                  JetpackNativeAdapter.Snapshot power) {
        var correction = JetpackSteering.toward(position, velocity, landing, yaw, true, power);
        var next = JetpackMotion.step(position, velocity, correction, yaw, power);
        return aligned(next.position(), next.velocity(), landing) ? correction
                : JetpackSteering.toward(position, velocity, position, yaw, true, power);
    }
    static boolean columnAvailable(JetpackRoute.Space space, Vec3 position, Vec3 landing) {
        return columnAvailable(space, position, landing, true);
    }
    // 核对正下方通道和目标通道；只降到中间巡航高度时，也要求更下面还有一处已知落地出口。
    static boolean columnAvailable(JetpackRoute.Space space, Vec3 position, Vec3 landing, boolean touchdown) {
        if (landing == null || touchdown && !JetpackRoute.supportsLanding(space, landing)) return false;
        Vec3 directlyBelow = new Vec3(position.x, landing.y, position.z);
        Vec3 observed = space.landingBelow(directlyBelow.add(0, 0.1, 0));
        return observed != null && (touchdown ? observed.distanceToSqr(directlyBelow) < 0.0001 : observed.y <= landing.y + 0.01)
                && space.clear(position, directlyBelow) && space.clear(position, landing);
    }
    /** Installed FlightLib 3.2.1 DOWN clamps displacement to max(rawVy, -hoverVerticalSpeed).
     * Allow the measured RTT, a threshold-crossing tick and two input ticks before releasing DOWN.
     */
    static double shiftReleaseHeight(JetpackNativeAdapter.Snapshot power, int pingMillis) {
        int ticks = 3 + (int)Math.ceil((pingMillis < 0 ? 150 : Math.min(10000, pingMillis)) / 50D);
        return 0.1 + power.vertical() * ticks - power.hoverDescent();
    }
    /** MC air travel displaces by raw velocity, then applies gravity and 0.98 drag. Include a full
     * measured RTT, two receipt observations and two tick-order margins before native hover braking.
     */
    static double restartHeight(double rawVy, double gravity, int pingMillis) {
        if (!Double.isFinite(rawVy) || !Double.isFinite(gravity) || gravity <= 0) return Double.POSITIVE_INFINITY;
        int ticks = 4 + (int) Math.ceil((pingMillis < 0 ? 150 : Math.min(10000, pingMillis)) / 50D);
        double drop = 0;
        for (int tick = 0; tick < ticks; tick++) {
            drop += Math.max(0, -rawVy);
            rawVy = (rawVy - gravity) * 0.98;
        }
        return 0.9 + drop;
    }
    private static boolean confirmed(Observation o, Command command) {
        return o.receiptCommand() == command && o.receiptStatus() == NativeActionReceipt.Status.CONFIRMED_APPLIED;
    }
    private static boolean pending(Observation o, Command command) {
        return o.receiptCommand() == command && o.receiptStatus() == NativeActionReceipt.Status.PENDING;
    }
    private void enter(Phase next, long tick, String message) { phase = next; phaseTick = tick; detail = message; }
}
