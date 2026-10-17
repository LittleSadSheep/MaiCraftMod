// SPDX-License-Identifier: GPL-3.0-only
/**
 * 出行这一块给别人实现的接口：出行方式（TravelMode）与它的报价、一段行程。
 *
 * <p>Create 电梯、喷气背包、火车、自己的载具由联动模组或载具能力实现；行程规划只认这个接口，
 * 不认具体是哪个模组。没有方式接上时只走路。
 */
package org.maiwithu.maicraft.behavior.travel.spi;
