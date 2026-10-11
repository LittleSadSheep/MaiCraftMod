// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.world.ReadsLightLevels;

/** 照明测试的现场替身：一张方块表、一张已加载表、一张结实面表；亮度可查表，也可按发光方块现算。 */
final class LightingFakes {

    private LightingFakes() {}

    /** 现场替身：没放进已加载表的列一律如实回答"没加载"。 */
    static final class FakeWorld implements LightingWorld {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> loadedColumns = new HashSet<>();
        final Set<BlockPos> sturdy = new HashSet<>();

        FakeWorld put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
            return this;
        }

        FakeWorld load(BlockPos column) {
            loadedColumns.add(column.immutable());
            return this;
        }

        FakeWorld floor(BlockState support, int y, int fromX, int toX, int fromZ, int toZ) {
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    BlockPos at = new BlockPos(x, y, z);
                    put(at, support);
                    sturdy.add(at);
                    load(new BlockPos(x, 64, z));
                }
            }
            return this;
        }

        @Override public boolean loaded(BlockPos pos) {
            return loadedColumns.contains(new BlockPos(pos.getX(), 64, pos.getZ()));
        }

        @Override public BlockState state(BlockPos pos) {
            return blocks.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
        }

        @Override public boolean sturdy(BlockPos pos, Direction face) {
            return sturdy.contains(pos.immutable());
        }
    }

    /** 亮度替身：直接查表；没记录的格是 0。 */
    static final class FakeLights implements ReadsLightLevels {
        final Map<BlockPos, Integer> values = new HashMap<>();

        FakeLights light(BlockPos pos, int level) {
            values.put(pos.immutable(), level);
            return this;
        }

        @Override public OptionalInt blockLight(BlockPos pos) {
            Integer value = values.get(pos.immutable());
            return value == null ? OptionalInt.of(0) : OptionalInt.of(value);
        }

        // 天空光：替身场景都当头顶遮着。
        @Override public OptionalInt skyLight(BlockPos pos) {
            return OptionalInt.of(0);
        }
    }

    /**
     * 亮度替身：按现场里的发光方块现算（发射量 − 曼哈顿距离）。往现场放一支火把，复测量得到新的光；
     * 把火把从表里拿走，复测就又暗回去——"灯插了又没了"的穷尽场景用它摆。
     */
    static final class LiveLights implements ReadsLightLevels {
        private final FakeWorld world;

        LiveLights(FakeWorld world) {
            this.world = world;
        }

        @Override public OptionalInt blockLight(BlockPos pos) {
            if (!world.loaded(pos)) return OptionalInt.empty();
            int brightest = 0;
            for (Map.Entry<BlockPos, BlockState> entry : world.blocks.entrySet()) {
                int emission = entry.getValue().getLightEmission();
                if (emission > 0) {
                    brightest = Math.max(brightest, emission - manhattan(entry.getKey(), pos));
                }
            }
            return OptionalInt.of(brightest);
        }

        // 天空光：替身场景都当头顶遮着；没加载如实给空。
        @Override public OptionalInt skyLight(BlockPos pos) {
            return world.loaded(pos) ? OptionalInt.of(0) : OptionalInt.empty();
        }

        private static int manhattan(BlockPos a, BlockPos b) {
            return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
        }
    }
}
