// SPDX-License-Identifier: GPL-3.0-only
/**
 * 拿东西（maicraft:obtain）：让背包里某样东西再多几件。身上、容器、合成、烧炼、采集、采掘
 * 各来源问一遍、挑最省事的路；来源的问价与执行都在玩家行为层的拿到物品引擎里，这里只做
 * 参数校验、把请求交给引擎、把结果（含实际拿到的途径）结算清楚。
 *
 * <p>本包只有 {@code ObtainAbility} 对外公开；由启动时创建并登记进能力清单。
 */
package org.maiwithu.maicraft.ability.obtain;
