// SPDX-License-Identifier: GPL-3.0-only
/**
 * 计时能力：不占身体的"过 N 秒叫我"，按游戏时间计，到点在任务事件流里发 timer_due；
 * 只改客户端全程一份的计时表，当场完成，换世界、断线时没到点的清掉。
 */
package org.maiwithu.maicraft.ability.timer;
