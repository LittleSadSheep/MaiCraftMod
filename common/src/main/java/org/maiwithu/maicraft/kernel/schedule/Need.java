// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.schedule;

import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 身体需求（反射）：坠落、溺水、着火、被埋、被攻击、饥饿、夜晚……（docs/design/03 的 M6）。
 *
 * <p>需求每刻按身体处境给出自己有多急；需要处理时，由它创建这一次的处理差事（一个普通的执行器），
 * 交给仲裁决定能否打断当前任务。差事做完就结清，没做成也不能把主任务判失败。
 */
public interface Need {

    /** 需求的名字，用于日志和 Attention 事件，例如"换气"。 */
    String name();

    /** 此刻有多急；现在不需要处理时返回 null。 */
    Urgency urgency(TickContext context);

    /** 需要处理时，创建这一次的处理差事。 */
    Task respond(TickContext context);
}
