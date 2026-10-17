// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 认得出哪些界面：原版界面加上联动模组证明过的模组界面。打开界面、读两侧内容都按这一份判。
 *
 * <p>启动时由联动登记表收齐模组的证明，进世界后交给打开界面的代码；没装联动模组时就是只认原版。
 */
public final class MenuLayouts {

    /** 只认原版界面：合成、烧炼这类只会用到原版工作站的地方，以及没有联动模组时。 */
    public static final MenuLayouts VANILLA = new MenuLayouts(List.of());

    private final Map<String, MenuLayoutProof> proofs = new HashMap<>();

    /**
     * @param proofs 模组界面的证明；同一种界面只能有一份证明，原版界面不接受改写
     */
    public MenuLayouts(List<MenuLayoutProof> proofs) {
        for (MenuLayoutProof proof : proofs) {
            for (String type : proof.menuTypes()) {
                String id = type.toLowerCase(Locale.ROOT);
                // 原版界面的判定是游戏事实，不让模组的证明改写；两个证明抢同一种界面说明登记错了。
                if (id.startsWith("minecraft:")) {
                    throw new IllegalArgumentException("模组的布局证明不能改写原版界面 " + id);
                }
                if (this.proofs.putIfAbsent(id, proof) != null) {
                    throw new IllegalArgumentException("界面 " + id + " 已经有一份布局证明了");
                }
            }
        }
    }

    /** 判定这个界面两侧怎么分：原版界面按原版的样子，模组界面按登记的证明，都没有就是证明不了。 */
    public MenuLayout.Layout classify(MenuSlots slots) {
        return MenuLayout.classify(slots, proofs::get);
    }
}
