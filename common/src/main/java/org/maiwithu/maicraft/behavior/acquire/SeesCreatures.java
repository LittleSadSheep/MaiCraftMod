// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

/**
 * 猎物在不在、看不听得见的只读接缝：出手前的射线核对与打斗中的目标核对都用它。
 * 走远了、没了给空；还在但被挡住给假；看得见给真。
 */
public interface SeesCreatures {

    /** 这只生物此刻的样子；不在了（走远、被别的打死）给空。 */
    Optional<Boolean> visible(int entityId);
}
