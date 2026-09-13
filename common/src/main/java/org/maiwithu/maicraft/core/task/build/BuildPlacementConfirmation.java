package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.build.BuildValidity;

/**
 * 记录一次放置前的方块状态，并检查点击后是否有预期变化，包括床头、门上半等随之生成的格子。
 * 本次点击有进展也可确认成功，例如先放下双层半砖的第一片；整格是否完成由施工任务继续判断。
 * requiresBlockAcknowledgement 要求动作控制层结合服务器确认号，不能只凭客户端先显示了方块就结束动作。
 */
final class BuildPlacementConfirmation implements NativeConfirmation {
    private final BuildTaskRecord.Target target;
    private final List<BuildPlacementGeometry.GeneratedCell> generated;
    private final Map<Long, BlockState> before;
    private final BlockState predicted;
    private final Map<Long, BuildTaskRecord.Target> effectContracts;
    private final boolean predictedFootprintMatches;
    private int materialBefore = -1, materialObserved;

    BuildPlacementConfirmation(BuildTaskRecord.Target target,
                               List<BuildPlacementGeometry.GeneratedCell> generated,
                               Map<Long, BlockState> before, BlockState predicted) {
        this(target, generated, before, predicted, Map.of());
    }

    BuildPlacementConfirmation(BuildTaskRecord.Target target,
                               List<BuildPlacementGeometry.GeneratedCell> generated,
                               Map<Long, BlockState> before, BlockState predicted,
                               Map<Long, BuildTaskRecord.Target> declared) {
        this.target = target;
        this.before = Map.copyOf(before);
        this.predicted = predicted;
        // 本次门合页等派生属性由原生放置预测决定；蓝图默认值不是主人明确要求，不能把正确右合页误判成左合页。
        var projection = new BuildTaskRecord.Target(predicted, target.item(), target.pos(), target.label(), null, null, null);
        var nativeEffects = BuildPlacementGeometry.generatedBy(projection);
        var effects = new ArrayList<BuildPlacementGeometry.GeneratedCell>();
        var contracts = new java.util.LinkedHashMap<Long, BuildTaskRecord.Target>();
        for (var effect : generated) {
            effects.add(nativeEffects.stream().filter(value -> value.pos().equals(effect.pos())).findFirst().orElse(effect));
            var authored = declared.get(effect.pos().asLong());
            if (authored == null) authored = new BuildTaskRecord.Target(effect.expected(), target.item(), effect.pos(), target.label(),
                    null, null, null, target.itemPlace(), target.exactProperties(), target.strictIdentity(), target.finalProperties());
            contracts.put(effect.pos().asLong(), authored);
        }
        this.generated = List.copyOf(effects); this.effectContracts = Map.copyOf(contracts);
        predictedFootprintMatches = nativeEffects.size() == generated.size()
                && nativeEffects.stream().allMatch(value -> contracts.containsKey(value.pos().asLong()));
    }

    @Override public boolean requiresBlockAcknowledgement() { return true; }
    @Override public Verdict observe(LocalPlayerContext context) {
        return materialVerdict(context, observe(context.level()::isLoaded, context.level()::getBlockState, false));
    }
    @Override public Verdict observeAcknowledged(LocalPlayerContext context) {
        return materialVerdict(context, observe(context.level()::isLoaded, context.level()::getBlockState, true));
    }

    BuildPlacementConfirmation trackMaterial(LocalPlayer player) {
        // 门、床等一件物品生成两格：生存放置须同时看到扣一件，不按上下两半重复收费，也不改背包制造证据。
        if (!player.getAbilities().instabuild && !generated.isEmpty() && target.materialCount() == 1)
            materialObserved = materialBefore = org.maiwithu.maicraft.core.PlayerInv.carriedCount(player.getInventory(), target.item());
        return this;
    }
    private Verdict materialVerdict(LocalPlayerContext context, Verdict world) {
        if (materialBefore < 0) return world;
        materialObserved = org.maiwithu.maicraft.core.PlayerInv.carriedCount(context.player().getInventory(), target.item());
        int consumed = materialBefore - materialObserved;
        if (world == Verdict.DIVERGED || consumed < 0 || consumed > 1 || world == Verdict.NOT_APPLIED && consumed != 0) return Verdict.DIVERGED;
        // 方块确认和库存同步可能先后到达；少了扣物证据就继续等同一个回执，不能再点一次或直接报成功。
        return world == Verdict.APPLIED && consumed == 0 ? Verdict.PENDING : world;
    }

    // 任一相关格没加载就继续等。全部仍与点击前相同时，服务器已确认则判为没生效，否则暂时无法判断。
    Verdict observe(Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states, boolean acknowledged) {
        if (!loaded.test(target.pos()) || generated.stream().anyMatch(effect -> !loaded.test(effect.pos())))
            return Verdict.PENDING;
        BlockState old = before.get(target.pos().asLong()), live = states.apply(target.pos());
        boolean unchanged = live.equals(old);
        // 本次点击按放置阶段的要求确认，允许合法的逐次增量；门的最终开关状态随后另行调整。
        boolean complete = BuildPlacementGeometry.placementComplete(target, live)
                || BuildPlacementGeometry.isProgress(target, old, live);
        // 两格结构还要与本次预测的摆放状态相符；单格半砖等既有逐次增量规则保持原样。
        complete &= predictedFootprintMatches;
        if (!generated.isEmpty()) complete &= matchesNativePlacement(live, predicted);
        boolean diverged = !complete && !unchanged;
        // 主格有变化还不够，自动生成的其他格也必须逐一符合要求；有格变成无关状态则记为偏离预期。
        for (var effect : generated) {
            BlockState was = before.get(effect.pos().asLong()), now = states.apply(effect.pos());
            // 上半独立声明的朝向、合页等要求仍须满足；开关等运行态继续留给最后的原生调整步骤。
            boolean matches = matchesNativePlacement(now, effect.expected())
                    && effectContracts.get(effect.pos().asLong()).acceptsPlacedState(now);
            complete &= matches;
            unchanged &= now.equals(was);
            diverged |= !matches && !now.equals(was);
        }
        if (unchanged) return acknowledged ? Verdict.NOT_APPLIED : Verdict.PENDING;
        if (complete) return Verdict.APPLIED;
        // 门的上半等可能稍后才同步：它还没变化时继续等，不因为确认号已经到达就立即判错。
        return diverged || !predictedFootprintMatches ? Verdict.DIVERGED : Verdict.PENDING;
    }

    private static boolean matchesNativePlacement(BlockState live, BlockState expected) {
        if (live.getBlock() != expected.getBlock()) return false;
        for (var property : expected.getProperties())
            if (BuildValidity.isPlacementProperty(property) && (!live.hasProperty(property)
                    || !live.getValue(property).equals(expected.getValue(property)))) return false;
        return true;
    }

    // 出错时返回预测、点击前、期望和现在观察到的各格状态，便于区分没放上和放成了别的状态。
    Map<String, Object> diagnostics(Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states) {
        List<Map<String, Object>> effects = new ArrayList<>();
        effects.add(effect(target.pos(), predicted, loaded, states));
        generated.forEach(value -> effects.add(effect(value.pos(), value.expected(), loaded, states)));
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("predicted_primary", predicted.toString()); data.put("effects", effects); data.put("requires_server_acknowledgement", true);
        if (materialBefore >= 0) data.put("material_confirmation", Map.of("expected_consumed", 1,
                "carried_before", materialBefore, "last_observed_carried", materialObserved));
        return Map.copyOf(data);
    }

    private Map<String, Object> effect(BlockPos pos, BlockState expected,
                                       Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states) {
        var authored = pos.equals(target.pos()) ? target : effectContracts.get(pos.asLong());
        return Map.of("position", pos.toShortString(), "before", before.get(pos.asLong()).toString(),
                "expected", expected.toString(), "authored", authored.desiredState().toString(),
                "observed", loaded.test(pos) ? states.apply(pos).toString() : "unloaded");
    }
}
