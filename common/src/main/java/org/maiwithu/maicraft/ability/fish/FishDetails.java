// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 钓鱼的结果细节：确认拿到手里的收获次数与抛出的竿数。
 *
 * @param caught 确认进了背包的收获次数（垃圾、宝藏也算一次）
 * @param casts  一共抛了几竿
 */
record FishDetails(int caught, int casts) implements ResultDetails {
}
