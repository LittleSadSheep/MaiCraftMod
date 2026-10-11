// SPDX-License-Identifier: GPL-3.0-only
/**
 * 照明的玩家行为：量光（样本采样与分类、复测）、选灯位（资格与贪心）、随行插火的灯位判断，
 * 以及区域补光与太暗需求共用的随行补光设置。
 *
 * <p>放灯交给施工引擎，这里只判断"哪里暗、灯插哪、亮没亮"。
 */
package org.maiwithu.maicraft.behavior.lighting;
