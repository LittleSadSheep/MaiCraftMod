// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ultimine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.interaction.spi.ChainKeyHold;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * FTB Ultimine（连锁破坏）的联动入口：交给登记表一样东西——连锁挖。采掘挖整条矿脉、整根树干，
 * 清障挖命中面一片，往下开台阶、横着开通道一行一行挖，都按住 FTB 的连锁键、用 FTB 自己的形状和预览。
 * 每一下读写都经联动入口包一层，装的版本对不上时整个联动停用，挖掘退回逐格。
 */
public final class UltimineCompat extends CompatModule {

    public static final String MOD_ID = "ftbultimine";

    private final UltimineControls controls;

    public UltimineCompat(UltimineControls controls) {
        super(MOD_ID, "FTB 连锁破坏");
        this.controls = Objects.requireNonNull(controls, "controls");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 连锁挖：服务器收得到 FTB 的包才开得出连锁键；读写都经这一层，对不上时停用。
        UltimineControls called = new Called();
        ChainSite site = new LiveChainSite();
        registry.breakAccelerator(this, services -> new Accelerator(called, site));
    }

    /** 开连锁键：服务器没装 FTB 时开不出，挖掘逐格。 */
    private record Accelerator(UltimineControls controls, ChainSite site) implements BreakAccelerator {
        @Override public Optional<ChainKeyHold> hold(ChainShape shape, Predicate<BlockPos> mayBreak) {
            if (!controls.serverListens()) return Optional.empty();
            return Optional.of(new UltimineKeyHold(controls, site, shape, mayBreak));
        }
    }

    /** 碰读写端的每一下都经联动入口：接口对不上时停用并抛 ModApiMismatch，由登记表的包装答"用不了"。 */
    private final class Called implements UltimineControls {
        @Override public boolean serverListens() {
            return call("看服务器收不收得到连锁包", controls::serverListens);
        }

        @Override public boolean heldByHand() {
            return call("看连锁键有没有人按着", controls::heldByHand);
        }

        @Override public void holdKey(boolean down) {
            run(down ? "按下连锁键" : "松开连锁键", () -> controls.holdKey(down));
        }

        @Override public List<String> shapes() {
            return call("读连锁形状", controls::shapes);
        }

        @Override public int currentShape() {
            return call("读当前连锁形状", controls::currentShape);
        }

        @Override public void cycleShape(boolean next) {
            run("切连锁形状", () -> controls.cycleShape(next));
        }

        @Override public Selection selection() {
            return call("读连锁选区", controls::selection);
        }

        @Override public int outlineLimit() {
            return call("读连锁选区的显示上限", controls::outlineLimit);
        }

        @Override public Optional<String> refusal(LocalPlayer player, BlockPos pos) {
            return call("问能不能连锁", () -> controls.refusal(player, pos));
        }

        @Override public boolean hudHidden() {
            return call("看界面藏没藏", controls::hudHidden);
        }
    }
}
