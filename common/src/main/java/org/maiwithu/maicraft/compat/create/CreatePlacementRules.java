// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;

/**
 * Create 方块放法和原版默认不一样的几种，交给施工引擎：
 * <ul>
 *   <li>带漏斗没有自己的物品：拿对应的漏斗放到传送带上变成带漏斗，所以要等下面的带子先在；</li>
 *   <li>隧道只能放在传送带上：等下面的带子；</li>
 *   <li>横着的齿轮箱（轴向东西或南北）拿竖直齿轮箱放：先按齿轮箱放下，再看旁边伸过来的轴定轴向——
 *       只有一个水平方向有轴伸过来时轴向取和它垂直的那个，不然按角色面朝方向转九十度，所以先等那两侧的轴；</li>
 *   <li>动力辊放下的那一格下面是实心顶面时会被抬高一格：想让它落在某格，下面空着、再下面是实心面时对着下面那一格放，
 *       下面就是实心面时怎么放都落不到这里；</li>
 *   <li>无线红石信号终端贴在背后那一块上：等背后那块先放好。</li>
 * </ul>
 * 只回答事实，不放方块；规则依据 Create 6.0.11 的 FunnelItem、BeltTunnelItem、VerticalGearboxItem、RollerBlockItem。
 */
final class CreatePlacementRules implements PlacementRules {

    private static final String NS = CreateMachineCatalog.MOD_ID + ":";
    /** 带漏斗 → 放它用的漏斗。 */
    private static final Map<String, String> BELT_FUNNELS = Map.of(
            NS + "andesite_belt_funnel", NS + "andesite_funnel",
            NS + "brass_belt_funnel", NS + "brass_funnel");
    private static final Set<String> TUNNELS = Set.of(NS + "andesite_tunnel", NS + "brass_tunnel");
    private static final String GEARBOX = NS + "gearbox";
    private static final String VERTICAL_GEARBOX = NS + "vertical_gearbox";
    private static final String ROLLER = NS + "mechanical_roller";
    private static final String REDSTONE_LINK = NS + "redstone_link";
    private static final BlockPos BELOW = new BlockPos(0, -1, 0);

    private final CreateCompat compat;

    CreatePlacementRules(CreateCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public Optional<Placement> placementFor(BlockState state, BlockPos at, ReadsBlocks world) {
        String id = idOf(state.getBlock());
        // 带漏斗拿漏斗放，对着它自己那一格：落在传送带上时漏斗物品自己变成带漏斗。
        if (BELT_FUNNELS.containsKey(id)) return item(BELT_FUNNELS.get(id)).map(funnel -> new Placement(funnel, at));
        // 横着的齿轮箱只有竖直齿轮箱物品放得出来，齿轮箱物品放下一律竖着。
        if (id.equals(GEARBOX) && state.getValue(BlockStateProperties.AXIS).isHorizontal()) {
            return item(VERTICAL_GEARBOX).map(vertical -> new Placement(vertical, at));
        }
        if (id.equals(ROLLER)) return Optional.of(new Placement(state.getBlock().asItem(), rollerPlaceAt(at, world)));
        return Optional.empty();
    }

    @Override public List<BlockPos> placeAfter(BlockState state) {
        String id = idOf(state.getBlock());
        if (BELT_FUNNELS.containsKey(id) || TUNNELS.contains(id)) return List.of(BELOW);
        if (id.equals(GEARBOX) && state.getValue(BlockStateProperties.AXIS).isHorizontal()) {
            // 轴向南北的竖直齿轮箱靠东西两侧伸过来的轴定向，反过来一样：先等那两侧。
            Direction side = state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Z ? Direction.EAST : Direction.NORTH;
            return List.of(BlockPos.ZERO.relative(side), BlockPos.ZERO.relative(side.getOpposite()));
        }
        if (id.equals(REDSTONE_LINK) && state.hasProperty(BlockStateProperties.FACING)) {
            return List.of(BlockPos.ZERO.relative(state.getValue(BlockStateProperties.FACING).getOpposite()));
        }
        return List.of();
    }

    // 竖直齿轮箱放下以后：数四个水平方向哪几个有轴伸过来，只有一个轴向时取和它垂直的，不然按面朝方向转九十度。
    @Override public BlockState afterPlacing(Item item, BlockState placed, ClientLevel level, BlockPos at, Direction playerFacing) {
        if (!idOf(item).equals(VERTICAL_GEARBOX) || !idOf(placed.getBlock()).equals(GEARBOX)) return placed;
        Set<Direction.Axis> incoming = EnumSet.noneOf(Direction.Axis.class);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = at.relative(side);
            if (compat.shaftFaces(level, neighbor, level.getBlockState(neighbor)).contains(side.getOpposite())) {
                incoming.add(side.getAxis());
            }
        }
        return placed.setValue(BlockStateProperties.AXIS, verticalGearboxAxis(incoming, playerFacing));
    }

    /** 竖直齿轮箱的轴向，纯函数：只有一个水平轴向有轴伸过来时取和它垂直的那个，不然取面朝方向顺时针转九十度的轴。 */
    static Direction.Axis verticalGearboxAxis(Set<Direction.Axis> incoming, Direction playerFacing) {
        if (incoming.size() == 1) return incoming.contains(Direction.Axis.X) ? Direction.Axis.Z : Direction.Axis.X;
        return playerFacing.getClockWise().getAxis();
    }

    /**
     * 动力辊对着哪一格放，纯函数：下面那格是实心顶面时原版会把它抬到上面一格，只有对着下面那格放才落得到这里——
     * 那格被占着就是放不了（引擎照实记）；下面空着、再下面是实心顶面时也对着下面那格放，点地面就行；其余对着它自己。
     */
    static BlockPos rollerPlaceAt(BlockPos at, ReadsBlocks world) {
        BlockPos below = at.below();
        if (!world.loaded(below)) return at;
        BlockState under = world.state(below);
        if (fullTop(under)) return below;
        if (under.canBeReplaced() && world.loaded(below.below()) && fullTop(world.state(below.below()))) return below;
        return at;
    }

    private static boolean fullTop(BlockState state) {
        return Block.isFaceFull(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), Direction.UP);
    }

    private static Optional<Item> item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key);
    }

    private static String idOf(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
