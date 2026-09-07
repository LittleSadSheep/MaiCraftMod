// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

/** Brigadier source is deliberately generic so both client command registries share one tree. */
public final class PreviewCommands {
    private PreviewCommands() {}

    public static <S> LiteralArgumentBuilder<S> attach(LiteralArgumentBuilder<S> root) {
        root.then(dev("Dev")).then(dev("dev"));
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

    private static <S> LiteralArgumentBuilder<S> dev(String name) {
        return LiteralArgumentBuilder.<S>literal(name).executes(context -> PreviewController.status())
                .then(action("on", () -> PreviewController.dev(true)))
                .then(action("off", () -> PreviewController.dev(false)));
    }

    private static <S> LiteralArgumentBuilder<S> action(String name, java.util.function.IntSupplier action) {
        return LiteralArgumentBuilder.<S>literal(name).executes(context -> action.getAsInt());
    }
}
