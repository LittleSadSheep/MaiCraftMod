// SPDX-License-Identifier: GPL-3.0-only
/**
 * JEI 的读写端：直接调用 JEI 的类，实现 compat.jei 的读写接缝；只翻译，不判断。
 * JEI 插件 JeiRuntimeHandoff 由 JEI 按注解扫描自己创建；没装 JEI 时这个包的类一个都不会被加载。
 */
package org.maiwithu.maicraft.neoforge.compat.jei;
