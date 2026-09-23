package org.maiwithu.maicraft.core.pathing.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.init.InitTag;

/**
 * 多种任务共用的方块判断：哪里可以作为站位、怎样表示半砖上的脚位、方块是否危险、挖开会不会放出液体、工具能否取得掉落物。
 * 可通行分类会把可手开的门视为能通过，不等于此刻身体碰撞盒可以直接穿过；需要精确碰撞时应看相应现场检查。
 */
public final class BlockHelper {

    private BlockHelper() {}

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /**
     * 判断角色身体能否占据此格：格内无碰撞，且不是禁止进入的液体。空气、草和花等均可通行。
     *
     * <p>水面直接纳入通行判定，不另造游泳移动。流动水会把角色推离路线，因此拒绝进入；静水只允许占据水面格，
     * 即其上方没有液体，从而让移动图沿水面通行而不会规划水下走廊。熔岩始终不可通行，因此普通前进、上升和下降路线无需专设游泳边。
     */
    public static boolean canWalkThrough(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (!fluid.is(FluidTags.WATER)) return false;       // lava etc. — never
            if (isFlowingWater(level, pos)) return false;        // current shoves us
            // 只走水面：上方有液体或睡莲叶时，角色会处于水下或被覆盖，不是自由水面。
            BlockState up = level.getBlockState(pos.above());
            if (!up.getFluidState().isEmpty()) return false;
            return !up.is(Blocks.LILY_PAD);
        }
        // 明确列出角色绝不能穿过的方块。火、蜘蛛网、甜浆果丛、细雪、打开的活板门和大叶草等碰撞形状可能为空或不完整，
        // 单靠下方形状检测会误判为可通行，因此必须先用此名单拦截。
        if (block instanceof BaseFireBlock
                || state.is(Blocks.COBWEB) || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.COCOA) || block instanceof AbstractSkullBlock
                || state.is(Blocks.BUBBLE_COLUMN) || block instanceof ShulkerBoxBlock
                || block instanceof SlabBlock || block instanceof TrapDoorBlock
                || state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.END_ROD)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POINTED_DRIPSTONE)
                || block instanceof AmethystClusterBlock || block instanceof AzaleaBlock
                || state.is(Blocks.BIG_DRIPLEAF) || state.is(Blocks.POWDER_SNOW)
                || block instanceof CauldronBlock) {
            return false;
        }
        // 木门和栅栏门即使关闭也可规划通过，执行器会亲手打开；铁门没有红石无法打开，因此继续进入碰撞检测并视为实心障碍。
        if (isOpenableDoor(state)) {
            return true;
        }
        // 薄地毯不会阻止角色行走。
        if (block instanceof CarpetBlock) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        return shape.isEmpty();
    }

    /**
     * 判断门是否能由角色徒手打开：木门和栅栏门可以，铁门需要红石而不可开。寻路将可手动打开的门视为可通过且不破坏，
     * 到达关闭的门前后由执行器右键打开。
     */
    // 这里当前按具体的原版铁门排除，其他 DoorBlock 都归入可打开类；并没有逐个读取门材质的手动开启能力。
    public static boolean isOpenableDoor(BlockState state) {
        if (state.is(Blocks.IRON_DOOR)) {
            return false;
        }
        return state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof FenceGateBlock;
    }

    /** 查询门或栅栏门当前是否打开，即方块状态的 OPEN 属性是否为 true。 */
    public static boolean isDoorOpen(BlockState state) {
        return state.hasProperty(BlockStateProperties.OPEN)
                && state.getValue(BlockStateProperties.OPEN);
    }

    /**
     * 判断角色能否从相邻的 {@code fromPos} 穿过 {@code doorPos} 上当前形态的门或栅栏门。栅栏门只有打开时可通行；
     * 门还要结合朝向：{@code (facingAxis == approachAxis) == open} 才能通过。打开但门板横挡路线的门仍不可通行，
     * 与接近方向平行的关闭门则可通过。若此处判为不可通行，执行器会右键切换门状态，以打开挡路的关闭门或关闭横挡路线的打开门。
     * 非门类方块一律视为可通过，由其他判定负责。
     */
    public static boolean isDoorwayPassable(BlockGetter level, BlockPos doorPos, BlockPos fromPos) {
        BlockState state = level.getBlockState(doorPos);
        Block block = state.getBlock();
        if (block instanceof FenceGateBlock) {
            return state.getValue(BlockStateProperties.OPEN);
        }
        if (!(block instanceof DoorBlock)) {
            return true;
        }
        if (fromPos.equals(doorPos)) {
            return false;
        }
        Direction.Axis facing = state.getValue(HorizontalDirectionalBlock.FACING).getAxis();
        boolean open = state.getValue(BlockStateProperties.OPEN);
        Direction.Axis approach;
        if (fromPos.north().equals(doorPos) || fromPos.south().equals(doorPos)) {
            approach = Direction.Axis.Z;
        } else if (fromPos.east().equals(doorPos) || fromPos.west().equals(doorPos)) {
            approach = Direction.Axis.X;
        } else {
            return true;   // not cardinally adjacent (diagonal / wrong Y) → don't toggle
        }
        return (facing == approach) == open;
    }

    /** 判断此格是否为水源或流动水。 */
    public static boolean isWater(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).getFluidState()
                .is(FluidTags.WATER);
    }

    /**
     * 判断此格是否属于流动水。非水源方块一定在流动；水源若向水平相邻的非水源格供水（池塘边缘）也视为流动，
     * 因为水流会推开角色。此判定让路线只沿静水区域前进。
     */
    public static boolean isFlowingWater(BlockGetter level, BlockPos pos) {
        FluidState fluid = level.getBlockState(pos).getFluidState();
        if (!fluid.is(FluidTags.WATER)) return false;
        if (!fluid.isSource()) return true;                 // amount < 8 → flowing
        for (Direction d : HORIZONTAL) {
            FluidState n = level.getBlockState(pos.relative(d)).getFluidState();
            if (n.is(FluidTags.WATER) && !n.isSource()) return true;
        }
        return false;
    }

    /**
     * 判断角色能否站在此方块顶部，也就是它是否构成稳定地面。
     *
     * <p>水格只有在其正上方仍有水时才算可站立面，角色因此浮在水面并踩在水下水柱上，而不是站在最上层空气格。
     * 配合 {@link #canWalkThrough} 的水面通行限制，可将角色约束在水面高度。
     */
    public static boolean canWalkOn(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (!fluid.is(FluidTags.WATER)) return false;   // lava is never a floor
            // 只有上方仍有水时才在此格站立，表示角色处于水下并向水面浮起。
            return isWater(level, pos.above());
        }
        if (state.isAir()) return false;
        // 明确允许站立的非完整碰撞方块名单；下方完整方块检测会漏掉农田、道路（高度为 15/16 格）、箱子、梯子和杜鹃等安全支撑面。
        if (state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.SOUL_SAND)) return true;
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.ENDER_CHEST)) return true;
        if (state.is(Blocks.GLASS) || state.getBlock() instanceof StainedGlassBlock) return true;
        if (state.is(Blocks.LADDER)) return true;
        if (state.getBlock() instanceof AzaleaBlock) return true;
        if (state.getBlock() instanceof StairBlock) return true;
        if (state.getBlock() instanceof SlabBlock) {
            // 所有半砖都视为地面；若角色站在下半砖上，playerFeet() 会将脚位统一表示为其上方格。
            return true;
        }
        // 岩浆块和蜂蜜块虽然是完整方块，但会造成伤害或粘滞，因此不能作为地面。
        if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.HONEY_BLOCK)) return false;
        // 其他情况按普通完整碰撞方块处理。
        return state.isCollisionShapeFullBlock(level, pos);
    }

    /**
     * 返回寻路使用的脚位：将实际位置上移 0.1251，避免角色陷入灵魂沙或农田后被读成更低一格；若所在格为半砖或楼梯，则取其上方格。
     * 这与 {@code Movement.feet} 一致，让移动图将两种非整高支撑面都表示为站在其上一格。
     */
    public static BlockPos playerFeet(BlockGetter level, double x, double y, double z) {
        BlockPos f = BlockPos.containing(x, y + 0.1251, z);
        Block block = level.getBlockState(f).getBlock();
        if (block instanceof SlabBlock || block instanceof StairBlock) {
            return f.above();
        }
        return f;
    }

    /** 判断半砖是否占据所在方块格的下半部。 */
    public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    public static boolean isBottomSlab(BlockGetter level, BlockPos pos) {
        return isBottomSlab(level.getBlockState(pos));
    }

    /**
     * 判断脚位格是否可站立：下方有坚实地面，且上方留有两个方块格的空间。
     */
    public static boolean isStandable(BlockGetter level, BlockPos feet) {
        return canWalkOn(level, feet.below())
                && canWalkThrough(level, feet)
                && canWalkThrough(level, feet.above());
    }

    /**
     * 判断是否为真正干燥的站立格。与 {@link #isStandable} 不同，此判定排除普通寻路使用的水面网格：脚部和头部必须无液体且可通行，支撑面也必须稳定。
     * 海岸接近点、建筑勘查点、钓鱼站位等明确要求陆地位置的语义目标应使用此谓词，不能把可游泳水域当作陆地。
     */
    public static boolean isDryStandable(BlockGetter level, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos support = feet.below();
        return level.getFluidState(feet).isEmpty()
                && level.getFluidState(head).isEmpty()
                && canWalkThrough(level, feet)
                && canWalkThrough(level, head)
                && canWalkOn(level, support);
    }

    /**
     * 判断此方块是否为角色绝不能站入或贴近破坏的危险物。熔岩和火焰属于硬性危险；当前只纳入明确会造成伤害的情形。
     */
    public static boolean isHazard(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            // 熔岩属于危险液体；水在上方通行判定中已被排除，但本身不是伤害危险。
            return fluid.getType().getBucket() == Items.LAVA_BUCKET;
        }
        // 非液体危险方块名单，角色绝不能寻路进入；岩浆块也会因接触造成伤害。
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.BUBBLE_COLUMN);
    }

    /**
     * 判断角色不应步行或冲刺进入的格子：任意液体（包括 {@link #isHazard} 未视为伤害危险的水）及同一危险方块集合。
     * 用于继续走向前方格子的安全检查，例如边走边挖抑制器和安全下降模式；水流也会推开角色，因此同样要拦截。
     */
    public static boolean avoidWalkingInto(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return true;
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.BUBBLE_COLUMN);
    }

    /**
     * 判断能否瞄准此方块的 {@code face} 并以它为支撑面放置。该面必须坚固，遵循原版判断方块是否获得支撑的规则。
     * 完整方块、玻璃、上半砖顶部、楼梯实心背面和灵魂沙顶部均可支撑；下半砖的顶面位于方块格内部，边界射线无法命中，因此不接受。
     * 判定针对共享的具体面而非整个方块，所以半高平台可从上方提供支撑，即使其侧面不够坚固。
     *
     * <p>少数具有特殊行为的方块无论表面多坚固都拒绝作为放置目标：竹子和滴水石会折断，活塞移动时会改变位置，脚手架会移动，
     * 潜影盒会在点击时开盖，紫水晶簇会碎裂。工作台、箱子和漏斗等界面方块无需在此排除，因为放置总会潜行，潜行点击会贴方块放置而不会打开界面。
     */
    public static boolean canPlaceAgainst(BlockGetter level, BlockPos pos, Direction face) {
        BlockState state = level.getBlockState(pos);
        Block b = state.getBlock();
        if (b instanceof BambooStalkBlock || b instanceof MovingPistonBlock
                || b instanceof ScaffoldingBlock || b instanceof ShulkerBoxBlock
                || b instanceof PointedDripstoneBlock || b instanceof AmethystClusterBlock) {
            return false;
        }
        return state.isFaceSturdy(level, pos, face);
    }

    /**
     * 返回破坏 {@code pos} 后会流入该格的相邻方向：仅可能是 {@link Direction#UP} 或水平方向，绝不会是 {@code DOWN}。
     * 原版液体会沿水平四向及向下扩散，因此上方或侧面的液源会填入刚腾空的格子，下方液体不会向上流入。
     * 熔岩危险性高于水，若两者都可能流入则优先返回熔岩方向；不会引发流动时返回 {@code null}。
     *
     * <p>这是判断“破坏方块会释放液体”的唯一依据；A* 破坏成本通过 {@link #breakWouldCreateFlow} 使用它，
     * 确保“可安全穿过”和“可安全主动挖掘”采用一致规则。
     */
    // 检查上方和四周会不会放出流体，优先返回岩浆方向；不检查下方，因为下方流体不会因挖上面一格而向上灌。
    public static Direction fluidReleasedByBreaking(BlockGetter level, BlockPos pos) {
        Direction water = null;
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            FluidState fluid = level.getBlockState(pos.relative(dir)).getFluidState();
            if (fluid.isEmpty()) continue;
            if (fluid.is(FluidTags.LAVA)) return dir;   // worst hazard wins
            if (water == null) water = dir;
        }
        return water;
    }

    /**
     * {@link #fluidReleasedByBreaking} 的布尔形式，供 A* 破坏成本使用：破坏 {@code pos} 是否会让相邻液体流入并淹没路线或灌入熔岩？
     */
    public static boolean breakWouldCreateFlow(BlockGetter level, BlockPos pos) {
        return fluidReleasedByBreaking(level, pos) != null;
    }

    /**
     * 判断方块是否能够被破坏。基岩和硬度小于零的方块不可破坏；空气没有可破坏目标，因此返回 false。
     */
    public static boolean isBreakable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        float hardness = state.getDestroySpeed(asBlockGetterLevel(level), pos);
        return hardness >= 0.0f;
    }

    /**
     * {@code getDestroySpeed} 接受 {@code BlockGetter}；此方法仅透传参数并保留适配边界，以便未来按加载器或版本转换参数类型。
     */
    private static BlockGetter asBlockGetterLevel(BlockGetter level) {
        return level;
    }

    /**
     * 判断 {@code inv} 中的工具能否采集 {@code state} 的掉落物，即破坏方块后能否获得物品，而不只是将其摧毁。
     * 方块无需工具即可掉落，或任一背包槽位持有正确工具时才返回 true。对 {@code requiresCorrectToolForDrops} 方块使用错误工具会空手破坏且无掉落，
     * 因此成本模型会拒绝该目标，break/mine 工具也会阻止操作。此处与 {@code switchToBestTool} 共用全背包扫描；后者可把背包里的工具换到手上，
     * 所以判定、成本和实际执行都检查完整背包，而不只检查快捷栏。
     */
    // 看是否具备取得正常掉落物的工具；背包扫描范围由传入容器决定，这里不负责把该物品装备到手上。
    public static boolean canHarvest(Container inv, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    /** 便捷判定：可安全用作脚手架支撑的完整实心方块。 */
    public static boolean isReplaceableForPlacement(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /**
     * 命中 do_not_break 方块标签的方块:硬禁挖的唯一真源,任何开关
     * 也不解除。默认成员是设施类(床/门/活板门/栅栏门,见
     * ModBlockTagData);工作台/熔炉/箱子/陷阱箱等常规功能方块不在
     * 硬禁内,它们走 NavSettings.blocksToAvoidBreaking 软清单
     * (挖掘成本 ×10,无路可走仍会破坏)。数据包可往此标签追加任何要
     * 硬禁挖的方块;带方块实体的方块(漏斗/潜影盒/刷怪笼/信标等)默认
     * 与泥土一样可破坏、无惩罚,除非数据包把它们加进此标签。
     */
    public static boolean shouldAvoidBreaking(BlockGetter level, BlockPos pos) {
        // 标签成员测试只读不可变 BlockState holder,off-thread 搜索可安全调用。
        BlockState state = level.getBlockState(pos);
        return state.is(InitTag.DO_NOT_BREAK);
    }

    /**
     * 破坏 {@code pos} 是否会使正上方的 {@link FallingBlock}（沙子、砂砾、铁砧或混凝土粉末）落到角色身上？若会则拒绝破坏，避免被埋住或窒息。
     */
    public static boolean breakReleasesFallingBlock(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos.above()).getBlock() instanceof FallingBlock;
    }
}
