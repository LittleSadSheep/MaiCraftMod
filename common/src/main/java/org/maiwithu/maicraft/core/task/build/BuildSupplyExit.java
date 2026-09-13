// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import static org.maiwithu.maicraft.core.task.build.BuildExcavationFrontier.AccessStatus.*;

/** 坑底、屋顶和边上脚手架共用的只读离场检查：地面必须能连续走开，孤柱与窄桥不能冒充仓库入口。 */
final class BuildSupplyExit {
    private static final int MARGIN = 4;
    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private final LocalPlayer player;
    private final Set<BlockPos> scaffolds;
    private final BuildSupportWorld world;
    private final BuildSupportWalking walking;
    private final GroundCorridor corridor;

    private BuildSupplyExit(LocalPlayer player, Set<BlockPos> scaffolds) {
        this.player = player; this.scaffolds = Set.copyOf(scaffolds);
        world = new BuildSupportWorld(player.level(), player.level()::isLoaded, Map.of());
        var box = player.getBoundingBox(); var forbidden = NavigationSafetyContext.forbiddenBodyCells();
        var physical = EmbeddedBaritoneRuntime.physicalObstacles();
        walking = new BuildSupportWalking(world, player.level()::isLoaded, box.getXsize(), box.getYsize(), forbidden, physical);
        corridor = new GroundCorridor(world, player.level()::isLoaded, box.getXsize(), box.getYsize(), forbidden, physical);
    }

    static BuildExcavationFrontier.SupplyAccess inspect(LocalPlayer player, BlockPos min, BlockPos max, Set<BlockPos> scaffolds) {
        if (min == null || max == null) return result(BLOCKED, null, "build_footprint_unavailable");
        BlockPos start = player.blockPosition();
        boolean inside = inside(start, min, max, 0);
        if (!inside(start, min, max, MARGIN) && !scaffolds.contains(start.below())) return result(READY, null, "outside_construction_access_area");
        try {
            var check = new BuildSupplyExit(player, scaffolds);
            // 真正在图纸外的连片地面上就可交给普通取料；旁边墙顶更高不要求角色再爬回墙上。
            if (!inside && check.patch(player.position())) return result(READY, start, "exterior_ground_connected");
            var columns = new LinkedHashSet<BlockPos>();
            int midX = Math.floorDiv(min.getX() + max.getX(), 2), midZ = Math.floorDiv(min.getZ() + max.getZ(), 2);
            for (int distance = 1; distance <= MARGIN; distance++) {
                for (int z : new int[]{clamp(start.getZ(), min.getZ(), max.getZ()), midZ, min.getZ(), max.getZ()}) {
                    columns.add(new BlockPos(min.getX() - distance, 0, z)); columns.add(new BlockPos(max.getX() + distance, 0, z));
                }
                for (int x : new int[]{clamp(start.getX(), min.getX(), max.getX()), midX, min.getX(), max.getX()}) {
                    columns.add(new BlockPos(x, 0, min.getZ() - distance)); columns.add(new BlockPos(x, 0, max.getZ() + distance));
                }
            }
            var ordered = new ArrayList<>(columns);
            ordered.sort(Comparator.comparingDouble((BlockPos pos) -> Math.pow(pos.getX() - start.getX(), 2) + Math.pow(pos.getZ() - start.getZ(), 2)));
            // 候选数量最多六十四，只读已同步的地表高度；每个落点再核对实际身体、危险方块和连续地面。
            for (BlockPos column : ordered) {
                if (!player.level().isLoaded(column)) continue;
                int y = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
                BlockPos cell = new BlockPos(column.getX(), y, column.getZ());
                if (player.level().isOutsideBuildHeight(cell.above())) continue;
                Vec3 feet = check.walking.stance(cell);
                if (feet == null || !check.patch(feet)) continue;
                BlockPos exit = BlockPos.containing(feet);
                return result(Math.abs(player.getY() - feet.y) > 1.0 ? EXIT_REQUIRED : READY, exit, "exterior_ground_verified");
            }
            return result(BLOCKED, null, check.world.sawUnloaded() ? "exterior_ground_unloaded" : "no_connected_exterior_ground");
        } catch (RuntimeException | LinkageError unavailable) { return result(BLOCKED, null, "exterior_ground_observation_unavailable"); }
    }

    private boolean patch(Vec3 origin) {
        BlockPos base = BlockPos.containing(origin);
        if (scaffolds.contains(base.below()) || !corridor.clear(origin, origin)) return false;
        var reached = new HashSet<BlockPos>(); var queue = new ArrayDeque<Vec3>();
        reached.add(base); queue.add(origin);
        // 在五乘五范围内沿真实落脚和至多一格台阶扩展；不把图纸、未来垫块或未知地形当作支撑。
        while (!queue.isEmpty() && reached.size() < 25) {
            Vec3 current = queue.removeFirst(); BlockPos at = BlockPos.containing(current);
            for (Direction side : SIDES) for (int dy : new int[]{0, 1, -1}) {
                BlockPos next = at.relative(side).offset(0, dy, 0);
                if (Math.abs(next.getX() - base.getX()) > 2 || Math.abs(next.getZ() - base.getZ()) > 2
                        || Math.abs(next.getY() - base.getY()) > 2 || reached.contains(next) || scaffolds.contains(next.below())) continue;
                Vec3 landing = walking.stance(next);
                if (landing == null || !walking.edge(current, landing)) continue;
                BlockPos actual = BlockPos.containing(landing);
                if (!scaffolds.contains(actual.below()) && reached.add(actual)) queue.add(landing);
            }
        }
        if (reached.size() < 9) return false;
        // 至少含真正连片的二乘二落脚区，排除只沿一格宽土桥或坑壁绕行的“可走开”假象。
        return reached.stream().anyMatch(pos -> reached.contains(pos.east()) && reached.contains(pos.south()) && reached.contains(pos.east().south()));
    }
    private static boolean inside(BlockPos pos, BlockPos min, BlockPos max, int margin) {
        return pos.getX() >= min.getX() - margin && pos.getX() <= max.getX() + margin
                && pos.getZ() >= min.getZ() - margin && pos.getZ() <= max.getZ() + margin;
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static BuildExcavationFrontier.SupplyAccess result(BuildExcavationFrontier.AccessStatus status, BlockPos exit, String code) {
        return new BuildExcavationFrontier.SupplyAccess(status, exit == null ? null : exit.immutable(), code);
    }
}
