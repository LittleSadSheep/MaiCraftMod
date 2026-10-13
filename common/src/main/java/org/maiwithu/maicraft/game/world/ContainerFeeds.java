// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 这只容器是不是在给机器供料（进料箱、补料箱）：下面挂着漏斗或机械动力的溜槽在往下抽，
 * 或者旁边贴着正在往外抽的机械动力漏斗（普通漏斗 extracting、传送带漏斗 shape=pushing）。
 * 出料箱是漏斗、溜槽往里放东西的，不算。只认这里列出的方块，不凭名字猜别的模组。
 */
public final class ContainerFeeds {

    /** 机械动力里从上面的容器往下抽东西的溜槽。 */
    private static final Set<String> CHUTES = Set.of("create:chute", "create:smart_chute");
    /** 机械动力贴在容器上的漏斗：extracting=true 时往外抽。 */
    private static final Set<String> FUNNELS = Set.of("create:andesite_funnel", "create:brass_funnel");
    /** 机械动力搭在传送带上的漏斗：shape=pushing 时把容器里的往带子上推。 */
    private static final Set<String> BELT_FUNNELS = Set.of("create:andesite_belt_funnel", "create:brass_belt_funnel");

    private ContainerFeeds() {}

    /** 这只容器正被自动化往外抽东西。 */
    public static boolean feedsAutomation(BlockGetter level, BlockPos container) {
        BlockState below = level.getBlockState(container.below());
        if (below.getBlock() instanceof HopperBlock || CHUTES.contains(id(below))) return true;
        for (Direction side : Direction.values()) {
            BlockPos at = container.relative(side);
            BlockState state = level.getBlockState(at);
            String id = id(state);
            boolean pulling = FUNNELS.contains(id) && "true".equals(value(state, "extracting"))
                    || BELT_FUNNELS.contains(id) && "pushing".equals(value(state, "shape"));
            if (pulling && attachedTo(state, at, container)) return true;
        }
        return false;
    }

    // 漏斗贴着的那一格在它朝向的反面：普通漏斗看 facing，传送带漏斗看 horizontal_facing。
    private static boolean attachedTo(BlockState funnel, BlockPos at, BlockPos container) {
        for (String name : new String[] {"facing", "horizontal_facing"}) {
            String facing = value(funnel, name);
            if (facing == null) continue;
            Direction direction = Direction.byName(facing);
            if (direction != null && at.relative(direction.getOpposite()).equals(container)) return true;
        }
        return false;
    }

    // 方块状态里某一项的取值（写法与 F3 里看到的一样）；没有这一项给 null。
    private static String value(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) return name(state, property);
        }
        return null;
    }

    private static <T extends Comparable<T>> String name(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
