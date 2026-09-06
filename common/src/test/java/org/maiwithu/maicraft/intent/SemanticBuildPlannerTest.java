package org.maiwithu.maicraft.intent;

import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord.Target;
import org.maiwithu.maicraft.core.tools.work.BuildTool;

/** Check the expanded production plan, including cells overwritten by later operations. */
public final class SemanticBuildPlannerTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var size = SemanticBuildPlanner.parseSize(new JsonPrimitive("small"));
        check(size.width() == 5 && size.depth() == 5 && size.storeys() == 1,
                "small must mean a practical single-room shelter");
        var site = new SemanticBuildPlanner.Site(-2, 2, -2, 2, 63,
                64, 66, new int[5][5], Direction.SOUTH, null, 0);
        var palette = new SemanticBuildPlanner.Palette("minecraft:cobblestone",
                "minecraft:birch_planks", "minecraft:birch_planks", "minecraft:birch_log",
                "minecraft:birch_slab", "minecraft:birch_log", "minecraft:birch_door",
                "minecraft:glass_pane", "minecraft:birch_fence", "minecraft:ladder",
                "minecraft:lantern", "minecraft:barrel", "minecraft:crafting_table",
                "minecraft:bookshelf", "minecraft:birch_stairs", "minecraft:gray_carpet");
        var style = SemanticBuildPlanner.styleProfile("simple wooden hut", "small_survival_house");
        check(!SemanticBuildPlanner.styleProfile("gothic", "small_survival_house")
                        .roofShapes().contains("flat"),
                "an explicit architectural style must take priority over the survival purpose");
        List<Target> targets = BuildTool.resolvedTargets(SemanticBuildPlanner.design(
                site, size, "small_survival_house", Set.of(), palette, style, "surface", true));
        int materials = targets.stream().mapToInt(Target::materialCount).sum();
        check(materials < 128, "an overnight hut should not require hundreds of decorative items: " + materials);
        check(targets.stream().filter(t -> t.materialCount() > 0).allMatch(t ->
                        t.item() == Items.BIRCH_PLANKS || t.item() == Items.BIRCH_DOOR),
                "an unfurnished wooden hut must be constructible from wood alone");
        Map<BlockPos, Target> cells = targets.stream().collect(Collectors.toMap(Target::pos, Function.identity()));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            check(cells.get(new BlockPos(x, 63, z)).desiredState().is(Blocks.BIRCH_PLANKS), "missing floor");
            check(cells.get(new BlockPos(x, 67, z)).desiredState().is(Blocks.BIRCH_PLANKS), "missing roof");
            for (int y = 64; y <= 66; y++) {
                Target cell = cells.get(new BlockPos(x, y, z));
                check(cell != null, "every shell/interior cell needs an explicit final state");
                if (Math.abs(x) < 2 && Math.abs(z) < 2) {
                    check(cell.desiredState().isAir(), "trees and bumps must be cleared from the living space");
                } else if (x == 0 && z == 2 && y < 66) {
                    check(cell.desiredState().is(Blocks.BIRCH_DOOR), "entrance needs a complete closable door");
                } else {
                    check(cell.desiredState().is(Blocks.BIRCH_PLANKS), "the outer wall must be closed");
                }
            }
        }
        for (int z = 3; z <= 4; z++) for (int y = 64; y <= 65; y++) {
            check(cells.get(new BlockPos(0, y, z)).desiredState().isAir(), "entry approach needs headroom");
        }
        List<Target> decorated = BuildTool.resolvedTargets(SemanticBuildPlanner.design(
                site, size, "small_survival_house", Set.of("windows", "lighting"),
                palette, style, "surface", true));
        check(decorated.stream().anyMatch(t -> t.item() == Items.GLASS_PANE), "explicit windows were lost");
        check(decorated.stream().anyMatch(t -> t.item() == Items.LANTERN), "explicit lighting was lost");
        System.out.println("SemanticBuildPlannerTest: passed; basic hut material items=" + materials);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
