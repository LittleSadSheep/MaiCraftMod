// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.ModApiMismatch;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.world.FurnaceFuels;

/** 精妙背包的最小读数：认出身上哪几格是背包；读写端对不上就停用；装了且版本对才登记。 */
class BackpackCompatTest {

    /** 背包视图的替身：只有几格固定的东西。 */
    private static BackpackView carrying(String... itemIds) {
        List<BackpackStack> stacks = List.of(itemIds).stream()
                .map(itemId -> new BackpackStack(itemId, 1, 64, false, false, false, false)).toList();
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() { return stacks; }
            @Override public int usedSlots() { return stacks.size(); }
            @Override public int totalSlots() { return 36; }
        };
    }

    @Test
    void 认出身上哪几格是精妙背包() {
        BackpackCompat compat = new BackpackCompat(itemId -> itemId.startsWith("sophisticatedbackpacks:"));
        List<BackpackStack> found = compat.carriedBackpacks(carrying(
                "minecraft:cobblestone", "sophisticatedbackpacks:iron_backpack", "minecraft:torch",
                "sophisticatedbackpacks:backpack"));
        assertEquals(List.of("sophisticatedbackpacks:iron_backpack", "sophisticatedbackpacks:backpack"),
                found.stream().map(BackpackStack::itemId).toList());
        assertTrue(compat.active());
    }

    @Test
    void 读写端的类对不上时停用并抛模组接口对不上() {
        BackpackCompat compat = new BackpackCompat(itemId -> { throw new NoClassDefFoundError("BackpackItem"); });
        assertThrows(ModApiMismatch.class, () -> compat.carriedBackpacks(carrying("minecraft:torch")));
        assertFalse(compat.active());
        assertTrue(compat.disabledReason().orElseThrow().contains("认背包物品"));
    }

    @Test
    void 装了且版本在范围内才登记() {
        SupportedMod<CompatModule> supported = new SupportedMod<>(BackpackCompat.MOD_ID, "精妙背包", new VerifiedVersions("3.25.69", "3.26"),
                () -> new BackpackCompat(itemId -> false));
        CompatRegistry installed = CompatRegistry.load(List.of(supported), loaderWith("3.25.69"));
        assertEquals(List.of(BackpackCompat.MOD_ID), installed.modules().stream().map(module -> module.modId()).toList());
        assertEquals("精妙背包（sophisticatedbackpacks）：已登记，版本 3.25.69", installed.decisions().getFirst());

        CompatRegistry newer = CompatRegistry.load(List.of(supported), loaderWith("3.26.9"));
        assertTrue(newer.modules().isEmpty());
        assertTrue(newer.decisions().getFirst().contains("不登记"), newer.decisions().getFirst());
    }

    /** 只装了精妙背包、版本给定的加载器环境替身。 */
    private static LoaderEnvironment loaderWith(String version) {
        return new LoaderEnvironment() {
            @Override public String loaderName() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return modId.equals(BackpackCompat.MOD_ID); }
            @Override public Optional<String> modVersion(String modId) {
                return isModLoaded(modId) ? Optional.of(version) : Optional.empty();
            }
            @Override public Path gameDirectory() { return Path.of("."); }
            @Override public Path configDirectory() { return Path.of("."); }
            @Override public boolean isDevelopment() { return true; }
            @Override public FurnaceFuels furnaceFuels() { return stack -> 0; }
        };
    }
}
