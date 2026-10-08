// SPDX-License-Identifier: GPL-3.0-only
/**
 * 原生操作与回执：先提交，再逐刻对账。
 *
 * <p>"发出不等于成功"：回执用连续稳定刻确认，身体或控制版本变化即判为不确定，不谎报成功也不盲目重发。
 * 底座移植自 v1 的 {@code NativeActionPort}、{@code NativeActionReceipt}、{@code NativeConfirmation}。
 * 上层的可组合动作（瞄准、确认、换工位重试）在 {@code behavior.act}。
 */
package org.maiwithu.maicraft.platform.act;
