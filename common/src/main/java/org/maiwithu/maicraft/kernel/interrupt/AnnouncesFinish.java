// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 插进来做完要告诉 LLM 一声的临时任务的特征接口：夜里自己去睡了一觉（时间一下跳到了天亮）这类，
 * 不说 LLM 就不知道发生过；吃一口、插一支火把这类小事不用说。
 *
 * <p>控制循环在它收尾时按它给的一句话发一条临时任务结束事件。
 */
public interface AnnouncesFinish {

    /** 收尾时要告诉 LLM 的一句话；这次不用说为 null。 */
    String finishedNote(TaskResult result);
}
