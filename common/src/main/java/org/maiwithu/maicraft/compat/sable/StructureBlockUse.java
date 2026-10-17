// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.AimCheck;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 空手右键结构上的一格（座位、无线红石打字机）：转头看着它此刻在世界里的位置，再按地块坐标交给游戏——
 * 真人右键船上的方块时，Sable 的射线给的就是地块里的格，这里照样交。够不着、结构没了如实说；
 * 生效与否按给定条件确认，等确认时不点第二次。
 */
final class StructureBlockUse implements Action {

    /** 够得着的距离（眼睛到方块中心，格）：原版交互距离 4.5。 */
    static final double REACH = 4.5;
    /** 转头最多等几刻。 */
    static final int AIM_TICKS = 20;

    private final SimulatedCompat compat;
    private final String structureId;
    private final BlockPos local;
    private final InteractionConfirmation confirmation;
    private final String what;
    private final int confirmTicks;

    private PlayerContext last;
    private PendingInteraction pending;
    private int aimed;

    /**
     * @param local        要右键的那一格（地块坐标）
     * @param confirmation 什么时候算生效
     * @param what         说的时候叫它什么（座位、无线红石打字机）
     * @param confirmTicks 等确认最多几刻
     */
    StructureBlockUse(SimulatedCompat compat, String structureId, BlockPos local, InteractionConfirmation confirmation,
            String what, int confirmTicks) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.structureId = Objects.requireNonNull(structureId, "structureId");
        this.local = local.immutable();
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation");
        this.what = Objects.requireNonNull(what, "what");
        this.confirmTicks = confirmTicks;
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = tick.player();
        last = context;
        // 上下文过期、本刻交互机会已被占用时等下一刻。
        if (!context.isCurrent() || context.interactionSender() == null || !context.canInteractThisTick()) {
            return ActionStatus.running();
        }
        if (pending != null) {
            pending = context.interactionSender().poll(context, pending);
            return pending.terminal() ? settle() : ActionStatus.running();
        }
        LocalPlayer player = context.localPlayer();
        Optional<SableStructures.Structure> ship = compat.structure(context.level(), player, structureId);
        if (ship.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "结构不见了，" + what + "点不到"));
        }
        Vec3 center = Vec3.atCenterOf(local);
        Vec3 world = ship.get().pose().toWorld(center);
        Vec3 eye = player.getEyePosition();
        double distance = eye.distanceTo(world);
        if (distance > REACH) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "离" + what + " " + Math.round(distance * 10) / 10.0 + " 格，够不着"));
        }
        // 转头看着它此刻在世界里的位置；转到位（或等满）再交。
        context.input().halt(player);
        context.input().lookAt(player, world);
        if (!AimCheck.settled(player.getViewVector(1.0f), eye, world) && ++aimed < AIM_TICKS) {
            return ActionStatus.running();
        }
        // 点哪一面：从地块里看眼睛在格子的哪一边。
        Vec3 localEye = ship.get().pose().toLocal(eye);
        Direction face = Direction.getNearest((float) (localEye.x - center.x), (float) (localEye.y - center.y),
                (float) (localEye.z - center.z));
        Vec3 hitAt = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        pending = context.interactionSender().useBlock(context, InteractionHand.MAIN_HAND,
                new BlockHitResult(hitAt, face, local, false), confirmation, confirmTicks);
        return ActionStatus.progressed();
    }

    // 确认有了结论：生效算做完；游戏明说没生效、或结果说不清，都照实交上去，不点第二次。
    private ActionStatus settle() {
        return switch (pending.status()) {
            case CONFIRMED_APPLIED -> ActionStatus.done();
            case CONFIRMED_NOT_APPLIED -> ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "右键" + what + "没有生效" + detail()));
            default -> ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                    "右键了" + what + "，但没能确认生效" + detail()));
        };
    }

    private String detail() {
        return pending.detail() == null || pending.detail().isBlank() ? "" : "：" + pending.detail();
    }

    @Override public void pause() {
        if (last != null && last.isCurrent()) last.input().halt(last.localPlayer());
    }

    @Override public void close() {
        // 收尾时没等到确认的一次性提交按不确定交还，已发出的点击不假装撤销。
        if (pending != null && !pending.terminal() && last != null && last.interactionSender() != null) {
            last.interactionSender().retireOneShotForTaskBoundary(last, pending, "右键" + what + "没等到确认就结束了");
        }
        pause();
    }

    @Override public Interruptibility interruptibility() {
        return pending != null && !pending.terminal() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return "右键" + what;
    }
}
