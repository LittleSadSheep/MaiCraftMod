// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/**
 * 身体需求有多急（docs/design/03 的 M6）。需求每刻按身体处境给出自己的紧急程度。
 *
 * <p>例：饿了但还能跑是舒适需求；饥饿值低到不能疾跑、不回血是有害需求；饿到掉血、快淹死、正往虚空掉是致命需求。
 */
public enum Urgency {
    /** 舒适：找个空档处理就好，例如饿了吃一口、天黑去睡。 */
    COMFORT,
    /** 有害：不处理会持续受损，例如被怪打、身上着火。 */
    HARMFUL,
    /** 致命：现在不处理就会死，例如坠向虚空、氧气见底、被埋在方块里。 */
    LETHAL
}
