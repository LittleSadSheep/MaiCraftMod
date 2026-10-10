// SPDX-License-Identifier: GPL-3.0-only
/**
 * EMI 的读写端：直接调用 EMI 的类，实现 compat.emi 的读写接缝；只翻译，不判断。
 * 没装 EMI 时这个包的类一个都不会被加载：联动清单只在确认装了之后才在 lambda 里 new 它。
 */
package org.maiwithu.maicraft.neoforge.compat.emi;
