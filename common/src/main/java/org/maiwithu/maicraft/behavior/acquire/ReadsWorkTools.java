// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

/**
 * 读"成批干活前该先备哪把效率工具"的接缝：方块的对口工具与等级、身上的工具够不够快够不够耐久、开局还缺几件料，
 * 由游戏接口层按真实的物品与方块读，规则见 {@link WorkTools}。
 */
public interface ReadsWorkTools {

    /** 什么都不备：接缝没接上时的占位，采掘照旧只在挖不了时备工具。 */
    ReadsWorkTools NONE = new ReadsWorkTools() {
        @Override public Optional<String> toolFor(String blockTypeId, int work) {
            return Optional.empty();
        }

        @Override public int bootstrap(String blockTypeId) {
            return 0;
        }
    };

    /** 成批干这种方块的活、还要几件时，值得先备的那把工具的物品 ID；不用备给空。 */
    Optional<String> toolFor(String blockTypeId, int work);

    /** 开局为了做第一把工具，还得先徒手弄几件这种方块；够了给 0。 */
    int bootstrap(String blockTypeId);
}
