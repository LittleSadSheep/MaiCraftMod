// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.List;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import org.maiwithu.maicraft.game.interaction.ChatDraftScreen;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 逐字打字：把一句话像真人一样打进真实聊天框——开框，草稿一个字一个字长出来，
 * 整句打完停一下才算打完；发送不在这里，发话任务在打完之后走聊天通道提交。
 *
 * <p>相邻两个完整可见字符隔 2 刻（约 0.1 秒），组合起来的表情按一个字，整句打完停 5 刻。
 * 只往自己开出的框里打：框被别的界面占着就等它让出来（最多 60 刻），等不到按游戏的真实拒绝收场；
 * 被生存需求打断时关掉框、保住已打的字数，回来接着从原字数打，不重头打。
 */
public final class TypeChatDraft implements Action {

    /** 相邻两个完整可见字符之间隔多少刻：2 刻约 0.1 秒。 */
    static final long TICKS_BETWEEN_CHARACTERS = 2;

    /** 整句打完后再停多少刻才交回任务：5 刻约 0.25 秒，让观众看清整句草稿。 */
    static final long HOLD_AFTER_LAST_TICKS = 5;

    /** 聊天框被别的界面占着时最多等多少刻：60 刻约 3 秒，等不到按真实拒绝收场。 */
    static final long BOX_WAIT_LIMIT_TICKS = 60;

    // 完整可见字符按扩展字素簇切：中文、组合表情、带音符的字母都是一个字，不会被拆成几半。
    private static final Pattern VISIBLE_CHARACTER = Pattern.compile("\\X");

    /** 打字的段：等框空出来 → 一个字一个字打 → 整句打完停一下。 */
    private enum Stage { OPEN_BOX, TYPING, HOLD }

    private final ChatDraftScreen drafts;
    private final String message;
    /** 各完整可见字符在原话里的结束边界：草稿按它切，组合表情不会被拆开。 */
    private final List<Integer> characterEnds;
    private Stage stage = Stage.OPEN_BOX;
    /** 已经打进框里的可见字符数。 */
    private int typed;
    /** 最早能打下一条（或收尾）的刻。 */
    private long nextAt;
    /** 框被占着已经等了多少刻。 */
    private long waited;

    /**
     * @param drafts  聊天草稿的接缝
     * @param message 要打的整句话；空话进不来这里，发话的参数校验会拦下
     */
    public TypeChatDraft(ChatDraftScreen drafts, String message) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.message = Objects.requireNonNull(message, "message");
        if (message.isEmpty()) throw new IllegalArgumentException("要打的话不能为空");
        this.characterEnds = VISIBLE_CHARACTER.matcher(message).results()
                .map(MatchResult::end).toList();
    }

    @Override
    public ActionStatus tick(TickContext context) {
        long now = context.gameTick();
        return switch (stage) {
            case OPEN_BOX -> tickOpenBox(now);
            case TYPING -> tickTyping(now);
            case HOLD -> now < nextAt ? ActionStatus.running() : ActionStatus.done();
        };
    }

    // 框空着就把当前草稿放进去；被别的界面占着等它让出来，等不到按游戏的真实拒绝收场。
    private ActionStatus tickOpenBox(long now) {
        if (drafts.show(shown())) {
            stage = Stage.TYPING;
            nextAt = now + TICKS_BETWEEN_CHARACTERS;
            return ActionStatus.progressed();
        }
        waited++;
        if (waited >= BOX_WAIT_LIMIT_TICKS) {
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "聊天框被别的界面占着，草稿打不进去",
                    "等占着界面的东西关掉或收场之后再试"));
        }
        return ActionStatus.running();
    }

    // 到点才打一个字；就算前面卡了很久也不会一口气补打。框被换掉了就等它让出来，从原字数接着打。
    private ActionStatus tickTyping(long now) {
        if (now < nextAt) {
            return ActionStatus.running();
        }
        if (!drafts.showing()) {
            stage = Stage.OPEN_BOX;
            waited = 0;
            return ActionStatus.running();
        }
        typed++;
        drafts.show(shown());
        if (typed == characterEnds.size()) {
            stage = Stage.HOLD;
            nextAt = now + HOLD_AFTER_LAST_TICKS;
        } else {
            nextAt = now + TICKS_BETWEEN_CHARACTERS;
        }
        return ActionStatus.progressed();
    }

    @Override
    public void pause() {
        // 让出框给生存需求的临时任务用：移动键进不来开着字的聊天框。已打的字数留在这份动作里，回来接着打。
        drafts.close();
    }

    @Override
    public void close() {
        // 阶段结束或任务收尾时同样让出框；提交之后框也该关上，和玩家按回车后一样。
        drafts.close();
    }

    /** 框里此刻该有的字：前若干个完整可见字符；一个都还没打时是空草稿（只为把框开出来）。 */
    private String shown() {
        return typed == 0 ? "" : message.substring(0, characterEnds.get(typed - 1));
    }

    @Override
    public String describe() {
        int total = characterEnds.size();
        return switch (stage) {
            case OPEN_BOX -> "等聊天框空出来（已等 " + waited + " 刻）";
            case TYPING -> "逐字打字「" + shown() + "」（" + typed + "/" + total + " 字）";
            case HOLD -> "整句打完「" + message + "」，停一下再发";
        };
    }
}
