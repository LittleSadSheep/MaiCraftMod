// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.WaterFluid;

/**
 * 接住一掉的办法：落进水里、蛛网、没长大的甜浆果丛、缠怨藤、垂泪藤会把摔落清零；黏液块把人弹住；
 * 干草块只把伤害减到五分之一（不免伤），所以排在最后、还要算减完能不能活。
 */
public enum LandingAid {
    WATER(Items.WATER_BUCKET, Blocks.WATER),
    COBWEB(Items.COBWEB, Blocks.COBWEB),
    SWEET_BERRIES(Items.SWEET_BERRIES, Blocks.SWEET_BERRY_BUSH),
    TWISTING_VINES(Items.TWISTING_VINES, Blocks.TWISTING_VINES),
    WEEPING_VINES(Items.WEEPING_VINES, Blocks.WEEPING_VINES),
    SLIME(Items.SLIME_BLOCK, Blocks.SLIME_BLOCK),
    HAY(Items.HAY_BLOCK, Blocks.HAY_BLOCK);

    private final Item item;
    private final Block block;

    LandingAid(Item item, Block block) {
        this.item = item;
        this.block = block;
    }

    /** 放这一种要拿在手上的物品。 */
    public Item item() {
        return item;
    }

    public String itemId() {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** 放下去长出来的方块（水是水方块）。 */
    public Block block() {
        return block;
    }

    /** 黏液块、干草是实心的，放在落点下面一格当落脚面，人落在它上面；其余放在落点那一格，人落进去。 */
    public boolean raisesSupport() {
        return this == SLIME || this == HAY;
    }

    /** 免不免伤：干草只减伤。 */
    public boolean damageFree() {
        return this != HAY;
    }

    /** 这一格是不是这种东西（藤蔓连茎带头都算）。 */
    public boolean matches(BlockState state) {
        if (this == WATER) return state.getFluidState().getType() instanceof WaterFluid;
        return state.is(block) || this == TWISTING_VINES && state.is(Blocks.TWISTING_VINES_PLANT)
                || this == WEEPING_VINES && state.is(Blocks.WEEPING_VINES_PLANT);
    }

    /**
     * 现成的这一格真能接住人：水要是水源，浆果丛要没长大（长大的会扎人），蛛网、藤蔓要在原版"清零摔落"的标签里；
     * 只看到同名方块还不够。黏液块、干草现成就算（干草另算减完伤能不能活）。
     */
    public boolean cushions(BlockState state) {
        if (!matches(state)) return false;
        return switch (this) {
            case WATER -> state.getFluidState().isSource();
            case SWEET_BERRIES -> state.getValue(BlockStateProperties.AGE_3) == 0;
            case COBWEB, TWISTING_VINES, WEEPING_VINES -> state.is(BlockTags.FALL_DAMAGE_RESETTING);
            case SLIME, HAY -> true;
        };
    }
}
