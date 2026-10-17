// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.createmod.catnip.platform.CatnipServices;
import com.simibubi.create.content.redstone.link.controller.LinkedControllerInputPacket;
import com.simibubi.create.content.contraptions.glue.SuperGlueSelectionPacket;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.simibubi.create.content.redstone.link.controller.LinkedControllerItem;
import net.neoforged.neoforge.items.ItemStackHandler;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import com.simibubi.create.content.equipment.toolbox.ToolboxBlockEntity;
import com.simibubi.create.content.equipment.toolbox.ToolboxEquipPacket;
import com.simibubi.create.content.equipment.toolbox.ToolboxHandler;
import com.simibubi.create.content.equipment.toolbox.ToolboxInventory;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackPlacement;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.compat.create.CreateTools;

/**
 * 读机械动力手持工具的读写端：能不能用扳手拆看方块是不是 Create 的可调方块；
 * 砂纸配方从客户端的配方表里按 Create 的砂纸打磨类型取，原料取配方唯一那一格能放的全部物品；
 * 铺轨读轨道物品记着的起点与两头轨道方块实体里的连接；工具箱读 Create 自己记着的附近工具箱，挂格发 ToolboxEquipPacket。
 * 只翻译，不判断。
 */
public final class CreateToolReads implements CreateTools {

    @Override public boolean wrenchable(BlockState state) {
        return state.getBlock() instanceof IWrenchable;
    }

    // 扳手点一面：照 Create 自己的转法算（方向机器绕被点那一面的轴转九十度，点朝向那一面切换转轴）。
    @Override public Optional<BlockState> wrenched(BlockState state, Direction face) {
        return state.getBlock() instanceof IWrenchable wrenchable
                ? Optional.of(wrenchable.getRotatedBlockState(state, face)) : Optional.empty();
    }

    @Override public List<Polishing> polishing(ClientLevel level) {
        List<Polishing> recipes = new ArrayList<>();
        for (RecipeHolder<? extends Recipe<?>> holder
                : level.getRecipeManager().getAllRecipesFor(AllRecipeTypes.SANDPAPER_POLISHING.getType())) {
            Recipe<?> recipe = holder.value();
            if (recipe.getIngredients().isEmpty()) continue;
            Ingredient ingredient = recipe.getIngredients().getFirst();
            Set<String> inputs = new LinkedHashSet<>();
            for (ItemStack option : ingredient.getItems()) {
                inputs.add(BuiltInRegistries.ITEM.getKey(option.getItem()).toString());
            }
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (inputs.isEmpty() || result.isEmpty()) continue;
            recipes.add(new Polishing(List.copyOf(inputs), BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),
                    result.getCount()));
        }
        return recipes;
    }

    @Override public void glue(BlockPos from, BlockPos to) {
        CatnipServices.NETWORK.sendToServer(new SuperGlueSelectionPacket(from, to));
    }

    // 那一片有没有胶：附近的胶里有一块的范围正好是这一片（Create 用 span 算两角盖住的方块）。
    @Override public boolean glued(ClientLevel level, BlockPos from, BlockPos to) {
        AABB span = SuperGlueEntity.span(from, to);
        return level.getEntitiesOfClass(SuperGlueEntity.class, span.inflate(1)).stream()
                .anyMatch(glue -> glue.getBoundingBox().equals(span));
    }

    @Override public boolean track(BlockState state) {
        return state.getBlock() instanceof TrackBlock;
    }

    // 轨道物品右键过一格轨道后，起点记在它的数据组件 TRACK_CONNECTING_FROM 里。
    @Override public Optional<BlockPos> trackStart(ItemStack held) {
        TrackPlacement.ConnectingFrom from = held.get(AllDataComponents.TRACK_CONNECTING_FROM);
        return from == null ? Optional.empty() : Optional.of(from.pos());
    }

    // 铺成的一段两头各记一份：两格轨道的方块实体都以对方的位置为键记着这一段。
    @Override public boolean trackConnected(ClientLevel level, BlockPos from, BlockPos to) {
        return level.getBlockEntity(from) instanceof TrackBlockEntity start && start.getConnections().containsKey(to)
                && level.getBlockEntity(to) instanceof TrackBlockEntity end && end.getConnections().containsKey(from);
    }

    // 够得着的工具箱：Create 自己记着每个已加载的工具箱，按范围与远近挑（最多八个）；工具箱的库存经物品能力读，
    // 一格四叠装的都是同一种东西，按装着的数件数（空着的格拿不出东西，不列）。
    @Override public List<Toolbox> toolboxes(ClientLevel level, Player player) {
        List<Toolbox> boxes = new ArrayList<>();
        for (ToolboxBlockEntity box : ToolboxHandler.getNearest(level, player, 8)) {
            IItemHandler inventory = level.getCapability(Capabilities.ItemHandler.BLOCK, box.getBlockPos(), null);
            if (inventory == null) continue;
            List<Compartment> compartments = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                ItemStack sample = ItemStack.EMPTY;
                int count = 0;
                for (int i = 0; i < ToolboxInventory.STACKS_PER_COMPARTMENT; i++) {
                    int slot = index * ToolboxInventory.STACKS_PER_COMPARTMENT + i;
                    if (slot >= inventory.getSlots()) break;
                    ItemStack stack = inventory.getStackInSlot(slot);
                    if (stack.isEmpty()) continue;
                    if (sample.isEmpty()) sample = stack;
                    count += stack.getCount();
                }
                if (count == 0) continue;
                compartments.add(new Compartment(index, BuiltInRegistries.ITEM.getKey(sample.getItem()).toString(), count,
                        sample.getMaxStackSize()));
            }
            boxes.add(new Toolbox(box.getBlockPos(), compartments));
        }
        return boxes;
    }

    @Override public int toolboxRange() {
        try {
            return AllConfigs.server().equipment.toolboxRange.get();
        } catch (RuntimeException unreadable) {
            return 10;
        }
    }

    @Override public void equipFromToolbox(BlockPos box, int compartment, int hotbarSlot) {
        CatnipServices.NETWORK.sendToServer(new ToolboxEquipPacket(box, compartment, hotbarSlot));
    }

    // 弹药类型是数据包注册表、带网络编码，客户端的注册表里也有。
    @Override public Optional<PotatoAmmo> potatoAmmo(Player player, ItemStack ammo) {
        if (ammo.isEmpty()) return Optional.empty();
        return PotatoCannonProjectileType.getTypeForItem(player.level().registryAccess(), ammo.getItem())
                .map(reference -> {
                    PotatoCannonProjectileType type = reference.value();
                    return new PotatoAmmo(type.reloadTicks(), type.split(), type.drag(), type.velocityMultiplier(),
                            type.gravityMultiplier());
                });
    }

    @Override public void remoteButtons(List<Integer> buttons, boolean press) {
        CatnipServices.NETWORK.sendToServer(new LinkedControllerInputPacket(buttons, press));
    }

    // 遥控器的十二个频率格：第 i 个键是第 2i、2i+1 格，和机械动力按键时算频率的同一份。
    @Override public List<List<ItemStack>> remoteFrequencies(ItemStack controller) {
        if (!(controller.getItem() instanceof LinkedControllerItem)) return List.of();
        ItemStackHandler items = LinkedControllerItem.getFrequencyItems(controller);
        List<List<ItemStack>> keys = new ArrayList<>();
        for (int key = 0; key < items.getSlots() / 2; key++) {
            keys.add(List.of(items.getStackInSlot(key * 2).copy(), items.getStackInSlot(key * 2 + 1).copy()));
        }
        return keys;
    }

    @Override public int linkRange() {
        try {
            return AllConfigs.server().logistics.linkRange.get();
        } catch (RuntimeException unreadable) {
            return 256;
        }
    }
}
