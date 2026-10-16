// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import java.util.Optional;

/**
 * 角色此刻的一行自身摘要：在哪（含维度）、血、饱食、在水里时的氧气、主手拿着什么、开着什么界面。
 * 目标结束时附在结束事件上，LLM 接着决定下一步时不用先再看一次自己；背包不在这一行里，要看用 observe(self)。
 *
 * <p>实现由启动一侧接在当前世界的现场上；不在世界里、还没看清自己时给空，事件照发、只是不附。
 */
public interface ReadsSelfBrief {

    /** 此刻的一行摘要；读不到时为空。 */
    Optional<String> current();

    /** 没接上时的空实现：结束事件不附摘要。 */
    ReadsSelfBrief NONE = Optional::empty;
}
