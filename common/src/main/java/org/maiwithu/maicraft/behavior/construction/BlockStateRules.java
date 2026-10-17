// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirtPathBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * 方块状态规则：蓝图里一格的目标状态怎么归一、哪一半不用单独放、拿什么物品放、特殊格要几样料、哪些格建不了。
 * 例如图纸里的成熟小麦归成幼苗、装满的炼药锅归成空锅、树叶设成玩家手放后不腐烂。
 * 这些只改目标数据，不在世界里放方块、倒水或使用物品。
 */
public final class BlockStateRules {

    private BlockStateRules() {}

    /**
     * 归一后的目标状态；传入对象不改。没列出的属性保持原值。
     * 调用方明确写的属性若被这里改掉，应当拒绝那一格而不是静默换值。
     */
    public static BlockState normalize(BlockState state) {
        if (state == null) return null;
        // 堆肥进度与锅里装的东西都是运行态，不是摆设：照字面摆"攒了三层的堆肥桶"是错的，归成空的那一档。
        if (state.is(Blocks.COMPOSTER)) return Blocks.COMPOSTER.defaultBlockState();
        // 按方块类型认锅而不是按标签：标签要等数据包加载，图纸归一在没有世界时也得能做。
        if (state.getBlock() instanceof AbstractCauldronBlock) return Blocks.CAULDRON.defaultBlockState();
        // 名为 age 的整数属性改成最小值，例如成熟小麦改成刚种下；按属性名识别，模组植物多半也用 age。
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && "age".equals(age.getName())) {
                state = state.setValue(age, Collections.min(age.getPossibleValues()));
                break;
            }
        }
        // 蜂巢里攒的蜜、海龟蛋的孵化进度是凭空产出的运行态；照抄 honey_level=5 等于剪刀一剪白得三个蜂巢。
        if (state.hasProperty(BlockStateProperties.LEVEL_HONEY)) state = state.setValue(BlockStateProperties.LEVEL_HONEY, 0);
        if (state.hasProperty(BlockStateProperties.HATCH)) state = state.setValue(BlockStateProperties.HATCH, 0);
        if (state.hasProperty(BlockStateProperties.STAGE)) state = state.setValue(BlockStateProperties.STAGE, 0);
        // 洞穴藤蔓结不结果同理，而且是白送：一格花一颗发光浆果，摘下来又收回那颗。
        if (state.hasProperty(BlockStateProperties.BERRIES)) state = state.setValue(BlockStateProperties.BERRIES, false);
        // 含水状态去掉：施工先放干的方块，水是另一格倒桶的事。
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        // 活塞伸出与否是运行态。
        if (state.hasProperty(BlockStateProperties.EXTENDED)) state = state.setValue(BlockStateProperties.EXTENDED, false);
        // 反过来的一条：建出来的树叶就是手放树叶；不置 persistent，自然树的图纸照放当场腐烂，放一片烂一片。
        if (state.hasProperty(BlockStateProperties.PERSISTENT)) state = state.setValue(BlockStateProperties.PERSISTENT, true);
        return state;
    }

    /** 床头、门的上半和高植物的上半不用单独放：正常放下另一半时游戏会连同生成。 */
    public static boolean isSecondaryHalf(BlockState state) {
        if (state == null) return false;
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return true;
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
    }

    /** 没有世界信息时拿什么放：耕地和土径按泥土，其余取方块自己的物品；空气表示没有能放它的物品。 */
    public static Item materialItem(Block block) {
        Item override = overrideItem(block);
        return override != null ? override : block.asItem();
    }

    /**
     * 有世界信息时先问方块的"中键取物"，例如小麦对应种子；带方块实体的方块不问，它的自述会读世界里
     * 那一格（图纸里的素白旗会被探针格里的红旗带偏）。问不出来退回方块自己的物品。
     */
    public static Item materialItem(BlockState state, LevelReader level, BlockPos pos) {
        if (state == null) return Items.AIR;
        Item override = overrideItem(state.getBlock());
        if (override != null) return override;
        if (level != null && !state.hasBlockEntity()) {
            try {
                var picked = state.getBlock().getCloneItemStack(level, pos, state);
                if (!picked.isEmpty()) return picked.getItem();
            } catch (RuntimeException ignored) {
                // 问不出来就退回下面那条，不该让一格坏掉整张图纸
            }
        }
        return state.getBlock().asItem();
    }

    private static Item overrideItem(Block block) {
        // 耕地与土径是拿锄、锹在土上加工出来的；人手上没有"一块耕地"可放。
        return block instanceof FarmBlock || block instanceof DirtPathBlock ? Items.DIRT : null;
    }

    /**
     * 从空位建成这一格要几件物品：门上半、床头不计费，双层半砖两件，雪层、海龟蛋、海泡菜、蜡烛按数量，
     * 藤蔓与发光地衣按贴了几面；高草一格一件。空气与液体不在这里算。
     */
    public static int materialCount(BlockState state) {
        if (state == null || state.isAir() || state.getBlock() instanceof LiquidBlock) return 0;
        if (isSecondaryHalf(state)) return 0;
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
        if (state.getBlock() instanceof SnowLayerBlock) return state.getValue(BlockStateProperties.LAYERS);
        if (state.getBlock() instanceof TurtleEggBlock) return state.getValue(BlockStateProperties.EGGS);
        if (state.getBlock() instanceof SeaPickleBlock) return state.getValue(BlockStateProperties.PICKLES);
        // 一格四根蜡烛就是四根：少收三根等于白送三根。
        if (state.hasProperty(BlockStateProperties.CANDLES)) return state.getValue(BlockStateProperties.CANDLES);
        if (state.is(Blocks.VINE) || state.is(Blocks.GLOW_LICHEN)) {
            int faces = 0;
            for (BooleanProperty side : new BooleanProperty[]{BlockStateProperties.NORTH, BlockStateProperties.EAST,
                    BlockStateProperties.SOUTH, BlockStateProperties.WEST, BlockStateProperties.UP, BlockStateProperties.DOWN}) {
                if (state.hasProperty(side) && state.getValue(side)) faces++;
            }
            return Math.max(1, faces);
        }
        return 1;
    }

    /** 一格要的一叠料：按物品类型收，或者组件也要一致（带花纹的旗帜少比一个组件就是白送手工活）。 */
    public record CellNeed(ItemStack stack, boolean exact) {
        public boolean matches(ItemStack other) {
            return exact ? ItemStack.isSameItemSameComponents(stack, other) : other.is(stack.getItem());
        }
    }

    /**
     * 一格不止一种材料、或材料花纹必须一致时的料单：带花的花盆要盆加花；有花纹数据的旗帜要对应花纹的旗帜。
     * 空列表表示沿用目标本身的默认材料数量。
     */
    public static List<CellNeed> cellNeeds(BlockState state, CompoundTag safeData, LevelReader level, BlockPos probe,
                                           HolderLookup.Provider registries) {
        if (state == null) return List.of();
        // 带花的花盆：盆一件，花一件。盆里是什么问方块自己，模组的花盆也照样认。
        if (state.getBlock() instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
            Item plant = materialItem(state, level, probe);
            if (plant == Items.AIR) plant = pot.getPotted().asItem();
            if (plant == Items.AIR || plant == Items.FLOWER_POT) return List.of();
            return List.of(new CellNeed(new ItemStack(Items.FLOWER_POT), false), new CellNeed(new ItemStack(plant), false));
        }
        ItemStack exact = strictItem(state, safeData, registries);
        return exact == null ? List.of() : List.of(new CellNeed(exact, true));
    }

    /** 用图纸里的旗帜颜色和花纹拼出所需物品；没有花纹数据、不是旗帜或解析失败时返回 null。 */
    public static ItemStack strictItem(BlockState state, CompoundTag safeData, HolderLookup.Provider registries) {
        if (state == null || safeData == null || !(state.getBlock() instanceof AbstractBannerBlock) || !safeData.contains("patterns")) return null;
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) return null;
        CompoundTag itemTag = new CompoundTag();
        itemTag.putString("id", BuiltInRegistries.ITEM.getKey(item).toString());
        itemTag.putInt("count", 1);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:banner_patterns", safeData.get("patterns").copy());
        itemTag.put("components", components);
        return ItemStack.parse(registries, itemTag).filter(stack -> !stack.isEmpty()).orElse(null);
    }

    /** 这一格建不了的原因；null 表示可以建。液体不在这里拒绝，它是倒桶的格。 */
    public static String unbuildableReason(BlockState state) {
        if (state == null) return null;
        if (state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.JIGSAW) || state.is(Blocks.STRUCTURE_BLOCK)) {
            return "结构方块和拼图方块不是建筑的一部分";
        }
        // 活塞头与移动中的活塞是活塞自己伸出去的产物，不是能摆的东西；照放一个头就是一块无主的孤块。
        if (state.getBlock() instanceof PistonHeadBlock || state.getBlock() instanceof MovingPistonBlock) {
            return "活塞头属于伸出的活塞，不能单独放";
        }
        return null;
    }
}
