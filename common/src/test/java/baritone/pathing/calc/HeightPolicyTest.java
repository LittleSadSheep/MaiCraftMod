// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.calc;

import baritone.api.IBaritone;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Moves;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import sun.misc.Unsafe;

/** Exercise A*'s real movement-lattice gate with a height-only construction policy. */
public final class HeightPolicyTest {
    public static void main(String[] args) throws Exception {
        var access = Unsafe.class.getDeclaredField("theUnsafe"); access.setAccessible(true);
        var memory = (Unsafe) access.get(null);
        var context = (CalculationContext) memory.allocateInstance(CalculationContext.class);
        IBaritone baritone = (IBaritone) Proxy.newProxyInstance(IBaritone.class.getClassLoader(),
                new Class<?>[]{IBaritone.class}, (proxy, method, values) -> {
                    if (method.getName().equals("getPlayerContext")) return null;
                    throw new AssertionError("unexpected game access: " + method.getName());
                });
        set(context, "baritone", baritone);
        var search = (AStarPathFinder) memory.allocateInstance(AStarPathFinder.class);
        set(search, "calcContext", context);
        var heightOnly = EmbeddedBaritonePolicy.capture(null, null, null, -57);
        set(context, "maicraftPolicy", heightOnly);
        check(heightOnly.forbidsBody(0, -58, 0) && heightOnly.forbiddenBodyCells().isEmpty(),
                "the execution policy rejects descent without enumerating any forbidden cells");
        check(blocked(search, Moves.DOWNWARD, 0, -57, 0, 0, -58, 0),
                "A* must reject the same below-floor movement that live execution rejects");
        check(!blocked(search, Moves.TRAVERSE_EAST, 0, -57, 0, 1, -57, 0), "same-height walking remains allowed");
        check(!blocked(search, Moves.ASCEND_EAST, 0, -57, 0, 1, -56, 0), "ascending existing steps remains allowed");
        set(context, "maicraftPolicy", EmbeddedBaritonePolicy.capture(null, null, null));
        check(!context.hasForbiddenBodyCells() && !blocked(search, Moves.DOWNWARD, 0, -57, 0, 0, -58, 0),
                "the later unrestricted pass may reconsider descent");
        set(context, "maicraftPolicy", EmbeddedBaritonePolicy.capture(null, null,
                LongSets.singleton(BlockPos.asLong(1, -57, 0))));
        check(blocked(search, Moves.TRAVERSE_EAST, 0, -57, 0, 1, -57, 0), "explicit forbidden cells remain effective");
        set(context, "maicraftPolicy", EmbeddedBaritonePolicy.capture(null, null,
                LongSets.singleton(BlockPos.asLong(0, -57, 0))));
        check(!blocked(search, Moves.TRAVERSE_EAST, 0, -57, 0, 1, -57, 0), "an occupied forbidden source remains escapable");
        System.out.println("HeightPolicyTest: search and execution agree on height-only construction constraints");
    }
    private static boolean blocked(AStarPathFinder search, Moves move, int x, int y, int z, int tx, int ty, int tz) throws Exception {
        Method method = AStarPathFinder.class.getDeclaredMethod("crossesForbiddenBodyCell", Moves.class,
                int.class, int.class, int.class, int.class, int.class, int.class);
        method.setAccessible(true); return (boolean) method.invoke(search, move, x, y, z, tx, ty, tz);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
