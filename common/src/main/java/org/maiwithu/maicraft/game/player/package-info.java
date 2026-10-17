// SPDX-License-Identifier: GPL-3.0-only
/**
 * 角色：每刻重新取得的本地玩家上下文，以及按键、视角与移动输入，和持续使用时按住使用键的投影。
 *
 * <p>上下文只在本刻有效，不能留到下一刻使用；每刻只准向游戏提交一次交互。
 */
package org.maiwithu.maicraft.game.player;
