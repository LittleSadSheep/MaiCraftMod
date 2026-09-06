// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.Input;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.check;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.field;

/** Native control packets retain actor leases and receipts without acquiring a visible menu. */
public final class ControlProtocolTest {
    private static final NativeConfirmation PENDING = context -> NativeConfirmation.Verdict.PENDING;
    private static final NativeConfirmation APPLIED = context -> NativeConfirmation.Verdict.APPLIED;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        worldAndChatDoNotTouchMenus();
        authorityAndScreenGates();
        serializationAndUncertainty();
        menuProtocolsStillRequireVisibleGui();
        System.out.println("ControlProtocolTest: passed");
    }

    private static void worldAndChatDoNotTouchMenus() throws Exception {
        for (boolean chat : new boolean[]{false, true}) {
            var h = new ActorControlTestHarness();
            Screen screen = chat ? h.allocate(ChatScreen.class) : null;
            h.minecraft.screen = screen;
            Object visibility = h.visibility();
            field(MenuVisibility.class, "readyTick").setLong(visibility, -10);
            int[] submissions = {0};
            var receipt = h.actions.submitControlProtocol(h.context, "test control",
                    () -> submissions[0]++, APPLIED, 5);
            check(receipt.status() == NativeActionReceipt.Status.PENDING,
                    "submission requires confirmation on distinct ticks");
            h.actions.poll(h.context, receipt);
            check(!receipt.terminal(), "same-tick polling cannot manufacture stable confirmation");
            check(!h.context.mutationAvailable(), "control submission claims the tick mutation");
            h.nextTick(true);
            h.actions.poll(h.context, receipt);
            check(receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED,
                    "world controls reconcile through ordinary native receipts");
            check(submissions[0] == 1, "read-only confirmation never resubmits controls");
            check(h.minecraft.screen == screen, "control submission and confirmation preserve the screen");
            check(field(MenuVisibility.class, "readyTick").getLong(visibility) == -10,
                    "control submission and confirmation never signal menu interaction");
        }
    }

    private static void authorityAndScreenGates() throws Exception {
        var h = new ActorControlTestHarness();
        Runnable unexpected = () -> { throw new AssertionError("unauthorized submission executed"); };
        var stale = h.context;
        h.nextTick(true);
        rejects(() -> h.actions.submitControlProtocol(stale, "stale", unexpected, PENDING, 5), "stale");
        h.nextTick(false);
        rejects(() -> h.actions.submitControlProtocol(h.context, "no lease", unexpected, PENDING, 5), "own");
        h.nextTick(true);
        Input botInput = h.player.input;
        h.player.input = new Input();
        rejects(() -> h.actions.submitControlProtocol(h.context, "human", unexpected, PENDING, 5), "own");
        h.player.input = botInput;
        for (Screen screen : new Screen[]{h.allocate(PauseScreen.class), h.inventoryScreen()}) {
            h.minecraft.screen = screen;
            rejects(() -> h.actions.submitControlProtocol(h.context, "modal", unexpected, PENDING, 5), "screen");
            check(h.minecraft.screen == screen && h.context.mutationAvailable(),
                    "disallowed screens remain open and rejected controls consume no mutation");
        }
    }

    private static void serializationAndUncertainty() throws Exception {
        var h = new ActorControlTestHarness();
        int[] submissions = {0};
        var pending = h.actions.submitControlProtocol(h.context, "first", () -> submissions[0]++, PENDING, 5);
        h.nextTick(true);
        rejects(() -> h.actions.submitControlProtocol(h.context, "second", () -> submissions[0]++, PENDING, 5),
                "awaiting confirmation");
        rejects(() -> h.actions.useItem(h.context, net.minecraft.world.InteractionHand.MAIN_HAND, PENDING, 5),
                "awaiting confirmation");
        check(submissions[0] == 1 && h.context.mutationAvailable(), "active controls occupy the shared native slot");
        h.actions.retireOneShotForTaskBoundary(h.context, pending, "task ended");
        check(pending.status() == NativeActionReceipt.Status.UNCERTAIN, "task retirement never asserts rollback");
        var failed = h.actions.submitControlProtocol(h.context, "throwing control", () -> {
            submissions[0]++;
            throw new IllegalStateException("packet sender failed");
        }, APPLIED, 5);
        check(failed.status() == NativeActionReceipt.Status.UNCERTAIN
                        && failed.detail().contains("throwing control"),
                "submission exceptions preserve an uncertain receipt and operation detail");
        rejects(() -> h.actions.submitControlProtocol(h.context, "same tick", () -> submissions[0]++, PENDING, 5),
                "already submitted");
        check(submissions[0] == 2, "exceptions never permit duplicate same-tick submissions");
        h.nextTick(true);
        var observationFailure = h.actions.submitControlProtocol(h.context, "timeout", () -> submissions[0]++,
                context -> { throw new IllegalStateException("facts unavailable"); }, 1);
        h.nextTick(true);
        h.actions.poll(h.context, observationFailure);
        check(observationFailure.status() == NativeActionReceipt.Status.UNCERTAIN
                        && h.context.mutationAvailable() && submissions[0] == 3,
                "unavailable confirmation expires read-only without retrying input");
        var revoked = h.actions.submitControlProtocol(h.context, "revoked", () -> {}, PENDING, 5);
        h.nextTick(false);
        h.actions.poll(h.context, revoked);
        check(revoked.status() == NativeActionReceipt.Status.UNCERTAIN, "revoked authority cannot confirm success");
    }

    private static void menuProtocolsStillRequireVisibleGui() throws Exception {
        var h = new ActorControlTestHarness();
        h.minecraft.screen = h.inventoryScreen();
        int[] submissions = {0};
        rejects(() -> h.actions.submitProtocol(h.context, "menu", () -> submissions[0]++, APPLIED, 5), "GUI");
        check(submissions[0] == 0, "an unrendered menu cannot submit a protocol");
        MenuVisibility.rendered(h.minecraft.screen);
        for (int tick = 0; tick < 4; tick++) h.nextTick(true);
        var receipt = h.actions.submitProtocol(h.context, "menu", () -> submissions[0]++, APPLIED, 5);
        check(submissions[0] == 1, "rendered settled menu still permits its native protocol");
        check(field(MenuVisibility.class, "readyTick").getLong(h.visibility()) == h.tick + 2,
                "menu protocol submissions retain the rendered dwell");
        h.nextTick(true);
        h.actions.poll(h.context, receipt);
        check(receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                        && field(MenuVisibility.class, "readyTick").getLong(h.visibility()) == h.tick + 2,
                "menu protocol confirmation still renews its visible dwell");
    }

    private static void rejects(Runnable action, String detail) {
        try {
            action.run();
            throw new AssertionError("expected rejection containing " + detail);
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains(detail), "unexpected rejection: " + expected.getMessage());
        }
    }
}
