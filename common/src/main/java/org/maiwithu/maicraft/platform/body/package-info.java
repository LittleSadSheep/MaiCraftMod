// SPDX-License-Identifier: GPL-3.0-only
/**
 * 身体端口：每刻重新取得的本地玩家上下文、按键与视角输入。
 *
 * <p>上下文只在本刻有效，不能留到下一刻使用；每刻只准提交一次原生操作。
 * 底座移植自 v1 的 {@code client.actor}（登记在 docs/design/移植清单.md）。
 */
package org.maiwithu.maicraft.platform.body;
