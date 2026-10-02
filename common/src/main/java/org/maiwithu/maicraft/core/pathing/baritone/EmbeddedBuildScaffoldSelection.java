// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.Map;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;

/** 从未选中的背包槽位取用脚手架时，复用与施工相同的可见选择流程并等待回执确认。 */
final class EmbeddedBuildScaffoldSelection {
    private final FirstPersonActionGate gate = new FirstPersonActionGate();
    private EmbeddedBaritoneNavigator owner;
    private Item material;
    private int slot;
    private long lastTick = Long.MIN_VALUE;
    private long lastProgress = Long.MIN_VALUE;

    boolean select(LocalPlayerContext context, EmbeddedBaritoneNavigator navigator, LocalPlayer player) {
        if (owner != null && owner != navigator) cancel(owner);
        if (owner == null) {
            var choice = BuildPlacementRegistry.scaffoldChoice(player);
            if (choice == null) return false;
            owner = navigator; material = choice.item(); slot = choice.inventorySlot();
            lastProgress = context.tickRevision();
        }
        return advance(context, navigator);
    }

    boolean advance(LocalPlayerContext context, EmbeddedBaritoneNavigator navigator) {
        if (owner == null) return true;
        if (owner != navigator) { cancel(owner); return false; }
        // 原生操作按身体刻编号串行化，不能因世界时间同步或暂停沿用上一会话的去重刻。
        long now = context.tickRevision();
        if (lastTick == now) return false;
        lastTick = now;
        var status = gate.select(context, context.player(), slot);
        if (gate.takeConfirmedSwap() != null) { navigator.recordConfirmedNativeAction(); lastProgress = now; }
        // 已发出的槽位操作交给原生回执结算；只有纯界面准备持续无进展才停止，并明确说明没有放块。
        if (status == FirstPersonActionGate.Status.RUNNING && !gate.pending() && now - lastProgress > 200) {
            cancel(navigator); navigator.internalFailure("scaffold preparation could not present or close its inventory GUI; the current placement step has not executed");
            return false;
        }
        if (status == FirstPersonActionGate.Status.FAILED) {
            String failure = gate.failure(); cancel(navigator);
            navigator.internalFailure("temporary scaffold selection was not confirmed: " + failure); return false;
        }
        if (status != FirstPersonActionGate.Status.READY) return false;
        boolean valid = context.player().getMainHandItem().is(material)
                && BuildPlacementRegistry.scaffoldUseAllowed(context.player(), context.player().getMainHandItem());
        cancel(navigator);
        return valid;
    }

    boolean pending() { return owner != null; }
    // 记录导航真正卡在哪个物品准备阶段，避免把没有执行的搜索或移动误报成地形无路。
    Map<String, Object> diagnostics() {
        return owner == null ? Map.of("pending", false) : Map.of("pending", true,
                "item_id", BuiltInRegistries.ITEM.getKey(material).toString(), "source_slot", slot,
                "native_confirmation_pending", gate.pending(), "last_progress_tick", lastProgress);
    }
    void cancel(EmbeddedBaritoneNavigator navigator) {
        if (owner != navigator) return;
        gate.reset(); owner = null; material = null; slot = -1; lastTick = Long.MIN_VALUE; lastProgress = Long.MIN_VALUE;
    }
    void reset() { if (owner != null) cancel(owner); }
}
