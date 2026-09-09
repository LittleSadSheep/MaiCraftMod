package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
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

    BuildPlacementConfirmation(BuildTaskRecord.Target target,
                               List<BuildPlacementGeometry.GeneratedCell> generated,
                               Map<Long, BlockState> before, BlockState predicted) {
        this.target = target;
        this.generated = List.copyOf(generated);
        this.before = Map.copyOf(before);
        this.predicted = predicted;
    }

    @Override public boolean requiresBlockAcknowledgement() { return true; }
    @Override public Verdict observe(LocalPlayerContext context) {
        return observe(context.level()::isLoaded, context.level()::getBlockState, false);
    }
    @Override public Verdict observeAcknowledged(LocalPlayerContext context) {
        return observe(context.level()::isLoaded, context.level()::getBlockState, true);
    }

    // 任一相关格没加载就继续等。全部仍与点击前相同时，服务器已确认则判为没生效，否则暂时无法判断。
    Verdict observe(Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states, boolean acknowledged) {
        if (!loaded.test(target.pos()) || generated.stream().anyMatch(effect -> !loaded.test(effect.pos())))
            return Verdict.PENDING;
        BlockState old = before.get(target.pos().asLong()), live = states.apply(target.pos());
        boolean unchanged = live.equals(old);
        boolean complete = target.matches(live) || BuildPlacementGeometry.isProgress(target, old, live);
        boolean diverged = !complete && !unchanged;
        // 主格有变化还不够，自动生成的其他格也必须逐一符合要求；有格变成无关状态则记为偏离预期。
        for (var effect : generated) {
            BlockState was = before.get(effect.pos().asLong()), now = states.apply(effect.pos());
            boolean matches = BuildValidity.valid(now, effect.expected(), false);
            complete &= matches;
            unchanged &= now.equals(was);
            diverged |= !matches && !now.equals(was);
        }
        if (unchanged) return acknowledged ? Verdict.NOT_APPLIED : Verdict.PENDING;
        if (complete) return Verdict.APPLIED;
        // 门的上半等可能稍后才同步：它还没变化时继续等，不因为确认号已经到达就立即判错。
        return diverged ? Verdict.DIVERGED : Verdict.PENDING;
    }

    // 出错时返回预测、点击前、期望和现在观察到的各格状态，便于区分没放上和放成了别的状态。
    Map<String, Object> diagnostics(Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states) {
        List<Map<String, Object>> effects = new ArrayList<>();
        effects.add(effect(target.pos(), target.desiredState(), loaded, states));
        generated.forEach(value -> effects.add(effect(value.pos(), value.expected(), loaded, states)));
        return Map.of("predicted_primary", predicted.toString(), "effects", effects,
                "requires_server_acknowledgement", true);
    }

    private Map<String, Object> effect(BlockPos pos, BlockState expected,
                                       Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states) {
        return Map.of("position", pos.toShortString(), "before", before.get(pos.asLong()).toString(),
                "expected", expected.toString(), "observed", loaded.test(pos) ? states.apply(pos).toString() : "unloaded");
    }
}
