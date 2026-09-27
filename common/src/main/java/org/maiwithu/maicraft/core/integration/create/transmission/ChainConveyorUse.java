// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;

/** 持链右键先选起点、再连终点；选择标记与双向连接分别对账，不能用方块外观没有变化判断失败。 */
public final class ChainConveyorUse implements NativeConfirmation {
    enum Action { SELECT_FIRST, CLEAR_SELECTION, CONNECT }
    private final LocalPlayer player;
    private final Level world;
    private final BlockPos clicked;
    private final BlockEntity targetEntity;
    private BlockEntity sourceEntity;
    private BlockPos first;
    private Set<BlockPos> targetBefore = Set.of(), sourceBefore = Set.of();
    private Action action = Action.SELECT_FIRST;
    private int before, cost, after;
    private boolean creative, applied;
    private String failure;

    public static ChainConveyorUse prepare(LocalPlayer player, BlockPos clicked, InteractionHand hand) {
        if (!player.getItemInHand(hand).is(Items.CHAIN)
                || !ChainConveyorBridge.isConveyor(player.level().getBlockEntity(clicked))) return null;
        return new ChainConveyorUse(player, clicked);
    }

    private ChainConveyorUse(LocalPlayer player, BlockPos clicked) {
        this.player=player; world=player.level(); this.clicked=clicked.immutable(); targetEntity=world.getBlockEntity(clicked);
        before=after=ChainConveyorInventory.count(player); creative=player.getAbilities().instabuild;
        try {
            ChainConveyorInventory.requirePlainChains(player);
            if (!player.mayBuild()) throw new IllegalArgumentException("chain_conveyor_player_cannot_build");
            var selected=ChainConveyorBridge.selection();
            first=selected.first()!=null && world.dimension().equals(selected.dimension()) ? selected.first() : null;
            targetBefore=ChainConveyorBridge.connections(targetEntity);
            var limits=ChainConveyorBridge.limits(world);
            if (targetBefore.size()>=limits.maximumConnections()) throw new IllegalArgumentException("chain_conveyor_connection_limit_reached");
            if (first==null) return;
            action=player.isShiftKeyDown() || first.equals(clicked) ? Action.CLEAR_SELECTION : Action.CONNECT;
            if (!world.isLoaded(first)) throw new IllegalArgumentException("chain_conveyor_selected_endpoint_unloaded");
            sourceEntity=world.getBlockEntity(first); sourceBefore=ChainConveyorBridge.connections(sourceEntity);
            if (action==Action.CLEAR_SELECTION) return;
            ChainConveyorGeometry.validate(first,clicked,limits);
            if (ChainConveyorBridge.existingLink(sourceBefore,targetBefore,clicked.subtract(first)))
                throw new IllegalArgumentException("chain_conveyor_already_connected");
            cost=ChainConveyorBridge.linkCost(clicked.subtract(first));
            // 第二次点击缺料会清掉原生选择标记；出手前报告真实配额，让规划者补齐材料后继续原选择。
            failure=materialFailure(before,cost,creative);
        } catch (IllegalArgumentException problem) { failure=problem.getMessage(); }
    }

    static String materialFailure(int available, int cost, boolean creative) {
        return !creative && available<cost ? "chain_conveyor_insufficient_chains: required="+cost
                +", available="+available+", missing="+(cost-available) : null;
    }
    public String failure() { return failure; }
    public int missingChains() { return creative ? 0 : Math.max(0,cost-before); }

    @Override public Verdict observe(LocalPlayerContext context) {
        if (context.player()!=player || context.level()!=world || !world.isLoaded(clicked)
                || world.getBlockEntity(clicked)!=targetEntity || player.getAbilities().instabuild!=creative) return Verdict.DIVERGED;
        try {
            if (first!=null && (!world.isLoaded(first) || world.getBlockEntity(first)!=sourceEntity)) return Verdict.DIVERGED;
            var selected=ChainConveyorBridge.selection(); after=ChainConveyorInventory.count(player);
            var targetNow=ChainConveyorBridge.connections(targetEntity);
            boolean linked=false, unchanged=targetNow.equals(targetBefore);
            if (first!=null) {
                var sourceNow=ChainConveyorBridge.connections(sourceEntity);
                unchanged &= sourceNow.equals(sourceBefore);
                if (action==Action.CONNECT) {
                    // 只接受这对端点新增的双向连接；别处的接线变化或只清空选择标记不能充当接线完成。
                    var expectedSource=new HashSet<>(sourceBefore); expectedSource.add(clicked.subtract(first));
                    var expectedTarget=new HashSet<>(targetBefore); expectedTarget.add(first.subtract(clicked));
                    linked=sourceNow.equals(expectedSource) && targetNow.equals(expectedTarget);
                }
            }
            return verdict(action,selected.matches(world,clicked),selected.first()==null,
                    unchanged,linked,before,after,creative?0:cost);
        } catch (IllegalArgumentException changed) { return Verdict.DIVERGED; }
    }

    static Verdict verdict(Action action, boolean selectedClicked, boolean selectionCleared,
            boolean unchanged, boolean linked, int before, int after, int cost) {
        if (action==Action.CONNECT) {
            if (after!=before && after!=before-cost) return Verdict.DIVERGED;
            return linked && selectionCleared && after==before-cost ? Verdict.APPLIED : Verdict.PENDING;
        }
        if (after!=before || !unchanged) return Verdict.DIVERGED;
        return (action==Action.SELECT_FIRST ? selectedClicked : selectionCleared) ? Verdict.APPLIED : Verdict.PENDING;
    }

    // 单刻观察还要经过统一回执的稳定窗口；仅最终确认后才对外宣布选点或接线动作完成。
    public void confirmed() { applied=true; }

    public Map<String,Object> evidence() {
        var result=new LinkedHashMap<String,Object>();
        result.put("action",action.name().toLowerCase(Locale.ROOT));
        result.put("native_action_confirmed",applied);
        result.put("first_endpoint_selected",applied && action==Action.SELECT_FIRST);
        result.put("chain_link_verified",applied && action==Action.CONNECT);
        result.put("machine_production_verified",false);
        result.put("item_id","minecraft:chain"); result.put("chains_required",cost);
        result.put("chains_available_before",before); result.put("chains_available_after",after);
        result.put("chains_missing",missingChains());
        result.put("clicked_endpoint",clicked.toShortString());
        if (first!=null) result.put("selected_endpoint_before",first.toShortString());
        if (failure!=null) result.put("failure_code",failure);
        return result;
    }

    public String summary() {
        if (!applied) return "native chain-conveyor outcome remains unconfirmed";
        return switch (action) {
            case SELECT_FIRST -> "native chain-conveyor first endpoint selected; the second endpoint has not been connected";
            case CLEAR_SELECTION -> "native chain-conveyor selection cleared; no chain link was created";
            case CONNECT -> "native reciprocal chain-conveyor link and chain consumption confirmed; machine production remains unverified";
        };
    }
}
