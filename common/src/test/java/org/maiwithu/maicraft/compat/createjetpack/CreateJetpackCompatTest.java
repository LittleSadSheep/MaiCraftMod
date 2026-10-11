// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import org.maiwithu.maicraft.kernel.task.Action;

/** 喷气背包联动：登记飞过去与刹车两样；两个开关差哪个按哪个、读回一致才算拨好、按三次不对就说不清。 */
class CreateJetpackCompatTest {

    /** 开关替身：按一下就翻转（按了不反应时什么都不变）。 */
    private static final class FakeControls implements JetpackControls {
        boolean active;
        boolean hover;
        boolean responds = true;
        int presses;

        @Override public Optional<Pack> pack(LocalPlayer player) {
            return Optional.of(new Pack("create_jetpack:jetpack", -1, active, hover, true, 120, 0.4, -0.03, ""));
        }

        @Override public void press(LocalPlayer player, Toggle toggle) {
            presses++;
            if (!responds) return;
            if (toggle == Toggle.ACTIVE) active = !active;
            else hover = !hover;
        }
    }

    @Test
    void 两个开关差哪个按哪个_读回一致才算拨好() {
        FakeControls controls = new FakeControls();
        JetpackSwitches switches = new JetpackSwitches(new CreateJetpackCompat(controls));
        switches.want(true, true);
        assertEquals(JetpackSwitches.State.WORKING, switches.tick(null, 0));
        assertEquals(2, controls.presses, "两个都差，同一刻各按一下");
        assertEquals(JetpackSwitches.State.DONE, switches.tick(null, 1));
        switches.want(false, true);
        assertEquals(JetpackSwitches.State.WORKING, switches.tick(null, 10));
        assertEquals(3, controls.presses, "只差飞行开关，只按它");
        assertEquals(JetpackSwitches.State.DONE, switches.tick(null, 11));
    }

    @Test
    void 按三次读回还不对就说不清() {
        FakeControls controls = new FakeControls();
        controls.responds = false;
        JetpackSwitches switches = new JetpackSwitches(new CreateJetpackCompat(controls));
        switches.want(true, false);
        long now = 0;
        JetpackSwitches.State state = JetpackSwitches.State.WORKING;
        while (state == JetpackSwitches.State.WORKING && now < 100) {
            state = switches.tick(null, now);
            now += JetpackSwitches.SETTLE_TICKS;
        }
        assertEquals(JetpackSwitches.State.UNCONFIRMED, state);
        assertEquals(JetpackSwitches.MAX_PRESSES, controls.presses);
        assertTrue(switches.unclear().contains("飞行开关"), switches.unclear());
    }

    @Test
    void 登记飞过去与意外往下掉时的刹车() {
        CompatRegistry registry = CompatRegistry.load(List.of(new SupportedMod<>(CreateJetpackCompat.MOD_ID,
                "机械动力：喷气背包", new VerifiedVersions("5.1.2", "5.2"),
                () -> new CreateJetpackCompat(new FakeControls()))), loaderWith("5.1.2"));
        List<TravelMode> modes = registry.travelModes(offlineServices());
        assertEquals(List.of("jetpack"), modes.stream().map(mode -> mode.via().name()).toList());
        assertEquals(1, registry.fallBrakes().size());
    }

    /** 不在世界里的玩家行为：角色为空，只够建出出行方式。 */
    private static PlayerServices offlineServices() {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
        GearChanges noGear = new GearChanges() {
            @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
            @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
        };
        BackpackView empty = new BackpackView() {
            @Override public List<BackpackStack> stacks() { return List.of(); }
            @Override public int usedSlots() { return 0; }
            @Override public int totalSlots() { return 36; }
        };
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), noGear, inner -> inner, (request, permissions) -> null, nobodyOwns,
                itemId -> Set.of(), MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, empty);
    }

    private static LoaderEnvironment loaderWith(String version) {
        return new LoaderEnvironment() {
            @Override public String loaderName() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return modId.equals(CreateJetpackCompat.MOD_ID); }
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
