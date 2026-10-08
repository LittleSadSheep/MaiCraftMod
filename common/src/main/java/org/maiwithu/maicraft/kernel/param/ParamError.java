// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

/**
 * 一条参数错误。入口把同一请求的所有错误一次报全，而不是改一个报一个。
 *
 * @param field    出错的参数名
 * @param message  错在哪，用调用方能直接照着改的话说
 * @param expected 期望的写法，例如"整数 1..256"；不适用时为 null
 */
public record ParamError(String field, String message, String expected) {}
