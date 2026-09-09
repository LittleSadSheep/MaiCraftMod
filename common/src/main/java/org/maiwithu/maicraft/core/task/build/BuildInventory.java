package org.maiwithu.maicraft.core.task.build;

import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashSet;
import java.util.Set;

/**
 * 施工读取背包的帮助类：统计材料、找要拿的槽位，也支持要求附魔／花纹等属性一致的材料。
 * 普通存量统计只算主背包三十六格；实际选物品是否可用后面二十七格，还受 allowInventory 设置影响。
 * 这里不扣材料，物品消耗由游戏里的实际放置完成。
 */
final class BuildInventory {

    private final LocalPlayer player;

    BuildInventory(LocalPlayer player) {
        this.player = player;
    }

    private int buildableLimit(Inventory inventory) {
        return Math.min(PlayerInv.BUILDABLE_SLOTS, inventory.items.size());
    }

    /** 背包里有几件满足这一笔要求的东西——口径由这笔要求自己说。 */
    int countMatching(BuildTaskRecord.CellNeed need) {
        Inventory inventory = player.getInventory();
        int limit = buildableLimit(inventory);
        int n = 0;
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && need.matches(stack)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    /**
     * 背包里有几件<b>和这一叠完全一样</b>的东西——组件也要一致。
     *
     * <p>用原版自己那个"同物品同组件"判据,不另立一套近似判据:少比一个组件,就等于
     * 拿一把白剑换走文件里那把锋利五的剑。
     */
    int strictCount(ItemStack want) {
        Inventory inventory = player.getInventory();
        int limit = buildableLimit(inventory);
        int n = 0;
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, want)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    boolean hasItem(Item item, boolean wholeInventory) {
        return hasItems(item, 1, wholeInventory);
    }

    /** 够不够 {@code count} 件——双层砖那种一格吃两件的格子要问这个。 */
    boolean hasItems(Item item, int count, boolean wholeInventory) {
        // 调用者允许且设置也允许时才动用完整背包，否则只认快捷栏。
        if (wholeInventory && NavSettings.get().allowInventory) {
            return mainInventoryCount(item) >= count;
        }
        return hotbarCount(item) >= count;
    }

    private int hotbarCount(Item item) {
        Inventory inventory = player.getInventory();
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    int findSlot(Item item, boolean wholeInventory) {
        // 先选快捷栏里同类型的第一叠，找不到才按 allowInventory 决定是否继续找主背包。
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        if (wholeInventory && NavSettings.get().allowInventory) {
            return findMainInventorySlot(item);
        }
        return -1;
    }

    int mainInventoryCount(Item item) {
        // 这个统计方法不看 allowInventory，始终统计主背包；与 findSlot 的可用范围并不总是一致。
        return PlayerInv.buildableCount(player.getInventory(), item);
    }

    private int findMainInventorySlot(Item item) {
        Inventory inventory = player.getInventory();
        int limit = buildableLimit(inventory);
        for (int i = 9; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        return -1;
    }

    /** 手上拿着的方块能放出哪些状态——寻路的建造上下文按它判"她有什么可垫"。 */
    Set<BlockState> availableStates(boolean wholeInventory) {
        // 供保留的建造成本接口估计手里能放出什么；这里只是按当前站位做预测，不代表目标位置一定放得下。
        Set<BlockState> states = new HashSet<>();
        Inventory inventory = player.getInventory();
        int limit = wholeInventory && NavSettings.get().allowInventory
                ? buildableLimit(inventory) : 9;
        for (int i = 0; i < limit; i++) {
            addAvailableState(states, inventory.getItem(i));
        }
        return states;
    }

    private void addAvailableState(Set<BlockState> states, ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        try {
            BlockPos feet = PlayerNav.playerFeet(player);
            BlockHitResult hit = new BlockHitResult(player.position(), Direction.UP, feet, false);
            BlockState state = blockItem.getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(
                    player.level(), player, InteractionHand.MAIN_HAND, stack, hit) {}));
            if (state != null) {
                states.add(state);
            }
        } catch (RuntimeException e) {
            // 模组的放置预测抛异常时，用默认状态兜底；默认状态也只是估计，不是一次成功放置的证明。
            states.add(blockItem.getBlock().defaultBlockState());
        }
    }
}
