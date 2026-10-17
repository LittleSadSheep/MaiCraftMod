// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.game.interaction.Interaction;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 打一下展示框的生产实现：展示框按原版的类认（发光展示框、模组的展示框都是它的子类），
 * 走近交给站位与靠近（按实体的够得着规则），打一下走交互层的原生左键攻击，
 * 以框里空了或框没了为打成的确认。
 */
public final class ClientItemFrameHits implements HitsItemFrames {

    /** 打一下的耐心（刻）：转头、等攻击条回满、等游戏确认都算在里面；打不着就如实说。 */
    private static final int HIT_PATIENCE_TICKS = 100;

    private final Supplier<PlayerContext> context;
    private final BringsPlayerClose approaches;

    public ClientItemFrameHits(Supplier<PlayerContext> context, BringsPlayerClose approaches) {
        this.context = Objects.requireNonNull(context, "context");
        this.approaches = Objects.requireNonNull(approaches, "approaches");
    }

    @Override
    public Optional<String> contents(int frame) {
        return frameOf(context.get(), frame).map(found -> found.getItem().isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(found.getItem().getItem()).toString());
    }

    @Override
    public Action approach(int frame, Permissions permissions) {
        // 框挂在墙上，站位按它此刻的包围盒找；框已经不在了就没有可走的地方。
        return frameOf(context.get(), frame)
                .map(found -> approaches.toward(ApproachTarget.ofEntity(found.getBoundingBox()), permissions))
                .orElseGet(() -> failing("展示框不在了"));
    }

    @Override
    public Action hit(int frame) {
        return new Hit(frame);
    }

    // 只认原版的展示框（含子类）：别的实体不当展示框打。
    private static Optional<ItemFrame> frameOf(PlayerContext current, int frame) {
        if (current == null || current.level() == null) return Optional.empty();
        Entity entity = current.level().getEntity(frame);
        return entity instanceof ItemFrame found && !found.isRemoved() ? Optional.of(found) : Optional.empty();
    }

    private static Action failing(String why) {
        return new Action() {
            @Override public ActionStatus tick(TickContext tick) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, why));
            }

            @Override public String describe() {
                return why;
            }
        };
    }

    /** 左键打展示框一下：转头对准、等攻击条回满、出手，框里空了（或框没了）算打成。 */
    private final class Hit implements Action {
        private final int frame;
        private Action strike;
        private int waited;

        Hit(int frame) {
            this.frame = frame;
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            PlayerContext current = tick.player();
            if (current == null || current.localPlayer() == null || current.interactionSender() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，打不了展示框", null));
            }
            if (strike == null) {
                Optional<ItemFrame> found = frameOf(current, frame);
                if (found.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "展示框不在了"));
                }
                strike = new InteractionAction(Interaction.attackEntity(current, current.interactionSender(),
                        current.menuActions(), current.input(), found.get(), emptied()), "打一下展示框");
            }
            ActionStatus status = strike.tick(tick);
            if (status instanceof ActionStatus.Running && ++waited > HIT_PATIENCE_TICKS) {
                strike.close();
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                        "打不着展示框：转过去准星总落在别的东西上，或一直够不着"));
            }
            return status;
        }

        // 打成了：框里的东西没了（弹出来了），或框整个没了。
        private InteractionConfirmation emptied() {
            return moment -> frameOf(moment, frame).map(found -> found.getItem().isEmpty()).orElse(true)
                    ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
        }

        @Override
        public void close() {
            if (strike != null) strike.close();
        }

        @Override
        public String describe() {
            return "打一下展示框";
        }
    }
}
