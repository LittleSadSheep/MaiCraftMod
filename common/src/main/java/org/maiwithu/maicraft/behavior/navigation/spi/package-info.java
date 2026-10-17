// SPDX-License-Identifier: GPL-3.0-only
/**
 * 寻路给别人实现的接口：障碍来源（{@code ObstacleSource}）——会动的结构（Create 的移动装置、电梯轿厢、
 * 列车车厢，Sable 的物理结构）当刻在世界里的碰撞盒与运动。寻路只认这个接口，按统一的规矩换成走不得的地方，
 * 不认具体是哪个模组。
 */
package org.maiwithu.maicraft.behavior.navigation.spi;
