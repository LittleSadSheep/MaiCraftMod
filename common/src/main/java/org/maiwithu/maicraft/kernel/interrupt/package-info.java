// SPDX-License-Identifier: GPL-3.0-only
/**
 * 打断：每刻决定角色听谁的——手上的主任务，还是某个生存需求的临时任务。
 *
 * <p>只有一条规则：必须立刻处理的打断一切，需要尽快处理的不打断停下不安全的动作，找空当处理的只在两个动作之间插进来。
 * 角色同一时刻只听一个任务的：干活时顺手补光这类辅助动作，也作为生存需求走同一条规则。
 */
package org.maiwithu.maicraft.kernel.interrupt;
