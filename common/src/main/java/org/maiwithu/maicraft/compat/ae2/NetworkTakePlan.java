// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/**
 * 从终端里怎么取：只拿需要的量，又不在一件一件上耗太久。纯函数，不读世界也不动手。
 *
 * <p>还缺一整组以上，就 Shift 点网络里那一条，一下一组直接进背包；不足一组，就一件一件取到光标上，
 * 凑够了再放进背包的一格。Shift 点一下就是一整组，不足一组时用它会多拿；一件一件取虽然慢，
 * 但不会把用不着的东西塞进背包。光标上的东西只在能整个放进某一格时才继续往上叠，免得关界面时放不下掉在地上。
 */
public final class NetworkTakePlan {

    private NetworkTakePlan() {}

    /** 下一步做什么。 */
    public sealed interface Move {
    }

    /** Shift 点这一条：最多一组直接进背包。 */
    public record TakeStack(long serial) implements Move {
    }

    /** 取一件到光标上。 */
    public record TakeOne(long serial) implements Move {
    }

    /** 把光标上的东西放进角色背包那一侧的这一格（界面里的槽位号）。 */
    public record PutDown(int slotId) implements Move {
    }

    /** 背包放不下了：网络里还有、也还缺，但没有地方装。 */
    public record NoRoom() implements Move {
    }

    /** 这一条取完了：够数了，或网络里这种东西没了。 */
    public record Done() implements Move {
    }

    /**
     * 网络存货里算想要的那几条，按先取哪条排：和物品默认样子一样的（没耐久损耗、没附魔、没改名）先取，
     * 再按存量从多到少；存量为零的不列。
     *
     * @param wanted 一种东西（看样子）算不算想要的
     */
    public static List<Ae2TerminalMenu.StockEntry> matching(List<Ae2TerminalMenu.StockEntry> stock,
            Predicate<ItemStack> wanted) {
        return stock.stream()
                .filter(entry -> entry.stored() > 0 && wanted.test(entry.sample()))
                .sorted(Comparator.comparing((Ae2TerminalMenu.StockEntry entry) -> !plain(entry.sample()))
                        .thenComparing(Comparator.comparingLong(Ae2TerminalMenu.StockEntry::stored).reversed()))
                .toList();
    }

    /**
     * 对网络里的这一条，下一步做什么。
     *
     * @param remaining       还要多少件进背包（光标上的不算已经进了背包）
     * @param cursor          此刻光标上的东西；只会是这一条的东西或空
     * @param entry           网络里这一条此刻的样子
     * @param playerSlotIds   角色背包那一侧的槽位号
     * @param playerSnapshots 与槽位号对齐的此刻内容
     */
    public static Move next(int remaining, SlotSnapshot cursor, Ae2TerminalMenu.StockEntry entry,
            List<Integer> playerSlotIds, List<SlotSnapshot> playerSnapshots) {
        int stackSize = entry.sample().getMaxStackSize();
        int onCursor = cursor.isEmpty() ? 0 : cursor.count();
        if (onCursor > 0) {
            // 光标上已经有：够数、满一组、网络里没了、或再叠一件就没有一格放得下，都先放下。
            boolean enough = onCursor >= remaining || onCursor >= stackSize || entry.stored() <= 0;
            boolean roomForOneMore = slotFor(entry.sample(), onCursor + 1, playerSlotIds, playerSnapshots).isPresent();
            if (enough || !roomForOneMore) {
                return slotFor(entry.sample(), onCursor, playerSlotIds, playerSnapshots)
                        .<Move>map(PutDown::new).orElseGet(NoRoom::new);
            }
            return new TakeOne(entry.serial());
        }
        if (remaining <= 0 || entry.stored() <= 0) {
            return new Done();
        }
        if (remaining >= stackSize) {
            // 整组：Shift 点进哪一格由游戏挑，能叠进没满的同种堆也行，所以只要背包里还有一处能放下一件就点。
            return slotFor(entry.sample(), 1, playerSlotIds, playerSnapshots).isPresent()
                    ? new TakeStack(entry.serial()) : new NoRoom();
        }
        return slotFor(entry.sample(), 1, playerSlotIds, playerSnapshots).isPresent()
                ? new TakeOne(entry.serial()) : new NoRoom();
    }

    /**
     * 角色背包那一侧能一次放下 count 件这种东西的一格：先找已经放着同一种、还装得下的，再找空格。
     * 两种都没有就给空。
     */
    static Optional<Integer> slotFor(ItemStack sample, int count, List<Integer> playerSlotIds,
            List<SlotSnapshot> playerSnapshots) {
        SlotSnapshot kind = SlotSnapshot.of(sample.copyWithCount(1));
        Integer empty = null;
        for (int i = 0; i < playerSlotIds.size(); i++) {
            SlotSnapshot slot = playerSnapshots.get(i);
            if (slot.isEmpty()) {
                if (empty == null) empty = playerSlotIds.get(i);
                continue;
            }
            if (slot.sameIdentity(kind) && slot.count() + count <= slot.stack().getMaxStackSize()) {
                return Optional.of(playerSlotIds.get(i));
            }
        }
        return Optional.ofNullable(empty);
    }

    // 和这种物品刚做出来时一个样：组件与默认的一致。
    private static boolean plain(ItemStack sample) {
        return ItemStack.isSameItemSameComponents(sample, new ItemStack(sample.getItem()));
    }
}
