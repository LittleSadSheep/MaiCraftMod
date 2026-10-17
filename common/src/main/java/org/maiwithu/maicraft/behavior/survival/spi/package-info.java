// SPDX-License-Identifier: GPL-3.0-only
/**
 * 生存与战斗这一块给别人实现的接口：联动模组的远程武器（RangedWeapon）。
 *
 * <p>Create 的土豆加农炮这类不用蓄力、按一下就发的远程武器由联动模组回答事实；挑武器、换手、瞄准、出手
 * 都在战斗的射击动作里统一做，不认具体是哪个模组。没有模组接上时只用弓弩。
 */
package org.maiwithu.maicraft.behavior.survival.spi;
