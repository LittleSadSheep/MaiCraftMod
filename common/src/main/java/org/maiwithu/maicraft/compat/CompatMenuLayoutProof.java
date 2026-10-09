// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 联动模组交来的界面布局证明，登记表给它包的一层：模组停用后一律回答证明不了并写明原因，
 * 判定时碰到模组接口对不上也在这里收住——界面一格都不点，按不支持结束，不让它变成内部错误。
 */
public final class CompatMenuLayoutProof implements MenuLayoutProof {

    private final CompatModule module;
    private final MenuLayoutProof proof;
    /** 界面类型在登记时抄一份：停用之后也还认得这些界面是这个模组的，回答"证明不了"而不是"认不出"。 */
    private final Set<String> menuTypes;

    public CompatMenuLayoutProof(CompatModule module, MenuLayoutProof proof) {
        this.module = Objects.requireNonNull(module, "module");
        this.proof = Objects.requireNonNull(proof, "proof");
        this.menuTypes = Set.copyOf(module.call("说明自己的界面类型", proof::menuTypes));
    }

    @Override public Set<String> menuTypes() {
        return menuTypes;
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        if (!module.active()) {
            return new MenuLayout.Unsupported(module.name() + "的联动已停用："
                    + module.disabledReason().orElse("原因不明") + "，这个界面一格都不点");
        }
        try {
            return module.call("判定界面两侧", () -> proof.classify(slots, playerSlots, otherSlots));
        } catch (ModApiMismatch broken) {
            return new MenuLayout.Unsupported(broken.getMessage() + "，这个界面一格都不点");
        }
    }
}
