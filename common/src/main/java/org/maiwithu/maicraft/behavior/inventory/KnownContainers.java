// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.List;

/**
 * 附近已知容器：角色记得的、离得近的箱子等容器。只回答"有哪些"，存取是容器存取接缝的事。
 *
 * <p>实现读世界记忆（读端 {@link RememberedContainers}）；接缝没接上（传 {@code Optional.empty()}）
 * 时腾地方就只走合并、随身背包和丢弃。
 */
public interface KnownContainers {

    /**
     * 记得的容器里，离角色多远以内的有哪些。
     *
     * @param blockRange 距离上限，按方块的欧氏距离算
     */
    List<KnownContainer> within(int blockRange);
}
