package org.maiwithu.maicraft.core.task.build;

import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.pathing.execute.PathExecutor;
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
 * 施工的背包口径,只此一处:存量只算 36 格主背包
 * ({@link PlayerInv#BUILDABLE_SLOTS}),预检与逐格闸门同源;实际扣料只由
 * 原生第一人称放置及其同步背包事实完成。
 * 此前预检用 41 格(含盔甲与副手)、闸门用 36 格:一整叠木板放在副手时,
 * 预检数得到、开工放行,而每一格都判缺料——口径分叉就是这类账目事故。
 *
 * <p>三档匹配并存且不可互换:按物品类型({@link #hasItems})、按料单口径
 * ({@link #countMatching}——料单自己说要不要组件全等)、按组件全等
 * ({@link #strictCount}——少比一个组件就等于拿白剑换走玩家那把锋利五)。
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
            BlockPos feet = PathExecutor.playerFeet(player);
            BlockHitResult hit = new BlockHitResult(player.position(), Direction.UP, feet, false);
            BlockState state = blockItem.getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(
                    player.level(), player, InteractionHand.MAIN_HAND, stack, hit) {}));
            if (state != null) {
                states.add(state);
            }
        } catch (RuntimeException e) {
            states.add(blockItem.getBlock().defaultBlockState());
        }
    }
}
