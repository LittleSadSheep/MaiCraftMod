// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 生存需求：坠落、溺水、着火、被埋、被攻击、饥饿、天黑、周围太暗……一个正常玩家干活时也会顺手处理的事。
 *
 * <p>每个生存需求每刻按角色处境给出自己有多急；需要处理时，由它创建这一次的临时任务（一个普通的任务），
 * 交给打断规则决定能不能打断手上的主任务。临时任务做完就结束，没做成也不能把主任务判失败。
 */
public interface SurvivalNeed {

    /** 需求的名字，用于日志和任务事件，例如"换气"。 */
    String name();

    /** 此刻有多急；现在不需要处理时返回 null。 */
    Urgency urgency(TickContext context);

    /**
     * 同上，但能看到此刻被推进的任务（通常是主任务；手上没有任务时为 null）。
     * 有的需求要看主任务在不在管一件事才决定让不让位，例如手头的活正在还手时被攻击不必再插进来；
     * 需求通过任务上的特征接口认这件事，内核与需求都不认识任何具体能力。默认不看主任务。
     */
    default Urgency urgency(TickContext context, Task currentTask) {
        return urgency(context);
    }

    /** 需要处理时，创建这一次的临时任务。 */
    Task createTask(TickContext context);

    /**
     * 临时任务没做成后，要不要先缓一阵再插。默认要：弄不到吃的、找不到床这类，接连重试只会空转。
     * 被攻击这类不缓：每一下新挨的打都是新的处境，缓着就是站着挨打（原先自卫没做成一次就按住
     * 5 秒到 5 分钟，施工时被打怎么也没反应）。
     */
    default boolean waitsAfterFailure() {
        return true;
    }
}
