// SPDX-License-Identifier: GPL-3.0-only
/**
 * 寻路：导航目标、目标编译，以及走到目的地需要的共用判断（地形、水深、坠落与逃跑策略）。
 * 对外的寻路入口与内嵌 Baritone 的执行接入随后续工作接入；全仓只有
 * {@code behavior.navigation.baritone} 可以使用 Baritone 的非 {@code baritone.api} 包。
 */
package org.maiwithu.maicraft.behavior.navigation;
