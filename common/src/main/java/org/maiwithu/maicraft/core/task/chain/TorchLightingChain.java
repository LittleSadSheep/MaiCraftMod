// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chain;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.task.reflex.Reflex;

/** 保留既有补光说明入口与原生确认条件；实际动作改由随行通道执行，不再参与身体抢占。 */
public final class TorchLightingChain implements Reflex {
    public static NativeConfirmation confirmation(LocalPlayer player, BuildTaskRecord.Target target) {
        // 火把常驻副手后，消耗必须按全身库存核对；背包与副手交换不能冒充一次放置。
        int before = PlayerInv.count(player.getInventory(), Items.TORCH);
        // 世界方块与火把扣减都符合，且收到本次服务器预测确认号后才记一支成功；本地画面预测不算。
        return new NativeConfirmation() {
            @Override public boolean requiresBlockAcknowledgement() { return true; }
            @Override public Verdict observe(LocalPlayerContext context) { return observe(context, false); }
            @Override public Verdict observeAcknowledged(LocalPlayerContext context) { return observe(context, true); }
            private Verdict observe(LocalPlayerContext context, boolean acknowledged) {
                if (!context.level().isLoaded(target.pos())) return Verdict.PENDING;
                var live = context.level().getBlockState(target.pos());
                int consumed = before - PlayerInv.count(context.player().getInventory(), Items.TORCH);
                if (consumed < 0 || consumed > 1 || !live.isAir() && !live.equals(target.desiredState())) return Verdict.DIVERGED;
                if (live.equals(target.desiredState()) && (consumed == 1
                        || context.player().getAbilities().instabuild && consumed == 0)) return Verdict.APPLIED;
                if (acknowledged && live.isAir() && consumed == 0) return Verdict.NOT_APPLIED;
                return Verdict.PENDING;
            }
        };
    }

    @Override public String id() { return "routine_torch_lighting"; }
    @Override public String describe() {
        return "随行补光默认关闭，LLM 可通过 auto_light 按需开启或随时关闭；开启后低于目标方块光时用副手火把边走边放，不绕路、不抢占主任务。整片区域使用 light_area。";
    }
}
