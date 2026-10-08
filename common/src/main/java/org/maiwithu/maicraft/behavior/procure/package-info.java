// SPDX-License-Identifier: GPL-3.0-only
/**
 * M4 物品获取：向所有物料来源询价，选总代价最低的组合，边做边重新盘点。
 *
 * <p>所有内部用途（施工备料、燃料、工具、垫脚方块、火把、食物……）都走这一个入口。
 * 物料来源通过 {@code behavior.procure.spi} 由行为层与联动模块共同提供。
 */
package org.maiwithu.maicraft.behavior.procure;
