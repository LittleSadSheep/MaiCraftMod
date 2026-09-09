// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

/**
 * 给两个加载器挂接同一套客户端命令：开关施工预览、确认或取消、隐藏显示，以及只看某层或高度区间。
 */
public final class PreviewCommands {
    private PreviewCommands() {}

    // dev 不带子命令时显示状态，preview all 恢复显示全部高度。
    public static <S> LiteralArgumentBuilder<S> attach(LiteralArgumentBuilder<S> root) {
        root.then(dev());
        root.then(LiteralArgumentBuilder.<S>literal("preview")
                .executes(context -> PreviewController.status())
                .then(action("status", PreviewController::status))
                .then(action("confirm", PreviewController::confirm))
                .then(action("cancel", PreviewController::cancel))
                .then(action("show", () -> PreviewController.visible(true)))
                .then(action("hide", () -> PreviewController.visible(false)))
                .then(action("all", () -> PreviewController.layers(Integer.MIN_VALUE, Integer.MAX_VALUE)))
                .then(LiteralArgumentBuilder.<S>literal("layer")
                        .then(RequiredArgumentBuilder.<S, Integer>argument("y", IntegerArgumentType.integer())
                                .executes(context -> PreviewController.layers(
                                        IntegerArgumentType.getInteger(context, "y"),
                                        IntegerArgumentType.getInteger(context, "y")))))
                .then(LiteralArgumentBuilder.<S>literal("range")
                        .then(RequiredArgumentBuilder.<S, Integer>argument("min", IntegerArgumentType.integer())
                                .then(RequiredArgumentBuilder.<S, Integer>argument("max", IntegerArgumentType.integer())
                                        .executes(context -> PreviewController.layers(
                                                IntegerArgumentType.getInteger(context, "min"),
                                                IntegerArgumentType.getInteger(context, "max")))))));
        return root;
    }

    private static <S> LiteralArgumentBuilder<S> dev() {
        return LiteralArgumentBuilder.<S>literal("dev").executes(context -> PreviewController.status())
                .then(action("on", () -> PreviewController.dev(true)))
                .then(action("off", () -> PreviewController.dev(false)));
    }

    // 把无额外参数的命令统一转给控制器，命令返回值沿用控制器的成功或失败结果。
    private static <S> LiteralArgumentBuilder<S> action(String name, java.util.function.IntSupplier action) {
        return LiteralArgumentBuilder.<S>literal(name).executes(context -> action.getAsInt());
    }
}
