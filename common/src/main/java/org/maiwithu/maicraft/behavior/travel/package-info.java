// SPDX-License-Identifier: GPL-3.0-only
/**
 * 出行：把一段行程拆成几段，每段选一种出行方式（步行、喷气背包、飞行器、电梯、传送门、船）。
 *
 * <p>出行方式通过 {@code behavior.travel.spi} 由联动模组提供，启动时明确登记。不把高度没核实的坐标直接交给寻路。
 */
package org.maiwithu.maicraft.behavior.travel;
