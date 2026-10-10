// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferPlan;

/**
 * 开箱取货的取货计划：下一笔从容器哪一格拿几件、要点哪几下的纯计算，不含点击本身。
 *
 * <p>像真人一样不把整箱搬空：整堆够用（要的不少于这一格装着的，含刚好够）才整堆快速移动；
 * 只要这格的一部分时，按二分堆拿到光标上，放进背包那一侧放得下的一格，多拿的一个一个退回源格。
 * 拿够就停：容器里剩下的格子与件数都不再碰。
 */
final class ContainerTakePlan {

    /** 一步点击：在哪一格上、快速移动还是普通左右键、这一下会不会把东西拿到光标上。 */
    record Click(int slotId, boolean quickMove, int button, boolean takesToCursor) {}

    /** 一笔取货：从容器哪一格拿几件，按顺序要点哪几下。 */
    record Move(int containerSlotId, int amount, List<Click> clicks) {

        Move {
            if (clicks.isEmpty()) throw new IllegalArgumentException("一笔取货至少要有点击");
            clicks = List.copyOf(clicks);
        }
    }

    private ContainerTakePlan() {
    }

    /**
     * 下一笔拿什么：容器侧从前往后找装着想要东西的第一格。背包那一侧放不下尾数（没有空格，
     * 同种东西的堆也都装不下）时不给计划——宁可不拿也不硬点整堆把整组搬走，执行层照常收场。
     *
     * @param reading 界面此刻的分侧读数
     * @param wanted  什么样的槽位算想要的东西
     * @param needed  还缺几件；不缺时没有下一笔
     */
    static Optional<Move> next(MenuContent.Reading reading, Predicate<SlotSnapshot> wanted, int needed) {
        if (needed < 1) return Optional.empty();
        List<SlotSnapshot> container = reading.containerSnapshots();
        for (int i = 0; i < container.size(); i++) {
            SlotSnapshot snapshot = container.get(i);
            if (snapshot.isEmpty() || !wanted.test(snapshot)) continue;
            int slotId = reading.containerSlotIds().get(i);
            if (snapshot.count() <= needed) {
                // 这整堆都用得上（含刚好够）：一笔快速移动整堆进背包，落在背包哪格由游戏挑。
                return Optional.of(new Move(slotId, snapshot.count(),
                        List.of(new Click(slotId, true, 0, false))));
            }
            // 只要这格的一部分：按二分堆拿到光标上，放进背包侧放得下的一格。
            int targetIndex = roomyPlayerSlot(reading.playerSnapshots(), snapshot, needed);
            if (targetIndex < 0) return Optional.empty();
            return Optional.of(new Move(slotId, needed,
                    exactClicks(slotId, reading.playerSlotIds().get(targetIndex), snapshot, needed)));
        }
        return Optional.empty();
    }

    // 尾数搬运的点击序列：与搬运计划同一个拆法——右键拿半堆、整份或一件一件放进背包那格，多拿的退回源格。
    private static List<Click> exactClicks(int sourceSlotId, int targetSlotId, SlotSnapshot source, int amount) {
        List<Click> clicks = new ArrayList<>();
        for (TransferPlan.Step step : TransferPlan.exactAmount(source.count(), amount,
                source.stack().getMaxStackSize()).steps()) {
            boolean onSource = step.where() == TransferPlan.Where.SOURCE;
            int slotId = onSource ? sourceSlotId : targetSlotId;
            switch (step.kind()) {
                case QUICK_MOVE -> clicks.add(new Click(slotId, true, 0, false));
                case TAKE_ALL -> clicks.add(new Click(slotId, false, 0, true));
                case TAKE_HALF -> clicks.add(new Click(slotId, false, 1, true));
                case GIVE_ALL -> clicks.add(new Click(slotId, false, 0, false));
                case GIVE_ONE, PUT_BACK_ONE -> clicks.add(new Click(slotId, false, 1, false));
            }
        }
        return clicks;
    }

    // 背包那一侧放得下这么多件的一格：先并进同种东西还没满的堆，再占空格；都没有给 -1。
    private static int roomyPlayerSlot(List<SlotSnapshot> player, SlotSnapshot item, int amount) {
        int max = item.stack().getMaxStackSize();
        int empty = -1;
        for (int i = 0; i < player.size(); i++) {
            SlotSnapshot slot = player.get(i);
            if (slot.isEmpty()) {
                if (empty < 0) empty = i;
            } else if (slot.sameIdentity(item) && slot.count() + amount <= max) {
                return i;
            }
        }
        return empty;
    }
}
