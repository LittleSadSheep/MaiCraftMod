// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 驾驶位上的一套，试按与开车共用：腾空手 → 坐到驾驶座（没有、坐不上就站在旁边）→ 空手右键接管无线红石打字机、
 * 等服务器把它点亮（已经亮着是自己在用就接着用，别人在用不抢）；接管后让按着的键正好是要的那几个（只发变化的），
 * 每刻核对还接管着、还坐着；最后交还（和真人按 Esc 一样）。
 * 拿着染料右键座位会给座位染色，右键无线红石打字机要空手才接管，所以先腾手。
 */
final class TypewriterCockpit {

    /** 右键接管后等服务器把无线红石打字机点亮最多几刻。 */
    static final int CONNECT_TICKS = 200;
    /** 坐上座位等确认最多几刻。 */
    static final int SIT_TICKS = 40;

    private enum Step { HAND, SIT, CONNECT, READY }

    private final SimulatedCompat compat;
    private final PlayerServices services;
    private final String structureId;
    private final TypewriterDriver.Station station;
    private final Set<Integer> held = new HashSet<>();

    private Step step = Step.HAND;
    private Action current;
    private boolean connected;
    private boolean disconnectSent;
    private boolean seated;
    private String seat;

    TypewriterCockpit(SimulatedCompat compat, PlayerServices services, String structureId,
            TypewriterDriver.Station station) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.structureId = Objects.requireNonNull(structureId, "structureId");
        this.station = Objects.requireNonNull(station, "station");
        this.seat = station.seat() == null ? "坐着够得着无线红石打字机的地方没有座位，站在它旁边" : "";
    }

    /** 推进准备：准备好了是 done（再调还是 done）；做不下去 failed。 */
    ActionStatus prepare(TickContext tick) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，接管不了"));
        }
        if (current != null) {
            ActionStatus status = current.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            return afterAction(status);
        }
        return switch (step) {
            case HAND -> hand(player);
            case SIT -> sit(player);
            case CONNECT -> connect(level, player);
            case READY -> ActionStatus.done();
        };
    }

    // 子动作结束：坐不上座位改站着开（无线红石打字机够得着就行）；腾手、接管失败就停。
    private ActionStatus afterAction(ActionStatus status) {
        if (status instanceof ActionStatus.Failed failed) {
            if (step == Step.SIT) {
                seat = "座位坐不上（" + failed.problem().message() + "），站在无线红石打字机旁";
                step = Step.CONNECT;
                return ActionStatus.progressed();
            }
            return failed;
        }
        switch (step) {
            case HAND -> step = station.seat() != null ? Step.SIT : Step.CONNECT;
            case SIT -> {
                seat = "坐在驾驶座上";
                seated = true;
                step = Step.CONNECT;
            }
            default -> {
                connected = true;
                step = Step.READY;
            }
        }
        return ActionStatus.progressed();
    }

    private ActionStatus hand(LocalPlayer player) {
        if (player.getMainHandItem().isEmpty()) return afterAction(ActionStatus.done());
        Optional<Action> empty = services.toMainhand().emptyMainhand();
        if (empty.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INVENTORY_FULL, "腾不出空手：快捷栏和背包都没有空格"));
        }
        current = empty.get();
        return ActionStatus.progressed();
    }

    // 坐到驾驶座：已经坐着就不再点。
    private ActionStatus sit(LocalPlayer player) {
        if (player.isPassenger()) {
            seat = "坐在驾驶座上（本来就坐着）";
            seated = true;
            step = Step.CONNECT;
            return ActionStatus.progressed();
        }
        current = new StructureBlockUse(compat, structureId, station.seat(),
                context -> context.localPlayer() != null && context.localPlayer().isPassenger()
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING,
                "座位", SIT_TICKS);
        return ActionStatus.progressed();
    }

    // 接管：已经亮着是自己在用就接着用、是别人在用不抢；没亮就空手右键，等服务器把它点亮。
    private ActionStatus connect(ClientLevel level, LocalPlayer player) {
        BlockState state = compat.block(level, structureId, station.typewriter());
        if (state == null || !state.hasProperty(BlockStateProperties.POWERED)) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "无线红石打字机读不到了"));
        }
        if (state.getValue(BlockStateProperties.POWERED)) {
            if (!compat.typewriterUsedBy(level, structureId, station.typewriter(), player.getUUID())) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "无线红石打字机正被别人用着，不抢"));
            }
            connected = true;
            step = Step.READY;
            return ActionStatus.progressed();
        }
        current = new StructureBlockUse(compat, structureId, station.typewriter(),
                context -> lit(context.level()) ? InteractionConfirmation.Verdict.APPLIED
                        : InteractionConfirmation.Verdict.PENDING, "无线红石打字机", CONNECT_TICKS);
        return ActionStatus.progressed();
    }

    /** 这一刻还接管着、还坐着；不是的话给一句是哪一样，好的话为 null。 */
    String lost(ClientLevel level, LocalPlayer player) {
        if (!lit(level)) return "无线红石打字机断开了（开着界面、走远了都会断）";
        if (seated && !player.isPassenger()) return "从座位上掉下来了";
        return null;
    }

    /** 让按着的键正好是这些：新要的发按下，不再要的发松开。 */
    void hold(Set<Integer> keys) {
        for (Integer key : Set.copyOf(held)) {
            if (!keys.contains(key)) {
                compat.typewriterKey(station.typewriter(), key, false);
                held.remove(key);
            }
        }
        for (Integer key : keys) {
            if (held.add(key)) compat.typewriterKey(station.typewriter(), key, true);
        }
    }

    void releaseAll() {
        hold(Set.of());
    }

    /** 本次按下的键都松开了。 */
    boolean released() {
        return held.isEmpty();
    }

    /** 绑了频率的键；读不到为 null。 */
    Set<Integer> bound(ClientLevel level) {
        return compat.typewriterKeys(level, structureId, station.typewriter());
    }

    /** 交还：只发一次；之后它熄了才算交还了。 */
    void disconnect(ClientLevel level) {
        if (!connected || disconnectSent || level == null) return;
        releaseAll();
        compat.typewriterDisconnect(level, structureId, station.typewriter());
        disconnectSent = true;
    }

    /** 无线红石打字机这一刻亮着（服务器认它有人在用）。 */
    boolean lit(ClientLevel level) {
        BlockState state = level == null ? null : compat.block(level, structureId, station.typewriter());
        return state != null && state.hasProperty(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.POWERED);
    }

    boolean connected() {
        return connected;
    }

    String seat() {
        return seat;
    }

    /** 被打断：子动作停手、松开所有键；回来先重新核对还接管着。 */
    void pause() {
        if (current != null) current.pause();
        releaseAll();
        if (step == Step.READY) step = Step.CONNECT;
    }

    /** 收尾：松开所有键，接管着就交还。 */
    void close() {
        if (current != null) {
            current.close();
            current = null;
        }
        releaseAll();
        PlayerContext context = services.context().get();
        disconnect(context == null ? null : context.level());
    }

    Interruptibility interruptibility() {
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    String describe() {
        return switch (step) {
            case HAND -> "腾出空手";
            case SIT -> "坐到驾驶座";
            case CONNECT -> "接管无线红石打字机";
            case READY -> "坐在驾驶位上";
        };
    }
}
