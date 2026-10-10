// SPDX-License-Identifier: GPL-3.0-only
/**
 * 机器能力给联动模组实现的接口：机器类型（MachineType）与网络读取器（NetworkReader），以及它们交换的几种记录。
 *
 * <p>机器能力只认这些接口，不认具体是哪个模组的机器；联动模组在自己的联动入口里实现，经登记表交来。
 */
package org.maiwithu.maicraft.ability.machine.spi;
