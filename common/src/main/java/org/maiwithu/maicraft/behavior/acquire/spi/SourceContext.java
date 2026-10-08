// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 问询来源时告诉它的一次现场：角色现在在哪、这次任务有哪些许可。
 * 来源按位置估距离、按许可决定哪些来源根本不参与。
 *
 * @param characterAt 角色此刻所在的位置
 * @param permissions 这次任务的许可
 */
public record SourceContext(WorldPosition characterAt, Permissions permissions) {

    public SourceContext {
        if (characterAt == null) throw new IllegalArgumentException("问询来源必须带着角色的位置");
        if (permissions == null) throw new IllegalArgumentException("问询来源必须带着这次的许可");
    }
}
