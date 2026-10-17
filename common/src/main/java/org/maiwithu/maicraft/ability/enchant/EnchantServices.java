// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.Objects;
import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.travel.SceneSeenTargets;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 附魔任务组合的玩家行为：缺料走受限的拿到物品引擎（身上与记得的容器，不为一次附魔下矿）、
 * 腾一格接成品、走到并点开台子、读附魔事实、解析观察编号与记得的地点、分刻扫描找台子。
 * 服务从构造函数传入；测试换成各自的替身。
 */
record EnchantServices(ItemNeeds obtain, InventorySpace space,
                       BiFunction<BlockPos, Permissions, MenuOpening> openings,
                       EnchantMenuFacts reads, SceneSeenTargets seen,
                       ReadsRememberedPlaces places, BlockScanService scans,
                       ReadsItemTags tags) {

    EnchantServices {
        Objects.requireNonNull(obtain, "obtain");
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(openings, "openings");
        Objects.requireNonNull(reads, "reads");
        Objects.requireNonNull(seen, "seen");
        Objects.requireNonNull(places, "places");
        Objects.requireNonNull(scans, "scans");
        Objects.requireNonNull(tags, "tags");
    }
}
