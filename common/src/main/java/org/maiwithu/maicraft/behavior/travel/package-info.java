// SPDX-License-Identifier: GPL-3.0-only
/**
 * M10 出行：把行程拆成若干段，每段交给一个出行方式（步行、喷气背包、飞行器、电梯、传送门、船）。
 *
 * <p>出行方式通过 {@code behavior.travel.spi} 由联动模块提供。不把高度未核实的坐标直接交给步行引擎。
 */
package org.maiwithu.maicraft.behavior.travel;
