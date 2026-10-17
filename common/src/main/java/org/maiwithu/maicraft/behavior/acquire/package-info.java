// SPDX-License-Identifier: GPL-3.0-only
/**
 * 拿到物品：问遍所有物品来源（背包、箱子、合成、烧炼、采集、交易……），选总代价最低的组合，边做边重新清点。
 *
 * <p>所有内部用途（施工备料、燃料、工具、垫脚方块、火把、食物……）都走这一个入口。
 * 物品来源通过 {@code behavior.acquire.spi} 由行为层与联动模组共同提供，启动时明确登记，不用 ServiceLoader。
 */
package org.maiwithu.maicraft.behavior.acquire;
