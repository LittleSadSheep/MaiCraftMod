// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 发话任务：把一句话交给聊天输入，再等聊天栏出现证据才算发成。
 * 普通聊天等自己那条（本地回显）；游戏命令没有"自己那条"的回显，等的是提交后
 * 聊天栏冒出的命令反馈行（原版命令会打结果反馈，如 Set the time to…）。
 * 证据等不到就按"已提交但没能确认"结束，记进结果的 unconfirmed，不盲目重发也不冒充成功。
 */
public final class ChatTask extends PhasedTask<ChatTask.Phase> {

    /** 发话的阶段：先提交，再等回显。 */
    public enum Phase { SEND, CONFIRM }

    /** 提交后等回显多久：超过就按没能确认收场。 */
    private static final long CONFIRM_TICKS = 20L * 5;

    // 卡住判定比确认窗口长：等回显等不到时由本任务自己按没能确认收场，轮不到卡住判定。
    private static final long STUCK_AFTER_TICKS = CONFIRM_TICKS + 20L;

    private final String message;
    private final SendsChatMessage sender;
    private final ReadsChatEcho echo;

    /** 提交那一刻的游戏刻；算等回显等了多久用。 */
    private long sentTick;
    /** 提交那一刻的毫秒时钟；命令反馈行按提交后有没有新行判断，要用同一时钟比对。 */
    private long sentMillis;
    /** 以 / 开头的是游戏命令：没有自己的回显，完成依据换成命令反馈行。 */
    private final boolean command;

    public ChatTask(String message, SendsChatMessage sender, ReadsChatEcho echo) {
        super("说话", Phase.SEND, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.message = message;
        this.sender = sender;
        this.echo = echo;
        this.command = message.startsWith("/");
    }

    /** 发话的结果细节：发了哪句话，供 LLM 对照与直播解说。 */
    private record ChatDetails(String message) implements ResultDetails {}

    @Override
    protected Action enter(Phase phase) {
        // 提交与等回显都是一刻内的小事，不需要动作。
        return null;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case SEND -> tickSend(context);
            case CONFIRM -> tickConfirm(context);
        };
    }

    /** 把话交给聊天输入，随后进入等证据。 */
    private Next<Phase> tickSend(TickContext context) {
        sentTick = context.gameTick();
        sentMillis = System.currentTimeMillis();
        sender.send(message);
        recordProgress("已把话交给聊天输入");
        return Next.go(Phase.CONFIRM, command ? "已提交命令，等聊天栏出现命令反馈行" : "已提交，等聊天栏出现自己那条");
    }

    /** 证据到了就算发成；等不到就按已提交但没能确认结束，不盲目重发。 */
    private Next<Phase> tickConfirm(TickContext context) {
        // 命令与聊天分开确认：命令看提交后冒出的反馈行，聊天看自己那条回显。
        if (command) {
            if (echo.anyLineAfter(sentMillis)) {
                return Next.done(TaskResult.done("命令已交给游戏执行，聊天栏出现了命令反馈行"));
            }
            if (context.gameTick() - sentTick >= CONFIRM_TICKS) {
                recordUnconfirmed(new Change(Change.Kind.OTHER, message, 1,
                        "已提交命令，但没在聊天栏看到命令反馈行，是否执行没能确认"));
                return Next.done(TaskResult.done("命令已交出去，但没在聊天栏看到命令反馈行，是否执行没能确认"));
            }
            return Next.stay();
        }
        if (echo.appearsInChat(message)) {
            return Next.done(TaskResult.done("已向全体玩家说话，聊天栏出现了自己那条"));
        }
        if (context.gameTick() - sentTick >= CONFIRM_TICKS) {
            recordUnconfirmed(new Change(Change.Kind.OTHER, message, 1,
                    "已提交聊天，但没在聊天栏看到自己那条，是否发出没能确认"));
            return Next.done(TaskResult.done("话已交出去，但没在聊天栏看到自己那条，是否发出没能确认"));
        }
        return Next.stay();
    }

    @Override
    protected ResultDetails details() {
        return new ChatDetails(message);
    }

    @Override
    protected String describePhase(Phase value) {
        if (value == Phase.SEND) return "正在说话";
        return command ? "等命令反馈行" : "等聊天栏回显";
    }
}
