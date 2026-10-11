// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 收临时任务事实的任务特征接口：主任务声明它，临时任务收尾时控制循环把临时任务交代的结果递进来。
 *
 * <p>生存需求插进来的临时任务自己会交出一份结果——顺手插了一支火把、垫了一块料——但角色的身体
 * 是主任务的，这件事发生在主任务身上：临时任务收场后它自己的结果没有别人再看，
 * 变化与没能确认的交互不并进主任务的结果，调用方拿结果对背包和世界的账就对不上。
 * 主任务收下这些结果，自己收尾时把它们的事实并进最终结果。
 */
public interface KeepsTempTaskFacts {

    /** 收下一份临时任务交代的结果：它的变化、没能确认的交互与试过的办法，随主任务的结果一起交代。 */
    void keepTempTaskFacts(TaskResult result);
}
