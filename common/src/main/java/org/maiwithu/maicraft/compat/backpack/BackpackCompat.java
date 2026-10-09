// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;

/**
 * 精妙背包的联动入口。现在只做一件最小的读数：认出角色主背包里哪几格放的是精妙背包。
 * 从背包里拿东西的来源与腾地方往背包里塞的随身背包都要先读到背包里的内容，那一步还没做，
 * 所以暂时不向登记表交任何 spi 实现；这一层先把编译、联动清单、版本检查与停用包装整条路走通。
 */
public final class BackpackCompat extends CompatModule {

    public static final String MOD_ID = "sophisticatedbackpacks";

    private final BackpackItems items;

    public BackpackCompat(BackpackItems items) {
        super(MOD_ID, "精妙背包");
        this.items = Objects.requireNonNull(items, "items");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 还没有能交的 spi 实现：物品来源与随身背包都要先能读背包里的内容。
    }

    /** 角色主背包里放着精妙背包的那些格子，按格子顺序；认物品经读写端，模组接口对不上时整个联动停用。 */
    public List<BackpackStack> carriedBackpacks(BackpackView backpack) {
        List<BackpackStack> found = new ArrayList<>();
        for (BackpackStack stack : backpack.stacks()) {
            if (call("认背包物品", () -> items.isBackpack(stack.itemId()))) {
                found.add(stack);
            }
        }
        return List.copyOf(found);
    }
}
