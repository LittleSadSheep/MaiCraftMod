// SPDX-License-Identifier: GPL-3.0-only
/**
 * 参数规格：一个能力的参数只在这里定义一次，能力说明里的参数表、MCP 入口的校验与规范化、带类型的取值都由它生成。
 *
 * <p>参数名必须先登记在 {@link org.maiwithu.maicraft.kernel.param.ParamNames}，同一个概念全接口只有一个名字。
 * 本包不依赖内核的其他包，目标与能力框架都可以使用它。
 */
package org.maiwithu.maicraft.kernel.param;
