// SPDX-License-Identifier: GPL-3.0-only
/**
 * JEI 的联动：把 JEI 当配方查看器。EMI 也装着时配方查询先问 EMI（它已经导入了 JEI 的类别），
 * EMI 还在加载或没装时由 JEI 回答。
 *
 * <p>读写接缝 JeiReads 只用 Java 与项目自己的配方记录描述要读什么；直接调用 JEI 类的读写端在 NeoForge 模块里。
 */
package org.maiwithu.maicraft.compat.jei;
