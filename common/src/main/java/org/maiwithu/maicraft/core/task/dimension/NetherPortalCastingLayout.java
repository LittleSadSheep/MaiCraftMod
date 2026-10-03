// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 单桶岩浆池手法：池边起底框 -> 六块导流模具 -> 留水逐格浇筑 -> 收水点火。 */
public record NetherPortalCastingLayout(BlockPos origin, Direction shore) {
    public NetherPortalCastingLayout {
        origin = origin.immutable();
        if (shore.getAxis() == Direction.Axis.Y) throw new IllegalArgumentException("casting shore must be horizontal");
    }

    /** 原点是门内左下格，正面朝向岩浆池；背后方向始终指向岸边，旋转后仍沿同一手法施工。 */
    public BlockPos cell(int across, int up, int behind) {
        return origin.relative(shore.getClockWise(), across).above(up).relative(shore, behind);
    }

    public NetherPortalFrame frame() {
        Direction across = shore.getClockWise();
        BlockPos positiveOrigin = across.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? cell(1, 0, 0) : origin;
        return new NetherPortalFrame(positiveOrigin, across.getAxis(), 2, 3);
    }

    /** 起手以实块挡住一格岩浆，在旁边放水后拆块，使水形成门底的两格凹槽。 */
    public BlockPos placeholder() { return cell(0, 0, 0); }
    public BlockPos initialWater() { return cell(1, 0, 0); }

    /** 水源在门框后方，不能占据最终需要黑曜石的顶部；从右侧高柱的内侧面倒水。 */
    public BlockPos castingWater() { return cell(1, 3, 1); }

    public List<BlockPos> mold() {
        return List.of(cell(2, 1, 1), cell(2, 2, 1), cell(2, 3, 1), cell(2, 3, 0),
                cell(-1, 1, 1), cell(0, 1, 1));
    }

    /** 给高处落水保留岸后通道；只清理模板点名的空间，不扩大成整片场地平整。 */
    public List<BlockPos> clearance() {
        return List.of(cell(1, 1, 1), cell(1, 2, 1), castingWater(), cell(1, 1, 2));
    }

    public List<BlockPos> bottom() { return List.of(cell(0, -1, 0), cell(1, -1, 0)); }

    /** 底框已凝固后才移水；两侧从低到高，顶部先贴右侧模具浇近端，再借新黑曜石的侧面浇远端。 */
    public List<BlockPos> upperFrame() {
        return List.of(cell(-1, 0, 0), cell(2, 0, 0), cell(-1, 1, 0), cell(-1, 2, 0),
                cell(2, 1, 0), cell(2, 2, 0), cell(1, 3, 0), cell(0, 3, 0));
    }

    /** 脚手架、取材和导航共享已声明范围，不能把先浇好的门框当成可拆的过路障碍。 */
    public Set<BlockPos> footprint() {
        var cells = new LinkedHashSet<>(frame().frame());
        cells.addAll(frame().interior()); cells.addAll(mold()); cells.addAll(clearance());
        return Set.copyOf(cells);
    }

    /** 返回整扇门的声明格；流到声明范围外的水不被虚构成“多余方块”。 */
    public Map<String, Object> observation(Function<BlockPos, BlockState> read) {
        var differences = new ArrayList<Map<String, Object>>();
        int obsidian = 0;
        for (BlockPos at : frame().frame()) {
            BlockState actual = read.apply(at);
            if (actual != null && actual.is(Blocks.OBSIDIAN)) obsidian++;
            else differences.add(difference(at, "minecraft:obsidian", actual));
        }
        for (BlockPos at : frame().interior()) {
            BlockState actual = read.apply(at);
            if (!NetherPortalFrame.empty(actual)) differences.add(difference(at, "air_or_active_portal", actual));
        }
        return Map.of("origin", position(origin), "shore", shore.getSerializedName(),
                "obsidian_blocks", obsidian, "expected_obsidian_blocks", 10,
                "frame_ready", frame().ready(read), "portal_active", frame().active(read), "differences", differences);
    }

    private static Map<String, Object> difference(BlockPos at, String expected, BlockState actual) {
        return Map.of("position", position(at), "expected", expected,
                "actual", actual == null ? "unloaded" : BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString(),
                "known", actual != null);
    }

    public static List<Integer> position(BlockPos at) { return List.of(at.getX(), at.getY(), at.getZ()); }
}
