// SPDX-License-Identifier: GPL-3.0-only
/**
 * NeoForge 一侧的联动：联动清单（客户端一份、服务端一份），以及每个模组一个子包的模组读写端。
 *
 * <p>读写端直接调用模组的类（以 compileOnly 编译，不用反射），只做翻译、不做判断；
 * 游戏逻辑与判断都在公共模块的 compat.&lt;模组&gt; 里。模组的类只许出现在这个包和它的子包里。
 */
package org.maiwithu.maicraft.neoforge.compat;
