// SPDX-License-Identifier: GPL-3.0-only
/**
 * 机械动力（Create）的联动：认出 Create 的机器、读它们转不转与应力网怎样、用置物台投料取货、
 * 用 Create 自己的方式装传送带与链条输送机。联动入口 CreateCompat 把目录 CreateMachineCatalog 里的每种机器、
 * 兜住附属模组动力方块的"其他"两种、应力网的网络读取器交给登记表。
 *
 * <p>读写接缝（CreateKinetics、CreateBelts、CreateChainConveyors、CreateStations）只用 Minecraft 与 Java 的类型；
 * 直接调用 Create 类的读写端在 NeoForge 模块里。形状规则、读数换算、加工处在哪都是纯函数判断。
 */
package org.maiwithu.maicraft.compat.create;
