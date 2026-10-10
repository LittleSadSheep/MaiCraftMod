// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.result.Change;

/**
 * 任务给动作的记账口：动作做的事直接记进发起它的任务的结果，不靠任务事后去问。
 *
 * <p>一件事里套着的小动作也会改世界——拿东西途中腾地方丢了一组圆石、施工途中挖掉一格——
 * 这些都要出现在这次任务的结果里。任务把自己的记账口交给动作，动作确认一笔记一笔；
 * 分阶段任务用 {@link PhasedTask#records()} 拿到自己的那一份。
 */
public interface TaskRecords {

    /** 一条已确认发生的变化，进结果的 changes。 */
    void change(Change change);

    /** 一条已提交、但没能确认结果的交互，进结果的 unconfirmed；这类交互不能盲目重做。 */
    void unconfirmed(Change change);

    /** 一次试过的办法及结果，进结果的 attempts。 */
    void attempt(String tried, String whatHappened);
}
