// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * 一处能死的地方：死法、站在哪、往哪一格去。
 *
 * <p>地形死法的入口是那一格岩浆、落差边缘外那一格或那一格仙人掌，站位是旁边能站的一格；
 * 随身造危险时站位与入口都是要点火、倒岩浆的那一格；敌对生物记它的实体编号与种类（编号会被别的实体复用，
 * 种类对不上就当它没了），站位与入口是挑中时它脚下那一格。
 *
 * @param way     死法
 * @param stand   走过去站的那一格（脚位）
 * @param entry   要进去、贴上去的那一格
 * @param mobId   敌对生物的实体编号；不是生物为 -1
 * @param mobType 敌对生物的实体类型；不是生物为 null
 */
record DeathSpot(WayToDie way, BlockPos stand, BlockPos entry, int mobId, String mobType) {

    DeathSpot {
        Objects.requireNonNull(way, "way");
        stand = stand.immutable();
        entry = entry.immutable();
    }

    /** 地形或自造的一处：没有生物。 */
    static DeathSpot at(WayToDie way, BlockPos stand, BlockPos entry) {
        return new DeathSpot(way, stand, entry, -1, null);
    }

    /** 放弃过就不再挑的记号：地形与自造按入口格，生物按实体编号。 */
    String key() {
        return way.param + ":" + (mobType == null ? entry.asLong() : mobType + "#" + mobId);
    }

    /** 给尝试记录的一句话：哪种死法、在哪。 */
    String describe() {
        return way.label + "（" + entry.getX() + ", " + entry.getY() + ", " + entry.getZ() + "）";
    }
}
