// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 界面会话：一次容器界面使用的所有权与关闭纪律。
 *
 * <p>正常玩家打开箱子，用完随手关上：谁打开谁负责关闭；只有明确移交给父任务（例如还接着用这台机器）
 * 界面才保留下来；任务结束时由内核的收尾保证界面必定关闭，调用的就是这里的关闭。
 * 打开的动作本身由站位与交互（M2/M3）完成，会话从"界面已经能打开"接手：认领、用、关。
 *
 * <p>两条动手纪律：光标为空才动手——接手时光标上还有物品就拒绝认领；
 * 界面对象与编号绑定——通道核对的是认领时的那份菜单，复用同一编号的另一只箱不算。
 *
 * <p>收尾（取消、失败、任务结束都走这里）：鼠标上拿着自己拿起的物品，先放回源格再关；
 * 不是自己拿的交给菜单入口关界面那一步：先放进背包里装得下的一格再关，装不下才由原版的关闭流程还；
 * 失败先保留最早的原因。
 */
public final class MenuSession {

    /** 关一份界面最多等多少刻；到点还关不上就承认卡住，不无限等。C 玩家常识，来源：实机关页大约几刻、留出网络余量。 */
    static final int CLOSE_TIMEOUT_TICKS = 100;

    /** 认领结果：拿到了会话，或者给一个拒绝的问题。 */
    public sealed interface Claim {
        record Owned(MenuSession session) implements Claim {
        }

        record Refused(Problem problem) implements Claim {
        }
    }

    /** 收尾一刻的进展：还在收、关上了、失败了。 */
    public sealed interface Closing {
        /** 还在收尾，下一刻再推进。 */
        enum InProgress implements Closing {
            IN_PROGRESS
        }

        /** 界面已经关上（或早就被别人关掉了），收尾完成。 */
        record Closed() implements Closing {
        }

        /** 到了期限还没关上：带着问题结束；问题保留的是最早的原因。 */
        record Failed(Problem problem) implements Closing {
        }
    }

    private boolean handedOver;
    private boolean closing;
    private boolean closeRequested;
    private long closeDeadline = Long.MIN_VALUE;
    /** 自己从哪个槽位拿起了光标上的物品；收尾时放回这里。负数表示光标上的东西不是自己拿的。 */
    private int cursorSourceSlot = -1;
    /** 最早的问题；收尾路上的岔子不顶掉它。 */
    private Problem earliestFailure;

    private MenuSession() {}

    /**
     * 认领当前打开的界面：界面必须真的开着，光标必须为空。
     * 光标上还有物品说明上一个使用者没结清，不能接手，也不替它收拾。
     */
    public static Claim claim(MenuChannel channel) {
        // 界面对象与编号的核对在通道里做：复用同一编号的另一只箱不算还开着。
        if (!channel.stillOpen()) {
            return new Claim.Refused(Problem.of(Problem.Kind.TARGET_GONE, "界面没有真的打开或已经不在了"));
        }
        if (channel.cursorCarrying()) {
            return new Claim.Refused(Problem.of(Problem.Kind.INTERNAL_ERROR,
                    "接手界面时光标上还拿着物品，上一个使用者没有结清"));
        }
        return new Claim.Owned(new MenuSession());
    }

    /** 下一笔点击前核对：还是认领的那份界面、光标为空。 */
    public boolean readyForNextClick(MenuChannel channel) {
        return channel.stillOpen() && !channel.cursorCarrying();
    }

    /** 搬运执行拿起物品时登记来源格：只有自己拿的，收尾时才放回。 */
    public void noteCursorTakenFrom(int slot) {
        cursorSourceSlot = slot;
    }

    /** 明确移交：界面留给接手的任务继续用，本会话不再负责关闭。 */
    public void handOver() {
        handedOver = true;
    }

    /** 是否已移交；移交后界面由接手者负责。 */
    public boolean handedOver() {
        return handedOver;
    }

    /**
     * 收尾并关闭，每刻推进一小步：先放回自己拿起的物品，再请游戏关闭，等界面真的消失。
     * 中途的岔子记进最早的问题，收尾继续走完——关不掉的界面比少算一笔搬运更糟。
     *
     * @param channel 界面通道
     * @param tick    当前客户端刻，用来算关闭的期限
     */
    public Closing closeNow(MenuChannel channel, long tick) {
        if (handedOver) {
            return new Closing.Failed(Problem.of(Problem.Kind.INTERNAL_ERROR,
                    "界面已经移交给接手的任务，本会话不再关闭它"));
        }
        // 界面先一步没了（玩家手动关掉、被别的任务顶掉）：关闭的目的已经达到。
        if (!channel.stillOpen()) {
            closing = false;
            return new Closing.Closed();
        }
        if (!closing) {
            closing = true;
            closeDeadline = tick + CLOSE_TIMEOUT_TICKS;
        }
        // 光标上还有自己拿起的物品：先放回源格；一次只点一下，等同步把光标清空再往下走。
        if (channel.cursorCarrying()) {
            if (cursorSourceSlot < 0) {
                // 不是自己拿的：不回退到别处，请游戏关；关界面那一步会先把它放进背包装得下的一格。
                return requestCloseOnce(channel, tick);
            }
            if (tick >= closeDeadline) {
                return failed(Problem.of(Problem.Kind.STUCK, "光标上的物品放不回源格，界面也关不上"));
            }
            channel.click(cursorSourceSlot, 0);
            return Closing.InProgress.IN_PROGRESS;
        }
        return requestCloseOnce(channel, tick);
    }

    // 请游戏关闭一次就够，之后每刻只看界面真的消失没有；反复请求只会发出重复的关闭。
    private Closing requestCloseOnce(MenuChannel channel, long tick) {
        if (!closeRequested) {
            closeRequested = true;
            channel.requestClose();
        }
        if (tick >= closeDeadline) {
            return failed(Problem.of(Problem.Kind.STUCK, "界面到期限还没有关上"));
        }
        return Closing.InProgress.IN_PROGRESS;
    }

    // 期限到了的失败：把最早的问题带出去。
    private Closing failed(Problem problem) {
        keepEarliest(problem);
        return new Closing.Failed(earliestFailure);
    }

    /** 记一个问题：最早的原因优先，后来的不顶掉。 */
    public void keepEarliest(Problem problem) {
        if (earliestFailure == null) earliestFailure = problem;
    }

    /** 最早的问题；没有时为 null。 */
    public Problem earliestFailure() {
        return earliestFailure;
    }
}
