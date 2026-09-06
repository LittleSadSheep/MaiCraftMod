package org.maiwithu.maicraft.core.task.acquire;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** Exercises the actual nested mutation/body scope used by mining and its terrain pathfinder. */
public final class AcquisitionProtectionTest {
    private static final String DIMENSION = "minecraft:overworld";

    public static void main(String[] args) {
        ordinaryMarkersDoNotBanWorldSources();
        explicitMarkersAndMeasuredFootprintsRemainProtected();
        unresolvedLabelsCannotStartAnEffect();
        otherDimensionsDoNotProtectLocalCoordinates();
        System.out.println("AcquisitionProtectionTest: passed");
    }

    private static void ordinaryMarkersDoNotBanWorldSources() {
        var coast = marker("old coast", 0, 64, 0, DIMENSION);
        var distant = marker("old lookout", 500, 64, 0, DIMENSION);
        var protection = AcquisitionProtection.resolve(List.of(), List.of(coast, distant), DIMENSION);
        check(protection.problems().isEmpty() && protection.markedCells().isEmpty(),
                "merely remembering a location inside the 528-block search area blocked mining");
        protection.run(() -> {
            check(!NavigationSafetyContext.protectsMutation(new BlockPos(0, 64, 0)),
                    "an ordinary unselected marker became an implicit protected area");
            return null;
        });
    }

    private static void explicitMarkersAndMeasuredFootprintsRemainProtected() {
        BlockPos marked = new BlockPos(0, 64, 0);
        BlockPos nearbyResource = new BlockPos(3, 64, 0);
        BlockPos measuredResource = new BlockPos(5, 64, 0);
        BlockPos forbiddenStance = measuredResource.above();
        var protection = AcquisitionProtection.resolve(List.of(" BASE ", "remote"),
                List.of(marker("base", 0, 64, 0, DIMENSION),
                        marker("remote", 500, 64, 0, DIMENSION)), DIMENSION);
        var measured = new LongOpenHashSet(new long[] {measuredResource.asLong()});
        var body = new LongOpenHashSet(new long[] {forbiddenStance.asLong()});
        NavigationSafetyContext.withProtectedArea(measured, body, () -> protection.run(() -> {
            check(NavigationSafetyContext.protectsMutation(marked),
                    "an explicitly selected resource marker could be mined or changed by the route");
            check(NavigationSafetyContext.protectsMutation(measuredResource)
                            && NavigationSafetyContext.forbidsBody(forbiddenStance),
                    "the acquire child weakened its parent's measured protection footprint");
            check(!NavigationSafetyContext.protectsMutation(nearbyResource),
                    "a point marker incorrectly invented a 12-block protected radius");
            var usable = List.of(marked, nearbyResource, measuredResource).stream()
                    .filter(pos -> !NavigationSafetyContext.protectsMutation(pos)).toList();
            check(usable.equals(List.of(nearbyResource)),
                    "protected targets must be excluded individually while safe resources remain usable");
            check(protection.markedCells().size() == 2, "point labels expanded into a large area collection");
            return null;
        }));
        check(NavigationSafetyContext.protectedMutationCells().isEmpty()
                        && NavigationSafetyContext.forbiddenBodyCells().isEmpty(),
                "acquisition protection leaked outside the child operation");
    }

    private static void unresolvedLabelsCannotStartAnEffect() {
        var protection = AcquisitionProtection.resolve(List.of("missing"), List.of(), DIMENSION);
        check(protection.problems().equals(List.of("unknown protected label: missing")),
                "an unresolved explicit protection was silently ignored");
        AtomicBoolean ran = new AtomicBoolean();
        try {
            protection.run(() -> ran.getAndSet(true));
            throw new AssertionError("unresolved protection entered the effectful child");
        } catch (IllegalStateException expected) { }
        check(!ran.get(), "child executed before protection resolution");
    }

    private static void otherDimensionsDoNotProtectLocalCoordinates() {
        var protection = AcquisitionProtection.resolve(List.of("nether base"),
                List.of(marker("nether base", 0, 64, 0, "minecraft:the_nether")), DIMENSION);
        check(protection.problems().isEmpty() && protection.markedCells().isEmpty(),
                "a known marker in another dimension blocked local acquisition");
    }

    private static IntentRuntime.Landmark marker(String label, int x, int y, int z, String dimension) {
        return new IntentRuntime.Landmark(label, new Goal.WorldPosition(x, y, z, dimension));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
