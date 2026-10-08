// SPDX-License-Identifier: GPL-3.0-only
/**
 * 联动模组（L3）：每个模组一个子包（Create、AE2、Mekanism……），只实现行为层或能力定义的 spi 接口。
 *
 * <p>启动时按模组是否安装来登记；联动包之间互不依赖，也不依赖能力的内部包。
 */
package org.maiwithu.maicraft.compat;
