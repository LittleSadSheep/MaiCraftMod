// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ultimine;

import java.util.List;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * FTB Ultimine 的读写接缝：只翻译，不判断。读的是客户端这一侧——连锁键、当前选的形状、服务器发回来的选区；
 * 写的是玩家能做的两件原生操作——按下松开连锁键（FTB 自己每刻看这个键、发按键包、画轮廓和面板），
 * 切形状（发 FTB 自己的切形状包，和按住 Shift 滚轮切一样）。
 */
public interface UltimineControls {

    /** 服务器收得到 FTB 的按键包和切形状包；服务器没装 FTB Ultimine 时连锁挖不了。 */
    boolean serverListens();

    /** 连锁键此刻是不是被人亲手按着（真按键）：主播自己在用，不抢。 */
    boolean heldByHand();

    /** 按下或松开连锁键；松开时人亲手按着的照旧按着。 */
    void holdKey(boolean down);

    /** 注册了的形状，按 FTB 的顺序，例如 ftbultimine:shapeless。 */
    List<String> shapes();

    /** 客户端此刻选着第几个形状（服务器切完会同步过来）。 */
    int currentShape();

    /** 切到下一个（true）或上一个形状：发 FTB 自己的切形状包。 */
    void cycleShape(boolean next);

    /** 服务器最近一次发来的选区：按准星算的会一起挖的格。 */
    Selection selection();

    /** 选区最多画几格：选区格数到了这个数，可能被截短了。 */
    int outlineLimit();

    /** 现在挖这一格 FTB 准不准连锁（冷却、饿着、工具不对、没权限）；准时为空，不准时是 FTB 给的原因。 */
    Optional<String> refusal(LocalPlayer player, BlockPos pos);

    /** 界面被 F1 藏起来了：观众看不到连锁的轮廓和面板。 */
    boolean hudHidden();

    /**
     * 服务器发来的一次选区。
     *
     * @param revision 第几次发来的：每收到一次新的加一，用它认是不是刚到的
     * @param cells    会一起挖的格（含准星那一格）；没有选区时为空
     */
    record Selection(long revision, List<BlockPos> cells) {
        public Selection {
            cells = List.copyOf(cells);
        }
    }
}
