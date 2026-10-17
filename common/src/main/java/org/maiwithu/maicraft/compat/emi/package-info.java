// SPDX-License-Identifier: GPL-3.0-only
/**
 * EMI 的联动：把 EMI 当配方查看器，查配方时先问它。EMI 会原样导入 JEI 插件注册的配方类别，
 * 两个都装时问它就看到两者之和。
 *
 * <p>读写接缝 EmiReads 只用 Java 与项目自己的配方记录描述要读什么；直接调用 EMI 类的读写端在 NeoForge 模块里。
 */
package org.maiwithu.maicraft.compat.emi;
