// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.InteractionAction;
import org.maiwithu.maicraft.game.interaction.Interaction;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 落地防护这项生存需求：这一掉会摔死（或下面是虚空）时立刻处理，
 * 派落地防护临时任务把手里的水桶对脚下方块放出去，用落进水里抵掉这次坠落。
 */
public final class FallNeed implements SurvivalNeed {

    private final SurvivalSituation.SituationReader reader;
    private final InteractionSender sender;
    private final MenuActions menuActions;

    public FallNeed(SurvivalSituation.SituationReader reader, InteractionSender sender, MenuActions menuActions) {
        this.reader = reader;
        this.sender = sender;
        this.menuActions = menuActions;
    }

    @Override public String name() { return "落地防护"; }

    @Override
    public Urgency urgency(TickContext context) {
        SurvivalSituation situation = reader.read(context);
        return situation == null ? null : FallDanger.assess(situation);
    }

    @Override
    public Task createTask(TickContext context) {
        return new FallGuardTask(() -> cushionAction(context));
    }

    /** 手里拿着水桶时，造一个"对脚下方块顶面放水"的原生交互动作；没有水桶就返回 null，由任务如实交代。 */
    private Action cushionAction(TickContext context) {
        var player = context.player();
        if (player == null || player.localPlayer() == null) {
            // 没有当刻的角色就没有手，谈不上放水；交给任务如实交代做不了。
            return null;
        }
        var body = player.localPlayer();
        if (!body.getMainHandItem().is(Items.WATER_BUCKET)) {
            return null;
        }
        // 对脚下一格的顶面右键：水会铺在这一格上，落地正好落进水里。命中点由这里预先算好，
        // 交互在坠落中逐刻核对准星仍指在这格顶面上才出手。
        BlockPos below = body.blockPosition().below();
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(below).add(0.0, 0.5, 0.0), Direction.UP, below, false);
        Interaction use = Interaction.useBlock(
                player, sender, menuActions, player.input(), hit, InteractionHand.MAIN_HAND);
        return new InteractionAction(use, "对脚下方块放水缓冲");
    }
}
