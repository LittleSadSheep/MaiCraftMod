// SPDX-License-Identifier: GPL-3.0-only
/**
 * Sable（物理结构）的服务端联动：质量、重心、惯性、各类力（气球升力、推进、浮空）只在服务端，
 * 经只读操作 sable.body 回答受力与配重建议；只读，不施力、不改结构。
 */
package org.maiwithu.maicraft.server.compat.sable;
