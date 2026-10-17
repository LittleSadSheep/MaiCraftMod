// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

/**
 * 聊天草稿：把一句话逐字打进真实聊天框的接缝；实现接在原版聊天界面上，测试用替身。
 *
 * <p>只动自己开出来的框：框被别的界面占着时不抢，交 false 由打字动作等它让出来；
 * 暂停或收尾时关掉的也只是自己的那份，玩家或别的任务开的界面不碰。
 */
public interface ChatDraftScreen {

    /** 把草稿显示进聊天框：框空着就开框，框已经是自己的就更新框里的字；框被别的界面占着时不动它，给 false。 */
    boolean show(String draft);

    /** 此刻开着的聊天框是不是自己那份；被换成别的界面后为 false。 */
    boolean showing();

    /** 关掉自己开出的框；框已经不在、或已经被换成别的界面时不动。 */
    void close();
}
