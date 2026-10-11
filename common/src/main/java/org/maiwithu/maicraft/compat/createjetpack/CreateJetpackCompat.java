// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 机械动力：喷气背包（Create Jetpack）的联动入口。交给登记表两样：喷气背包当一种出行方式（via=jetpack，
 * 飞到能落脚的平台，所有者 10-11 批），和意外往下掉时开悬停刹住（比放水桶先问）。
 * 每一下读开关、按开关键都经联动入口包一层，装的版本对不上时整个联动停用。
 */
public final class CreateJetpackCompat extends CompatModule {

    public static final String MOD_ID = "create_jetpack";

    private final JetpackControls controls;

    public CreateJetpackCompat(JetpackControls controls) {
        super(MOD_ID, "机械动力：喷气背包");
        this.controls = Objects.requireNonNull(controls, "controls");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 意外往下掉：穿着有气的喷气背包就开悬停刹住，落稳再关（所有者 10-11 定，比落水桶优先）。
        registry.fallBrake(this, context -> JetpackFallBrake.start(this, context));
    }

    /** 身上的喷气背包；见 {@link JetpackControls#pack}。 */
    public Optional<JetpackControls.Pack> pack(LocalPlayer player) {
        return call("读喷气背包", () -> controls.pack(player));
    }

    /** 按一下开关键；见 {@link JetpackControls#press}。 */
    public void press(LocalPlayer player, JetpackControls.Toggle toggle) {
        run("按喷气背包开关", () -> controls.press(player, toggle));
    }
}
