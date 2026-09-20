// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;

/** 配方类型、炉子方块、菜单类型和燃料时间必须对应同一种设备，不能各自维护一份猜测。 */
enum CookingDevice {
    FURNACE(Blocks.FURNACE, FurnaceMenu.class, new FurnaceFuel()::duration),
    BLAST_FURNACE(Blocks.BLAST_FURNACE, BlastFurnaceMenu.class, new BlastFuel()::duration),
    SMOKER(Blocks.SMOKER, SmokerMenu.class, new SmokerFuel()::duration),
    CAMPFIRE(Blocks.CAMPFIRE, null, stack -> 0);

    final Block block;
    private final Class<? extends AbstractContainerMenu> menuType;
    private final ToIntFunction<ItemStack> fuelDuration;

    CookingDevice(Block block, Class<? extends AbstractContainerMenu> menuType,
                  ToIntFunction<ItemStack> fuelDuration) {
        this.block = block;
        this.menuType = menuType;
        this.fuelDuration = fuelDuration;
    }

    boolean matches(AbstractContainerMenu menu) {
        return menuType != null && menuType.isInstance(menu);
    }

    int burnDuration(ItemStack stack) {
        // 高炉和烟熏炉会更快烧完燃料；直接使用原生设备的计算，也保留加载器对该方法的扩展。
        return Math.max(0, fuelDuration.applyAsInt(stack));
    }

    static CookingDevice forRecipe(RecipeType<?> type) {
        if (type == RecipeType.SMELTING) return FURNACE;
        if (type == RecipeType.BLASTING) return BLAST_FURNACE;
        if (type == RecipeType.SMOKING) return SMOKER;
        if (type == RecipeType.CAMPFIRE_COOKING) return CAMPFIRE;
        return null;
    }

    // 这些只读探针借用原生设备的受保护燃料计算；不放进世界，不运行机器，也不写入任何物品。
    private static final class FurnaceFuel extends FurnaceBlockEntity {
        FurnaceFuel() { super(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        int duration(ItemStack stack) { return getBurnDuration(stack); }
    }

    private static final class BlastFuel extends BlastFurnaceBlockEntity {
        BlastFuel() { super(BlockPos.ZERO, Blocks.BLAST_FURNACE.defaultBlockState()); }
        int duration(ItemStack stack) { return getBurnDuration(stack); }
    }

    private static final class SmokerFuel extends SmokerBlockEntity {
        SmokerFuel() { super(BlockPos.ZERO, Blocks.SMOKER.defaultBlockState()); }
        int duration(ItemStack stack) { return getBurnDuration(stack); }
    }
}
