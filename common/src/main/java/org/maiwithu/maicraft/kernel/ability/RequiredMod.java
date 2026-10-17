// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import java.util.Objects;

/**
 * 能力需要的联动模组。一个能力需要的模组都装了，它才可用；不在可用性检查里为每个能力单独写 if。
 *
 * @param modId 需要已安装的模组 ID，例如 create
 */
public record RequiredMod(String modId) {
    public RequiredMod {
        Objects.requireNonNull(modId, "modId");
    }

    public static RequiredMod of(String modId) {
        return new RequiredMod(modId);
    }
}
