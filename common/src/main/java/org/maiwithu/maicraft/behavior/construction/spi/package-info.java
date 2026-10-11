// SPDX-License-Identifier: GPL-3.0-only
/**
 * 施工这一块给别人实现的接口：放置规则（PlacementRules）。
 *
 * <p>模组方块拿什么物品放、对着哪一格放、要等哪几格先放好、放下以后自己变成什么，和原版默认不一样时由联动模组回答；
 * 施工引擎只认这个接口，不认具体是哪个模组。没有模组接上时一律按原版默认：拿方块自己的物品，对着目标格放。
 */
package org.maiwithu.maicraft.behavior.construction.spi;
