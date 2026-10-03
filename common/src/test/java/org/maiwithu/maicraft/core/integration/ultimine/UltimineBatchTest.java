// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ultimine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 回放原生部分连锁、分包到达、区块卸载和中途取消；预览数量永远不能直接成为破坏数量。 */
public final class UltimineBatchTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var world = new LinkedHashMap<BlockPos, BlockState>();
        BlockPos origin = new BlockPos(1, 5, 1);
        var positions = new ArrayList<BlockPos>();
        for (int i = 0; i < 70; i++) { BlockPos at = origin.east(i); positions.add(at); world.put(at, Blocks.IRON_ORE.defaultBlockState()); }
        var batch = new UltimineBatch(origin, positions, world::get);
        positions.clear();
        check(batch.size() == 70 && batch.observe(world::get, false).remaining() == 70, "full preview is detached and not counted as mined");
        world.put(origin, Blocks.AIR.defaultBlockState());
        check(batch.observe(world::get, false).removed().isEmpty(), "a disappeared seed still needs its own native confirmation");
        world.put(origin.east(), Blocks.AIR.defaultBlockState());
        world.put(origin.east(2), Blocks.COBBLESTONE.defaultBlockState());
        world.remove(origin.east(3));
        var partial = batch.observe(world::get, true);
        check(partial.removed().size() == 2 && partial.remaining() == 66 && partial.unknown() == 2,
                "partial native break preserves unchanged, changed and unloaded positions separately");
        check(partial.cells().size() == 70 && !partial.allRemoved(), "large native selection evidence is never truncated");
        // 下一批同步消息补到后再读取，旧快照不被新读数改写，最终仍完整返回每一格。
        for (int i = 0; i < 70; i++) world.put(origin.east(i), Blocks.AIR.defaultBlockState());
        var complete = batch.observe(world::get, true);
        check(complete.allRemoved() && complete.removed().size() == 70 && partial.removed().size() == 2,
                "late secondary block updates are included without rewriting earlier observations");
        world.put(origin, Blocks.IRON_ORE.defaultBlockState());
        var regenerated = batch.observe(world::get, true);
        check(regenerated.removed().containsKey(origin)
                && regenerated.cells().getFirst().get("status").equals("origin_break_confirmed_then_changed"),
                "a confirmed seed is retained when the world regenerates it; the new block is not silently mined again");
        System.out.println("UltimineBatchTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
