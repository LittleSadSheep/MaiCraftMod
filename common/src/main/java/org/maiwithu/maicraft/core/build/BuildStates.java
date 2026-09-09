package org.maiwithu.maicraft.core.build;

import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;

/**
 * 建筑和蓝图共同使用的方块规则：哪些状态改成初始值、哪些方块不支持建造，以及每格需要什么材料。
 * 例如图纸里的成熟小麦会改成幼苗，装满的炼药锅会改成空锅，树叶会设成玩家手放后不腐烂的状态。
 * 这些是当前实现采用的规则；即使调用者明确提供某个属性，Target 构造时也仍会执行 normalize。
 */
public final class BuildStates {

    private BuildStates() {}

    /**
     * 返回调整后的目标状态，不修改传入对象。null 原样返回；未在下方列出的属性保持原值。
     * 此方法只改目标数据，不会在游戏世界里放方块、倒水或使用物品。
     */
    public static BlockState normalize(BlockState state) {
        if (state == null) {
            return null;
        }
        // 堆肥进度与锅里装的东西都是运行态,不是摆设:图纸里存着"攒了三层的堆肥桶"
        // 或"装着岩浆的锅",照字面摆是错的,归一成空的那一档
        if (state.is(Blocks.COMPOSTER)) {
            return Blocks.COMPOSTER.defaultBlockState();
        }
        if (state.is(BlockTags.CAULDRONS)) {
            return Blocks.CAULDRON.defaultBlockState();
        }
        // 把名为 age 的整数属性改成它允许的最小值，例如成熟小麦改成刚种下的阶段。
        // 这里按属性名识别，没有逐个确认模组是否也用 age 表示植物生长。
        for (Property<?> p : state.getProperties()) {
            if (p instanceof net.minecraft.world.level.block.state.properties.IntegerProperty age
                    && "age".equals(age.getName())) {
                state = state.setValue(age, java.util.Collections.min(age.getPossibleValues()));
                break;   // 一块方块只会有一种 age
            }
        }
        // 蜂巢里攒的蜜是运行态,而且是凭空产出:照抄一个 honey_level=5 的蜂巢,
        // 玩家拿剪刀直接剪出三个蜂巢。孵化进度同理会提前刷出海龟。
        if (state.hasProperty(BlockStateProperties.LEVEL_HONEY)) {
            state = state.setValue(BlockStateProperties.LEVEL_HONEY, 0);
        }
        if (state.hasProperty(BlockStateProperties.HATCH)) {
            state = state.setValue(BlockStateProperties.HATCH, 0);
        }
        if (state.hasProperty(BlockStateProperties.STAGE)) {
            state = state.setValue(BlockStateProperties.STAGE, 0);
        }
        // 洞穴藤蔓结不结果同理,而且这一条是白送:一格花一颗发光浆果,玩家伸手一摘
        // 把那颗原样收回、藤蔓还留着——一整面二百格的藤蔓墙造价为零。
        if (state.hasProperty(BlockStateProperties.BERRIES)) {
            state = state.setValue(BlockStateProperties.BERRIES, false);
        }
        // 建造目标统一去掉含水状态；这里没有安排拿水桶把水补回去的步骤。
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        // 活塞伸出与否是运行态
        if (state.hasProperty(BlockStateProperties.EXTENDED)) {
            state = state.setValue(BlockStateProperties.EXTENDED, false);
        }
        // 反过来的一条:建出来的树叶就是"手放树叶"。不置 persistent,自然树的
        // 蓝图照放会当场腐烂,放一片烂一片永远建不完
        if (state.hasProperty(BlockStateProperties.PERSISTENT)) {
            state = state.setValue(BlockStateProperties.PERSISTENT, true);
        }
        return state;
    }

    /**
     * 床头、门的上半和高植物的上半不需要独立再放一次；正常放下床脚或下半时，游戏会连同另一半一起生成。
     * 蓝图导入据此只保留需要主动放置的一半，避免多算材料或把床头又当作一张床来放。
     */
    public static boolean isSecondaryHalf(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART)
                        == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
            return true;
        }
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
    }

    /**
     * 没有世界信息时使用的材料查询：耕地和土径按土计，其余取方块自己的物品。
     * 返回空气表示这里没有找到材料；是否跳过或报错由调用者决定。
     */
    public static net.minecraft.world.item.Item materialItem(
            net.minecraft.world.level.block.Block block) {
        net.minecraft.world.item.Item override = overrideItem(block);
        return override != null ? override : block.asItem();
    }

    /**
     * 有世界信息时，先处理耕地和土径，再尝试方块的“中键取物品”回答，例如小麦对应种子。
     * 带方块实体的方块跳过中键查询，避免误读目标坐标上原有旗帜、箱子等的数据。
     * 没有世界、回答为空或查询抛异常时，退回方块自己的物品；因此不是所有模组方块都保证能找到材料。
     */
    public static net.minecraft.world.item.Item materialItem(
            BlockState state, net.minecraft.world.level.LevelReader level,
            net.minecraft.core.BlockPos pos) {
        if (state == null) {
            return net.minecraft.world.item.Items.AIR;
        }
        net.minecraft.world.item.Item override = overrideItem(state.getBlock());
        if (override != null) {
            return override;
        }
        // 带方块实体的方块<b>不问</b>。它们的自述会去读 {@code pos} 那一格<b>世界里</b>的
        // 方块实体,而那一格此刻放的不是图纸里这块——旗帜、陶罐、潜影盒都是这样。后果很
        // 具体:探针那一格恰好立着一面红旗,图纸里所有素白旗的记账物品就全变成红旗,
        // 预检索要红旗、逐格闸门查红旗、实扣扣红旗,而落位放的是白旗。
        //
        // 别处解决这个问题靠养一个一次性世界(那里没有别人的方块实体);我们没有,那就
        // 只问答案与世界无关的那些方块。花盆、竹笋、瓜藤、藤蔓段都不带方块实体,所以
        // 该拿到的答案一个都没少;带方块实体的那些本来也不靠这条路(旗帜走的是
        // {@link #strictItem},读的是图纸自己那份数据)。
        if (level != null && !state.hasBlockEntity()) {
            try {
                var picked = state.getBlock().getCloneItemStack(level, pos, state);
                if (!picked.isEmpty()) {
                    return picked.getItem();
                }
            } catch (RuntimeException ignored) {
                // 问不出来就退回下面那条,不该让一格坏掉整张图纸
            }
        }
        return state.getBlock().asItem();
    }

    /** 耕地和土径要在土上使用工具形成，所以材料准备按土来算。 */
    private static net.minecraft.world.item.Item overrideItem(
            net.minecraft.world.level.block.Block block) {
        // 耕地与土径是拿锄/锹在土上加工出来的。它们自述的是自己(那两件物品存在),
        // 但人手上没有"一块耕地"可放。
        if (block instanceof net.minecraft.world.level.block.FarmBlock
                || block instanceof net.minecraft.world.level.block.DirtPathBlock) {
            return net.minecraft.world.item.Items.DIRT;
        }
        return null;
    }

    /**
     * 补充“一格不止一种材料”或“材料花纹必须一致”的要求。带花的花盆需要一个盆加一株植物；
     * 有花纹数据的旗帜需要对应花纹的旗帜物品。返回空列表表示沿用目标本身的默认材料数量。
     */
    public static List<BuildTaskRecord.CellNeed> cellNeeds(
            BlockState state, net.minecraft.nbt.CompoundTag safeData,
            net.minecraft.world.level.LevelReader level, net.minecraft.core.BlockPos probe,
            net.minecraft.core.HolderLookup.Provider registries) {
        if (state == null) {
            return List.of();
        }
        // 带花的花盆:盆一件,花一件。盆里那株是什么,问方块自己——中键取方块给的正是
        // 那株植物。不写死一张"哪个盆装哪种花"的对照表(那张表在别处是二十多行的
        // switch),模组的花盆也照样认。
        if (state.getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock pot
                && pot.getPotted() != Blocks.AIR) {
            net.minecraft.world.item.Item plant = materialItem(state, level, probe);
            if (plant == net.minecraft.world.item.Items.AIR) {
                plant = pot.getPotted().asItem();
            }
            if (plant == net.minecraft.world.item.Items.AIR
                    || plant == net.minecraft.world.item.Items.FLOWER_POT) {
                return List.of();   // 空盆,或者问不出盆里是什么:按一件盆走默认口径
            }
            return List.of(
                    new BuildTaskRecord.CellNeed(new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.item.Items.FLOWER_POT), false),
                    new BuildTaskRecord.CellNeed(new net.minecraft.world.item.ItemStack(plant), false));
        }
        net.minecraft.world.item.ItemStack exact = strictItem(state, safeData, registries);
        return exact == null ? List.of() : List.of(new BuildTaskRecord.CellNeed(exact, true));
    }

    /**
     * 用蓝图里的旗帜颜色和花纹拼出所需物品，让后续选料可以比较物品组件。
     * 没有花纹数据、不是旗帜或物品解析失败时返回 null，表示这里没有给出精确物品要求。
     */
    public static net.minecraft.world.item.ItemStack strictItem(
            BlockState state, net.minecraft.nbt.CompoundTag safeData,
            net.minecraft.core.HolderLookup.Provider registries) {
        if (state == null || safeData == null || !state.is(net.minecraft.tags.BlockTags.BANNERS)
                || !safeData.contains("patterns")) {
            return null;
        }
        net.minecraft.world.item.Item item = state.getBlock().asItem();
        if (item == net.minecraft.world.item.Items.AIR) {
            return null;
        }
        net.minecraft.nbt.CompoundTag itemTag = new net.minecraft.nbt.CompoundTag();
        itemTag.putString("id", net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(item).toString());
        itemTag.putInt("count", 1);
        net.minecraft.nbt.CompoundTag components = new net.minecraft.nbt.CompoundTag();
        components.put("minecraft:banner_patterns", safeData.get("patterns").copy());
        itemTag.put("components", components);
        return net.minecraft.world.item.ItemStack.parse(registries, itemTag)
                .filter(s -> !s.isEmpty())
                .orElse(null);
    }

    /**
     * 这一格能不能建;不能建的话,<b>说清为什么</b>。
     *
     * @return null = 可以建;否则是给模型看的一句话
     */
    public static String unbuildableReason(BlockState state) {
        if (state == null) {
            return null;
        }
        if (state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.JIGSAW) || state.is(Blocks.STRUCTURE_BLOCK)) {
            return "structure tooling blocks are not part of a building";
        }
        // 活塞头与移动中的活塞是活塞自己伸出去的产物,不是能摆的东西。我们在
        // normalize 里已经把 EXTENDED 归一成收回,照放一个头就是一块无主的孤块。
        if (state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonHeadBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.piston.MovingPistonBlock) {
            return "a piston head belongs to an extended piston, not to a build";
        }
        if (state.getBlock() instanceof LiquidBlock) {
            // 能力边界,得说是边界:回一句"未知方块"的话,名字明明是对的,模型只会
            // 以为自己拼错了,换个写法再试一遍
            return "she does not place or drain liquids; leave water and lava out of it"
                    + " and dig the basin instead, or let the player pour it";
        }
        return null;
    }
}
