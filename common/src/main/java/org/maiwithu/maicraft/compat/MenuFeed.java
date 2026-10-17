// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.FeedReport;
import org.maiwithu.maicraft.behavior.inventory.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.StoresInContainer;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 带界面的机器经界面投料：走过去右键点开机器的界面，把料从背包搬进去（整堆快速移动，机器自己把它放进收它的格；
 * 只放一部分时拆出这几件放进一格收它的空格），关上界面。界面认不认得、哪几格能点，看联动登记的界面布局。
 * 搬运与存东西能力同一套，每一笔按背包少了几件、界面里多了几件对上才算放进去。
 *
 * <p>做完交出实情：放进去几件；放不下（输入格满了、这样东西它不收）、两边件数对不上的写进说明。一件都没放进去时失败。
 */
public final class MenuFeed implements Action, FeedReport {

    private final String itemId;
    private final int asked;
    private final StoresInContainer storing;
    private int stored;
    private final List<String> notes = new ArrayList<>();

    /**
     * @param machine     机器叫什么，给描述与说明用，例如"富集仓"
     * @param blockTypeId 机器方块的注册 ID
     */
    public MenuFeed(PlayerServices services, String machine, BlockPos at, String blockTypeId, String itemId, int count,
            Permissions permissions) {
        Objects.requireNonNull(services, "services");
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.asked = count;
        StoresInContainer.Ledger ledger = new StoresInContainer.Ledger() {
            @Override public int left(String id) {
                return id.equals(itemId) ? asked - stored : 0;
            }

            @Override public void stored(String id, int amount) {
                stored += amount;
            }

            @Override public void unconfirmed(String fact) {
                notes.add(fact);
            }

            @Override public void attempt(String tried, String whatHappened) {
                notes.add(tried + "：" + whatHappened);
            }
        };
        // 点开机器界面和点开箱子是同一个动作：走到够得着、看得见的地方右键，认领界面并等内容同步。
        StoresInContainer.Parts parts = new StoresInContainer.Parts((cell, allowed) -> new ClientMenuOpening(cell,
                allowed, services.menuLayouts(), services.approach(), services.interactions(), services.context()),
                null);
        this.storing = new StoresInContainer(new KnownContainer(machine,
                WorldPosition.here(at.getX(), at.getY(), at.getZ()), blockTypeId), List.of(itemId), ledger,
                permissions, parts);
    }

    // 搬完确认件数：一件没放进去按失败，带上点开、放不下的经过；放进去的少于要的写一句为什么。
    @Override public ActionStatus tick(TickContext context) {
        ActionStatus status = storing.tick(context);
        if (!(status instanceof ActionStatus.Done)) return status;
        if (stored == 0) {
            String seen = notes.isEmpty() ? "" : "：" + String.join("；", notes);
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "界面里一件 " + itemId + " 都没放进去" + seen));
        }
        if (stored < asked) {
            notes.add("界面里放进去 " + stored + " 件 " + itemId + "，要的是 " + asked + " 件：收它的格满了，或身上不够");
        }
        return status;
    }

    @Override public int delivered() {
        return stored;
    }

    @Override public List<String> notes() {
        return List.copyOf(notes);
    }

    @Override public void pause() {
        storing.pause();
    }

    @Override public void close() {
        storing.close();
    }

    @Override public Interruptibility interruptibility() {
        return storing.interruptibility();
    }

    @Override public String describe() {
        return storing.describe();
    }
}
