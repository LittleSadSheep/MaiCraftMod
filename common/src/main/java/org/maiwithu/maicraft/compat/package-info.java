// SPDX-License-Identifier: GPL-3.0-only
/**
 * 联动模组：为第三方模组（Create、AE2、精妙背包……）写的适配，每个模组一个子包，只实现玩家行为层或能力定义的 spi 接口。
 *
 * <p>包根是接一个模组的公共部分：联动入口 CompatModule、联动清单的一行 CompatRow、验证过的版本范围 VersionRange、
 * 联动登记表 CompatRegistry，以及模组接口对不上时的异常 ModApiMismatch。
 * 启动时按联动清单逐行检查，装了且版本在范围内才登记；联动包之间互不依赖，也不依赖能力的内部包。
 * 直接调用模组类的读写端不在这里，在 NeoForge 模块里；这里只用 Minecraft 与 Java 的类型描述要读什么、点什么。
 */
package org.maiwithu.maicraft.compat;
