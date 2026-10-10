// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

/**
 * 界面通道：界面会话观察与关闭一份界面的接缝；实现接在游戏接口层的菜单入口上，测试用替身。
 *
 * <p>每个方法每刻最多做一件事（放回物品的点击、请求关闭），等结果的活由界面会话自己分刻来做。
 */
public interface MenuChannel {

    /**
     * 打开的界面是否仍是认领时的那一份。实现必须把界面对象与编号绑在一起核对：
     * 复用同一编号的另一只箱不是同一份界面，不能算还开着。
     */
    boolean stillOpen();

    /** 光标上是否拿着物品；光标为空才动手。 */
    boolean cursorCarrying();

    /**
     * 在一个槽位上点一下鼠标：button 0 是左键，1 是右键；收尾放回物品用。
     * 本刻点出去了返回真；本刻点不了（没有交互机会、上一下没结清、界面还没画好）返回假，什么都没做，下一刻再试。
     */
    boolean click(int slot, int button);

    /** 请游戏关闭这份界面；界面真的从画面上消失（{@link #stillOpen} 为假）才算关上。 */
    void requestClose();
}
