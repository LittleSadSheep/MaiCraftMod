package org.maiwithu.maicraft.client.actor;

import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.check;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.field;

/** 已授权的无人值守会话从观察阶段就能自卫，重生延续控制；人工撤回或换连接不能继承旧授权。 */
public final class AutomationContinuityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        startupIsExplicitAndOnce(); nativeRespawnKeepsExistingRequest();
        System.out.println("AutomationContinuityTest: startup and respawn control boundaries passed");
    }
    private static void startupIsExplicitAndOnce() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            int[] requests = {0};
            var startup = new StartupAutomation(true);
            check(!new StartupAutomation(false).tick(world.player, p -> requests[0]++), "ordinary clients do not opt into startup control");
            check(!startup.tick(null, p -> requests[0]++), "loading waits for an actual body");
            world.player.setHealth(0);
            check(!startup.tick(world.player, p -> requests[0]++), "the death screen cannot consume the initial takeover");
            world.player.setHealth(20);
            check(startup.tick(world.player, p -> { requests[0]++; world.h.body.requestAutomation(p); }), "a live test body requests control before any game task");
            world.h.body.toggleHumanRequest(world.player);
            check(!startup.tick(world.player, p -> requests[0]++) && requests[0] == 1
                    && !world.h.body.automationControlRequested(), "an explicit human takeover stays authoritative after startup");
        }
    }
    private static void nativeRespawnKeepsExistingRequest() throws Exception {
        try (var old = new InteractionWorldTestHarness(); var replacement = new InteractionWorldTestHarness()) {
            var identity = UUID.randomUUID(); old.player.setUUID(identity); replacement.player.setUUID(identity);
            field(LocalPlayer.class, "connection").set(replacement.player, old.player.connection);
            check(!ClientActorBoundary.samePlayerRespawn(old.player, replacement.player), "a live same-identity replacement is not proof of respawn");
            old.player.setHealth(0);
            check(ClientActorBoundary.samePlayerRespawn(old.player, replacement.player), "death plus matching identity and native connection proves the continuity");
            replacement.h.body.bodyReplaced(null, false);
            old.h.body.bodyReplaced(replacement.player, ClientActorBoundary.samePlayerRespawn(old.player, replacement.player));
            check(old.h.body.automationControlRequested() && old.h.body.fulfillAutomationRequest(replacement.player),
                    "the authorized request binds to the replacement body without another model command");
            old.h.body.toggleHumanRequest(replacement.player);
            old.h.body.bodyReplaced(old.player, true);
            check(!old.h.body.automationControlRequested(), "preservation never creates authorization after the human has revoked it");
            field(LocalPlayer.class, "connection").set(replacement.player, replacement.h.connection);
            check(!ClientActorBoundary.samePlayerRespawn(old.player, replacement.player), "another connection cannot reuse a dead body's authority");
            field(LocalPlayer.class, "connection").set(replacement.player, old.player.connection);
            replacement.player.setUUID(UUID.randomUUID());
            check(!ClientActorBoundary.samePlayerRespawn(old.player, replacement.player), "another player identity cannot inherit automation");
        }
    }
}
