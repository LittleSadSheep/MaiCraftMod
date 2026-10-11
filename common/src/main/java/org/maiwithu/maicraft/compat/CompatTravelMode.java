// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 联动模组交来的出行方式，登记表给它包的一层：模组停用后问报价如实答"这里用不了"，行程照常比别的方式；
 * 碰到模组接口对不上也在这里收住。跑起来的一段同样包一层，推进到一半停用按不支持收场，人停在哪就从哪接着规划。
 */
public final class CompatTravelMode implements TravelMode {

    private final CompatModule module;
    private final TravelMode mode;
    /** 取值、说明与自己的参数进世界时读一次：参数表要用，停用后也照样认得。 */
    private final TravelVia via;
    private final List<ParamSpec> options;

    public CompatTravelMode(CompatModule module, TravelMode mode) {
        this.module = Objects.requireNonNull(module, "module");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.via = Objects.requireNonNull(mode.via(), "via");
        this.options = List.copyOf(mode.options());
    }

    @Override public TravelVia via() {
        return via;
    }

    // 问报价：停用了或对不上就答"这里没有这种方式"，原因写明是哪个模组。
    @Override public void offer(TravelRequest request, OfferSink sink) {
        if (!module.active()) {
            sink.decline(Problem.Kind.UNSUPPORTED, module.name() + "的联动已停用：" + module.disabledReason().orElse("原因不明"));
            return;
        }
        try {
            mode.offer(request, sink);
        } catch (ModApiMismatch broken) {
            sink.decline(Problem.Kind.UNSUPPORTED, broken.getMessage());
        }
    }

    // 这种方式自己的参数：进世界时登记一次，停用后照样认得（给了会如实答用不了）。
    @Override public List<ParamSpec> options() {
        return options;
    }

    // 开跑这一段：停用了或对不上就给一段当场以不支持收场的，人没动，行程从原地接着规划。
    @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
        if (!module.active()) {
            return new Refused(module.name() + "的联动已停用：" + module.disabledReason().orElse("原因不明"));
        }
        try {
            return new Leg(module, mode.start(request, offer));
        } catch (ModApiMismatch broken) {
            return new Refused(broken.getMessage());
        }
    }

    /** 开不了跑的一段：第一刻就以不支持收场。 */
    private record Refused(String reason) implements TravelLeg {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, reason, null));
        }

        @Override public void requestSafeStop() {}

        @Override public String describe() {
            return "用不了：" + reason;
        }
    }

    /** 跑起来的一段：推进、暂停、收尾按动作那一层收住；安全停下、交代变化也不往外抛模组的错。 */
    private static final class Leg extends CompatAction implements TravelLeg {

        private final CompatModule module;
        private final TravelLeg leg;

        Leg(CompatModule module, TravelLeg leg) {
            super(module, leg);
            this.module = module;
            this.leg = leg;
        }

        @Override public void requestSafeStop() {
            if (!module.active()) return;
            try {
                leg.requestSafeStop();
            } catch (ModApiMismatch ignored) {
                // 已经停用，下一刻推进时按不支持收场。
            }
        }

        @Override public List<Change> changes() {
            return leg.changes();
        }

        @Override public List<Change> unconfirmed() {
            return leg.unconfirmed();
        }

        @Override public String summary() {
            return leg.summary();
        }
    }
}
