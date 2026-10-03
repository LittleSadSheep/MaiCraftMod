// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 换池岸方向后仍须得到十格门框；离线检查几何和回执，不冒充真实水流验证。 */
public final class NetherPortalCastingLayoutTest {
    public static void main(String[] args) throws Exception {
        try (var bootstrap = new InteractionWorldTestHarness()) {
            for (Direction shore : Direction.Plane.HORIZONTAL) {
                var layout = new NetherPortalCastingLayout(new BlockPos(10, 64, 20), shore);
                var frame = new HashSet<>(layout.bottom()); frame.addAll(layout.upperFrame());
                check(frame.size() == 10 && frame.equals(new HashSet<>(layout.frame().frame())),
                        "rotated casting targets agree with the vanilla frame geometry");
                check(layout.mold().size() == 6 && layout.mold().stream().noneMatch(frame::contains),
                        "temporary mold does not occupy obsidian targets");
                check(!frame.contains(layout.castingWater()) && !layout.mold().contains(layout.castingWater()),
                        "water source stays behind the finished frame");
                check(layout.footprint().containsAll(layout.clearance()), "rear drainage remains explicitly scoped");
                // 顶部不能隔空倒桶：起手已有两侧底脚，后续每格必须能借前一步实块或模具的原生点击面。
                var supports = new HashSet<>(layout.mold()); supports.addAll(layout.bottom());
                supports.add(layout.cell(-1, 0, 0)); supports.add(layout.cell(2, 0, 0));
                for (BlockPos target : layout.upperFrame()) {
                    boolean supported = supports.contains(target);
                    for (Direction side : Direction.values()) supported |= supports.contains(target.relative(side));
                    check(supported, "each successive casting cell has an existing native support face");
                    supports.add(target);
                }
                // 水流产生了范围外黑曜石时只比较声明格；一格未加载必须保留未知，不能当成空气。
                var blocks = new HashMap<BlockPos, BlockState>();
                frame.forEach(p -> blocks.put(p, Blocks.OBSIDIAN.defaultBlockState()));
                blocks.put(layout.cell(4, 0, -2), Blocks.OBSIDIAN.defaultBlockState());
                Function<BlockPos, BlockState> read = p -> blocks.getOrDefault(p, Blocks.AIR.defaultBlockState());
                check(layout.observation(read).get("differences").equals(List.of()), "incidental terrain is not a frame mismatch");
                blocks.put(layout.bottom().getFirst(), Blocks.COBBLESTONE.defaultBlockState());
                check(((List<?>) layout.observation(read).get("differences")).size() == 1,
                        "a confirmed bucket action cannot conceal the wrong product");
                blocks.put(layout.bottom().getFirst(), null);
                check(layout.observation(read).toString().contains("unloaded"), "unknown cells remain explicit");
                blocks.put(layout.bottom().getFirst(), Blocks.OBSIDIAN.defaultBlockState());
                layout.frame().interior().forEach(p -> blocks.put(p, Blocks.NETHER_PORTAL.defaultBlockState()
                        .setValue(NetherPortalBlock.AXIS, layout.frame().axis())));
                check(Boolean.TRUE.equals(layout.observation(read).get("portal_active")), "only a full live portal surface confirms activation");
            }
        }
        System.out.println("NetherPortalCastingLayoutTest: rotated mold, frame and whole-frame evidence passed");
    }
}
