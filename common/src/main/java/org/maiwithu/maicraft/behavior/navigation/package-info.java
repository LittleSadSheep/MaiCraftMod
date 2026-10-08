// SPDX-License-Identifier: GPL-3.0-only
/**
 * 寻路：对外的入口是走到（{@code WalkTo}）——交一条编译好的目标开始走，逐刻拿进展与
 * 到达确认，能中途停下；到达判断与地形许可配套。导航目标、目标编译，以及走到目的地
 * 需要的共用判断（地形、水深、坠落与逃跑策略）也在本包。全仓只有
 * {@code behavior.navigation.baritone} 可以使用 Baritone 的非 {@code baritone.api} 包；
 * 内嵌 Baritone 的逐刻驱动接入还没有接上。
 */
package org.maiwithu.maicraft.behavior.navigation;
