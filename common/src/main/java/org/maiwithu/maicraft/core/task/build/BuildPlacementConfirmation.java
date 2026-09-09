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

/** Frozen effects of one click, including secondary halves and monotone multi-use progress. */
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

    Verdict observe(Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states, boolean acknowledged) {
        if (!loaded.test(target.pos()) || generated.stream().anyMatch(effect -> !loaded.test(effect.pos())))
            return Verdict.PENDING;
        BlockState old = before.get(target.pos().asLong()), live = states.apply(target.pos());
        boolean unchanged = live.equals(old);
        boolean complete = target.matches(live) || BuildPlacementGeometry.isProgress(target, old, live);
        boolean diverged = !complete && !unchanged;
        for (var effect : generated) {
            BlockState was = before.get(effect.pos().asLong()), now = states.apply(effect.pos());
            boolean matches = BuildValidity.valid(now, effect.expected(), false);
            complete &= matches;
            unchanged &= now.equals(was);
            diverged |= !matches && !now.equals(was);
        }
        if (unchanged) return acknowledged ? Verdict.NOT_APPLIED : Verdict.PENDING;
        if (complete) return Verdict.APPLIED;
        // Secondary cells can arrive in a later chunk update even after the use acknowledgement.
        return diverged ? Verdict.DIVERGED : Verdict.PENDING;
    }

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
