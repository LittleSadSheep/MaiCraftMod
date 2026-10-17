// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.behavior.landing.spi.FallBrake;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 联动模组交来的刹车，登记表给它包的一层：模组停用后刹不住（落地防护照原来放水）；
 * 刹到一半碰到模组接口对不上，就算没接住，如实说原因，不让它变成生存需求的内部错误。
 */
final class CompatFallBrake implements FallBrake {

    private final CompatModule module;
    private final FallBrake brake;

    CompatFallBrake(CompatModule module, FallBrake brake) {
        this.module = Objects.requireNonNull(module, "module");
        this.brake = Objects.requireNonNull(brake, "brake");
    }

    @Override public Optional<CatchesFall> brake(PlayerContext context) {
        if (!module.active()) return Optional.empty();
        try {
            return brake.brake(context).map(Guarded::new);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    /** 刹的过程每一步都收住模组接口对不上：之后算没接住。 */
    private static final class Guarded implements CatchesFall {
        private final CatchesFall inner;
        private String broken;

        Guarded(CatchesFall inner) {
            this.inner = inner;
        }

        @Override public boolean prepare(PlayerContext context) {
            try {
                return broken == null && inner.prepare(context);
            } catch (ModApiMismatch failure) {
                broken = failure.getMessage();
                return false;
            }
        }

        @Override public void tick(PlayerContext context) {
            if (broken != null) return;
            try {
                inner.tick(context);
            } catch (ModApiMismatch failure) {
                broken = failure.getMessage();
            }
        }

        @Override public boolean complete() {
            return broken == null && inner.complete();
        }

        @Override public boolean failed() {
            return broken != null || inner.failed();
        }

        @Override public String detail() {
            return broken != null ? "刹车用不了：" + broken : inner.detail();
        }

        @Override public String itemId() {
            return inner.itemId();
        }

        @Override public BlockPos landingFeet() {
            return inner.landingFeet();
        }

        @Override public boolean touchedDown(PlayerContext context) {
            return inner.touchedDown(context);
        }

        @Override public PlayerInput.Movement steering(PlayerContext context, float yaw) {
            return inner.steering(context, yaw);
        }

        @Override public void stop(PlayerContext context, String reason) {
            try {
                inner.stop(context, reason);
            } catch (ModApiMismatch failure) {
                broken = failure.getMessage();
            }
        }

        @Override public List<Change> drainChanges() {
            return inner.drainChanges();
        }
    }
}
