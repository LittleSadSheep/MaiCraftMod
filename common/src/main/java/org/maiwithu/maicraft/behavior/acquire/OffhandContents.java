// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import org.maiwithu.maicraft.game.player.BackpackStack;

/**
 * 副手内容的只读接缝：角色副手上拿着什么。副手不占背包主格，背包视图里看不到它，
 * 身上清点时单独问它；空着的时候如实回答空。
 */
public interface OffhandContents {

    /** 副手上的那一格；空手时为 empty。 */
    Optional<BackpackStack> heldInOffhand();
}
