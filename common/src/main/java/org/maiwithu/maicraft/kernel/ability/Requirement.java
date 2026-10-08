// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import java.util.Objects;

/**
 * 能力可用的前提：需要某个联动模组已安装。能力是否可用由它的全部前提是否满足决定，
 * 不再像 v1 那样在可用性检查里为每个能力写 if。
 *
 * @param modId 需要已安装的模组 ID，例如 create
 */
public record Requirement(String modId) {
    public Requirement {
        Objects.requireNonNull(modId, "modId");
    }

    public static Requirement mod(String modId) {
        return new Requirement(modId);
    }
}
