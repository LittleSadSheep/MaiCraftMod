package org.maiwithu.maicraft.core.pathing.baritone;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/**
 * 检查下一趟路线排队时保护范围仍能保留下来，接管前不影响旧路线，接管后不继承无关旧限制。
 */
public final class NavigationPolicySnapshotTest {
    public static void main(String[] args) {
        BlockPos oldOwnerCell = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(10, 64, 0);
        BlockPos marked = new BlockPos(11, 64, 0);
        BlockPos forbidden = new BlockPos(12, 64, 0);
        var sacred = cells(target);
        var mutations = cells(marked);
        var body = cells(forbidden);
        EmbeddedBaritonePolicy.install(cells(oldOwnerCell), LongSets.emptySet(), LongSets.emptySet());
        try {
            var queued = NavigationSafetyContext.withProtectedArea(mutations, body,
                    () -> EmbeddedBaritonePolicy.capture(sacred,
                            NavigationSafetyContext.protectedMutationCells(),
                            NavigationSafetyContext.forbiddenBodyCells()));
            check(EmbeddedBaritonePolicy.protects(oldOwnerCell)
                            && !EmbeddedBaritonePolicy.protects(marked),
                    "queuing the next owner changed the still-airborne previous owner's policy");
            check(NavigationSafetyContext.protectedMutationCells().isEmpty(),
                    "the simulated semantic parent scope did not close");
            sacred.clear(); mutations.clear(); body.clear();
            EmbeddedBaritonePolicy.installSnapshot(queued);
            check(EmbeddedBaritonePolicy.protects(target) && EmbeddedBaritonePolicy.protects(marked),
                    "late activation lost target or marked mutation protection after the parent returned");
            check(EmbeddedBaritonePolicy.forbidsBody(forbidden)
                            && EmbeddedBaritonePolicy.protects(forbidden),
                    "late activation lost measured stance protection or allowed mutation of that stance");
            check(!EmbeddedBaritonePolicy.protects(oldOwnerCell),
                    "the replaced owner's unrelated constraints leaked into the new route");
            try {
                queued.protectedCells().clear();
                throw new AssertionError("queued policy is mutable");
            } catch (UnsupportedOperationException expected) { }
            var unrestricted = EmbeddedBaritonePolicy.capture(
                    LongSets.emptySet(), LongSets.emptySet(), LongSets.emptySet());
            EmbeddedBaritonePolicy.installSnapshot(unrestricted);
            check(!EmbeddedBaritonePolicy.protects(target) && !EmbeddedBaritonePolicy.forbidsBody(forbidden),
                    "an unrestricted next owner inherited the preceding owner's protection");
            System.out.println("NavigationPolicySnapshotTest: passed");
        } finally {
            EmbeddedBaritonePolicy.clear();
        }
    }

    private static LongOpenHashSet cells(BlockPos position) {
        return new LongOpenHashSet(new long[] {position.asLong()});
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
