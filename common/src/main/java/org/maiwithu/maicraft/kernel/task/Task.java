// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import java.util.Optional;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 任务：逐刻控制角色做一件事的对象，每次运行新建一个。角色每刻只听一个任务的，被选中的任务才会被推进一刻。
 *
 * <p>生命周期：{@code start}（一次性准备）→ 若干次 {@code tick}（中间可能被生存需求打断而 {@code pause}，
 * 打断结束后直接接着 tick，不会再 start）→ {@code close}（无论怎样结束都会调用且只调用一次）。
 *
 * <p>新写的任务一律继承 {@link PhasedTask}，不要直接实现本接口。
 */
public interface Task {

    /** 开始前的一次性准备；被打断后恢复不会再调用。 */
    default void start(TickContext context) {}

    /** 推进一刻。 */
    TickResult tick(TickContext context);

    /** 被生存需求打断：松开按键、停下进行中的动作，保留进度。 */
    void pause();

    /**
     * 停下之前先到安全的地方：飞着的就近落地、车上的刹停、电梯里的等停靠。暂停、取消、不急的打断碰到此刻停下不安全的任务时，
     * 内核先这样叫它，接着照常推进，等它收尾做完（{@link #stoppedSafely}）再真的停。
     * 返回真表示开始收尾了；返回假表示现在就能停，不用收尾。默认现在就能停。之后再 {@link #pause} 就是不用收尾了、照原样接着做。
     */
    default boolean stopSafely() {
        return false;
    }

    /** 收尾做完了：停在安全的地方等着，不再往前做；没在收尾为真。 */
    default boolean stoppedSafely() {
        return true;
    }

    /**
     * 被临时任务（自卫撤离、跑回去捡东西）带离原地后，能不能从新地点直接接着做。
     * 去某地、跑图这类本来就在挪地方的任务能；挖矿、施工这类守着一个岗位的不能，回不去就停下等 LLM 定。默认不能。
     */
    default boolean resumesAnywhere() {
        return false;
    }

    /**
     * 结束：正常走到结局、被替换、被取消或角色没了时都会调用一次。
     * 负责收尾（交还本刻的交互机会、关闭自己打开的容器界面、松开按键），并返回最终结果。
     */
    TaskResult close(CloseReason reason);

    /** 此刻能不能被打断；默认是"正常干活"。 */
    default Interruptibility interruptibility(TickContext context) {
        return Interruptibility.WORKING;
    }

    /** 给调试面板和日志的一句话：此刻在做什么。 */
    String describe();

    /**
     * 给调试面板的只读进展：此刻真正在干活的那一层任务在哪个阶段、多久没进展。
     * 运行子任务的任务转给正在跑的那一层；没有可说的进展（还没开始、已结束、在等回答）时为空。
     */
    default Optional<TaskProgress> currentProgress() {
        return Optional.empty();
    }
}
