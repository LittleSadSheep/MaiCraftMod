// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.utils.InputOverrideHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.function.Function;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneNavigator;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;

/** 使用真实导航输入桥回放插灯转头；转向按正常转头逐帧推进，物理移动仍按 Baritone 路线朝向，不能再次按相机旋转按键。 */
public final class AuxiliaryNavigationTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            AutomaticLightingTest.prepareBody(h);
            var backendField = ActorControlTestHarness.field(EmbeddedBaritoneRuntime.class, "backend");
            var ownerField = ActorControlTestHarness.field(EmbeddedBaritoneRuntime.class, "owner");
            Object previousBackend = backendField.get(null), previousOwner = ownerField.get(null);
            var pathing = h.h.allocate(PathingBehavior.class);
            var playerContext = proxy(IPlayerContext.class, (method) -> switch (method) {
                case "player" -> h.player;
                case "world" -> h.level;
                default -> throw new AssertionError(method);
            });
            var backend = proxy(IBaritone.class, (method) -> switch (method) {
                case "getPlayerContext" -> playerContext;
                case "getPathingBehavior" -> pathing;
                default -> throw new AssertionError(method);
            });
            var inputs = h.h.allocate(InputOverrideHandler.class);
            ActorControlTestHarness.field(InputOverrideHandler.class, "inputForceStateMap").set(inputs, new HashMap<>());
            try {
                backendField.set(null, backend);
                ownerField.set(null, h.h.allocate(EmbeddedBaritoneNavigator.class));
                for (float routeYaw : new float[]{0, 90, -175}) {
                    h.nextTick();
                    h.player.setYRot(routeYaw);
                    var context = ClientRuntime.requireContext(h.player);
                    inputs.setInputForceState(Input.MOVE_FORWARD, true);
                    inputs.setInputForceState(Input.SPRINT, true);
                    EmbeddedBaritoneRuntime.applyInputState(inputs);
                    context.body().requestNavigationLook(routeYaw, 8, context.tickRevision());
                    check(context.body().tryAuxiliaryLook(routeYaw + 90, 60, context.tickRevision()), "平地跑动允许借准星插灯");
                    float turnStart = h.player.getYRot();
                    // 借用只登记方向：镜头必须按正常转头逐帧靠近灯位，而不是越过中间角度的瞬转。
                    h.renderFrames(3);
                    check(Math.abs(Mth.wrapDegrees(h.player.getYRot() - turnStart)) > 0.5f
                                    && Math.abs(Mth.wrapDegrees(h.player.getYRot() - (routeYaw + 90))) > 1
                                    && Math.abs(h.player.getXRot() - 60) > 1,
                            "插灯转向必须逐帧推进，不能一帧就到灯位: yaw=" + h.player.getYRot() + " pitch=" + h.player.getXRot());
                    boolean borrowed = context.body().tryAuxiliaryLook(routeYaw + 90, 60, context.tickRevision());
                    check(borrowed, "同一灯位的下一刻应能继续推进转向");
                    ((DefaultBodyControlPort) context.body()).endTick((DefaultLocalPlayerContext) context);
                    // MixinEntity 在 moveRelative 内使用路线朝向；这里按相同的原版旋转规则核对真实世界运动方向。
                    Vec3 actual = new Vec3(h.player.input.leftImpulse, 0, h.player.input.forwardImpulse)
                            .yRot((float) Math.toRadians(-routeYaw));
                    Vec3 expected = new Vec3(0, 0, 1).yRot((float) Math.toRadians(-routeYaw));
                    check(actual.distanceToSqr(expected) < 1e-8, "插灯把导航前进扭成了横移: " + actual + " expected=" + expected);
                    check(h.player.input.up && !h.player.input.left && !h.player.input.right && h.player.isSprinting(),
                            "导航原有前进与疾跑按键保持不变");
                }
            } finally { backendField.set(null, previousBackend); ownerField.set(null, previousOwner); }
        }
        System.out.println("AuxiliaryNavigationTest: passed");
    }

    private static <T> T proxy(Class<T> type, Function<String, Object> call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> call.apply(method.getName())));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
