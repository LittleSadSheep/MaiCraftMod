// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 用东西能力的结果细节：确认生效了几次、打开的界面里有什么、告示牌上实际写下的字、骑上了什么。
 *
 * @param appliedTimes 确认生效了几次
 * @param opened       打开过的界面：是什么、里面有什么（按物品合并）；没打开过界面为 null
 * @param signText     告示牌上实际写下的字，逐行；没写告示牌为空列表
 * @param riding       骑上的东西；没骑为 null
 */
record UseDetails(long appliedTimes, String opened, List<String> signText, String riding) implements ResultDetails {
    UseDetails {
        signText = List.copyOf(signText);
    }
}
