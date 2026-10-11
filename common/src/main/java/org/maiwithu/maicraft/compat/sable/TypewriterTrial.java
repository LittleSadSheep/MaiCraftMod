// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 接管无线红石打字机逐条试按：驾驶位准备好（腾手、坐下、接管，见 {@link TypewriterCockpit}）→
 * 每条输入：先什么都不按看 8 刻（记下本来的漂移）→ 按住给定刻数 → 松开再看 10 刻（原生有延迟）→
 * 每 5 刻记一次结构挪了多少、转了多少 → 全部按完交还无线红石打字机。
 * 无线红石打字机原生不回报"收端通电了没有"，按下去只说明键发出去了；载具怎么动看样本。
 * 没绑频率的键不按（按了什么也不发，还会让装了电脑模组的服务器出错），照实写进经过。
 * 中途结构不见了、无线红石打字机断开、从座位上掉下来：先松开本次按着的键再停，写明是哪一样，不硬抢。
 */
final class TypewriterTrial implements VehicleDriver.Run {

    /** 按之前什么都不按看几刻。 */
    static final int NEUTRAL_TICKS = 8;
    /** 松开后再看几刻：原生从按键到收端、到桨叶转起来有延迟。 */
    static final int AFTER_TICKS = 10;
    /** 每几刻记一次样本。 */
    static final int SAMPLE_EVERY = 5;
    /** 交还后等无线红石打字机熄掉最多几刻。 */
    static final int DISCONNECT_TICKS = 40;

    /** 一条要按的输入：原样的名字、键码（不认得为 -1）、按几刻。 */
    record Planned(String control, int key, int ticks) {}

    private enum Step { PREPARE, NEUTRAL, HOLD, AFTER, DISCONNECT }

    private final SimulatedCompat compat;
    private final PlayerServices services;
    private final String structureId;
    private final TypewriterCockpit cockpit;
    private final List<Planned> planned;
    private final List<VehicleDriver.Tried> tried = new ArrayList<>();
    private final List<VehicleDriver.Sample> samples = new ArrayList<>();

    private Step step = Step.PREPARE;
    private int index;
    private long stepStart = Long.MIN_VALUE;
    private long pressedAt;
    private SableStructures.Pose neutralPose;
    private SableStructures.Pose pressPose;
    private SableStructures.Pose lastPose;
    private boolean disconnected;
    private Set<Integer> bound;
    private VehicleDriver.Report report;

    TypewriterTrial(SimulatedCompat compat, PlayerServices services, String structureId, TypewriterDriver.Station station,
            List<Planned> planned) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.structureId = Objects.requireNonNull(structureId, "structureId");
        this.cockpit = new TypewriterCockpit(compat, services, structureId, station);
        this.planned = List.copyOf(planned);
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null || context.input() == null) {
            return stop(null, Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，试按不了"));
        }
        long now = level.getGameTime();
        if (stepStart == Long.MIN_VALUE) stepStart = now;
        Optional<SableStructures.Structure> ship = compat.structure(level, player, structureId);
        if (ship.isEmpty()) return stop(level, Problem.of(Problem.Kind.TARGET_GONE, "结构不见了"));
        lastPose = ship.get().pose();
        if (step == Step.PREPARE) {
            ActionStatus prepared = cockpit.prepare(tick);
            if (prepared instanceof ActionStatus.Failed failed) return stop(level, failed.problem());
            if (!(prepared instanceof ActionStatus.Done)) return prepared;
            return go(Step.NEUTRAL, now);
        }
        if (step != Step.DISCONNECT) {
            // 按的过程中每刻核对：还接管着无线红石打字机、还坐在座位上。
            String lost = cockpit.lost(level, player);
            if (lost != null) return stop(level, Problem.of(Problem.Kind.STUCK, lost));
        }
        return switch (step) {
            case NEUTRAL -> neutral(level, now);
            case HOLD -> hold(now);
            case AFTER -> after(now);
            case DISCONNECT -> disconnect(level, now);
            case PREPARE -> ActionStatus.running();
        };
    }

    // 先不按看 8 刻：记下本来的漂移，好从反应里扣掉。认不出、没绑的键这里跳过并写明。
    private ActionStatus neutral(ClientLevel level, long now) {
        if (index >= planned.size()) return go(Step.DISCONNECT, now);
        if (bound == null) bound = cockpit.bound(level);
        Planned input = planned.get(index);
        String skip = skipReason(input);
        if (skip != null) {
            tried.add(new VehicleDriver.Tried(input.control(), input.ticks(), false, true, "", null, skip, List.of()));
            index++;
            return go(Step.NEUTRAL, now);
        }
        if (neutralPose == null) {
            neutralPose = lastPose;
            stepStart = now;
        }
        if (now - stepStart < NEUTRAL_TICKS) return ActionStatus.running();
        pressPose = lastPose;
        pressedAt = now;
        samples.clear();
        sample(now, true);
        cockpit.hold(Set.of(input.key()));
        return go(Step.HOLD, now);
    }

    // 按住给定刻数，每 5 刻记一次；到了松开。
    private ActionStatus hold(long now) {
        Planned input = planned.get(index);
        if ((now - pressedAt) % SAMPLE_EVERY == 0) sample(now, true);
        if (now - pressedAt < input.ticks()) return ActionStatus.running();
        cockpit.releaseAll();
        return go(Step.AFTER, now);
    }

    // 松开后再看 10 刻；看完算这一条的反应，接着下一条。
    private ActionStatus after(long now) {
        Planned input = planned.get(index);
        boolean last = now - stepStart >= AFTER_TICKS;
        if ((now - pressedAt) % SAMPLE_EVERY == 0 || last) sample(now, false);
        if (!last) return ActionStatus.running();
        VehicleDriver.Effect effect = effect(now - pressedAt);
        tried.add(new VehicleDriver.Tried(input.control(), input.ticks(), true, true,
                response(effect, now - pressedAt), effect, "", samples));
        index++;
        return go(Step.NEUTRAL, now);
    }

    // 全部按完：交还无线红石打字机，等它熄掉。
    private ActionStatus disconnect(ClientLevel level, long now) {
        cockpit.disconnect(level);
        if (!cockpit.lit(level)) {
            disconnected = true;
            return finish("按完交还了无线红石打字机");
        }
        if (now - stepStart >= DISCONNECT_TICKS) return finish("交还无线红石打字机后 " + DISCONNECT_TICKS / 20 + " 秒它还亮着");
        return ActionStatus.running();
    }

    private String skipReason(Planned input) {
        if (!TypewriterKeys.addressed(input.control())) {
            return "不是无线红石打字机的输入（这辆只认 打字机:键，例如 打字机:W、打字机:空格、打字机:上）";
        }
        if (input.key() == TypewriterKeys.UNKNOWN) {
            return "认不出是哪个键（字母、数字，或 空格、上、下、左、右、回车、shift、ctrl）";
        }
        if (bound != null && !bound.contains(input.key())) {
            return "这个键没绑频率，没按" + (bound.isEmpty() ? "（这台无线红石打字机一个键都没绑）"
                    : "（绑了的键：" + TypewriterKeys.names(bound) + "）");
        }
        return null;
    }

    // 一个样本：相对按下那一刻，世界里挪了多少、绕结构自己的轴转了多少。
    private void sample(long now, boolean held) {
        StructureGeometry.Change change = StructureGeometry.change(pressPose, lastPose);
        samples.add(new VehicleDriver.Sample((int) (now - pressedAt), held, round(change.moved().x, 100),
                round(change.moved().y, 100), round(change.moved().z, 100), round(change.turnDegrees().x, 10),
                round(change.turnDegrees().y, 10), round(change.turnDegrees().z, 10)));
    }

    // 这一条的效果：从按下到松开后看完，扣掉按之前那 8 刻的漂移，沿结构自己的轴挪了多少、绕它们转了多少。
    private VehicleDriver.Effect effect(long elapsed) {
        StructureGeometry.Change total = StructureGeometry.change(pressPose, lastPose);
        StructureGeometry.Change drift = StructureGeometry.change(neutralPose, pressPose);
        double scale = (double) elapsed / NEUTRAL_TICKS;
        Vec3 moved = total.alongOwnAxes().subtract(drift.alongOwnAxes().scale(scale));
        Vec3 turned = total.turnDegrees().subtract(drift.turnDegrees().scale(scale));
        return new VehicleDriver.Effect(round(moved.x, 100), round(moved.y, 100), round(moved.z, 100),
                round(turned.x, 10), round(turned.y, 10), round(turned.z, 10));
    }

    // 一句话的反应：沿结构自己的轴挪了多少、绕哪根轴转了多少；太小的不提。
    private static String response(VehicleDriver.Effect effect, long elapsed) {
        List<String> parts = new ArrayList<>();
        axis(parts, "沿结构 ", "x", effect.x(), 0.1, " 格");
        axis(parts, "沿结构 ", "y", effect.y(), 0.1, " 格");
        axis(parts, "沿结构 ", "z", effect.z(), 0.1, " 格");
        axis(parts, "绕结构 ", "x 轴", effect.turnX(), 0.5, "°");
        axis(parts, "绕结构 ", "y 轴", effect.turnY(), 0.5, "°");
        axis(parts, "绕结构 ", "z 轴", effect.turnZ(), 0.5, "°");
        String text = parts.isEmpty() ? "看不出动（挪不到 0.1 格、转不到 0.5°）" : String.join("，", parts);
        return text + "（" + elapsed + " 刻内，已扣掉按之前的漂移）";
    }

    private static void axis(List<String> parts, String lead, String name, double value, double least, String unit) {
        if (Math.abs(value) < least) return;
        parts.add(lead + (value > 0 ? "+" : "-") + name + " " + Math.abs(round(value, 10)) + unit);
    }

    static double round(double value, int scale) {
        return Math.round(value * scale) / (double) scale;
    }

    private ActionStatus go(Step next, long now) {
        step = next;
        stepStart = now;
        if (next == Step.NEUTRAL) neutralPose = null;
        return ActionStatus.progressed();
    }

    // 停：先松开按着的键，接管着就交还；没按到的几条写进经过。
    private ActionStatus stop(ClientLevel level, Problem problem) {
        cockpit.releaseAll();
        cockpit.disconnect(level);
        writeReport(problem.message());
        return ActionStatus.failed(problem);
    }

    private ActionStatus finish(String note) {
        writeReport(note);
        return ActionStatus.done();
    }

    private void writeReport(String note) {
        List<String> left = planned.subList(Math.min(index, planned.size()), planned.size()).stream()
                .map(Planned::control).toList();
        String rest = left.isEmpty() || note.startsWith("按完") ? "" : "；没按到：" + String.join("、", left);
        report = new VehicleDriver.Report(structureId, cockpit.seat(), tried, cockpit.released(), disconnected,
                note + rest, null);
    }

    @Override public VehicleDriver.Report report() {
        return report;
    }

    // 被打断：先松开按着的键，这一条回到"先不按看 8 刻"重来；回来先核对还接管着。
    @Override public void pause() {
        cockpit.pause();
        if (step == Step.NEUTRAL || step == Step.HOLD || step == Step.AFTER) {
            step = Step.PREPARE;
            stepStart = Long.MIN_VALUE;
        }
    }

    @Override public void close() {
        cockpit.close();
        if (report == null) writeReport("试按被收掉了");
    }

    @Override public Interruptibility interruptibility() {
        if (step == Step.PREPARE) return cockpit.interruptibility();
        // 按着键时载具在动，停下前要先松键：这时只让必须立刻处理的事插进来。
        return step == Step.HOLD || step == Step.AFTER ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return switch (step) {
            case PREPARE -> cockpit.describe();
            case NEUTRAL, HOLD, AFTER -> index < planned.size() ? "试按 " + planned.get(index).control() : "试按";
            case DISCONNECT -> "交还无线红石打字机";
        };
    }
}
