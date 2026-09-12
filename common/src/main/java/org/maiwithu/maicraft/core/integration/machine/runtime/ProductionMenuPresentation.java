// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.integration.machine.MachineSurvey;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskState;

/** Keep the real container visible throughout a native transfer, then close only this task's menu. */
final class ProductionMenuPresentation {
    private final LocalPlayer player;
    private final ProductionWork work;
    private BlockPos target;
    private net.minecraft.core.Direction face;
    private AbstractContainerMenu menu;
    private Task opening, closing;
    private long visibleSince, changedAt;

    ProductionMenuPresentation(LocalPlayer player, ProductionWork work) { this.player = player; this.work = work; }

    boolean open(BlockPos position, boolean required) {
        return open(position,required,null);
    }
    boolean open(BlockPos position, boolean required, net.minecraft.core.Direction requestedFace) {
        if (target != null && (!target.equals(position) || face != requestedFace)) {
            if (!close()) return false;
        }
        if (menu != null) {
            requireCurrent();
            return player.level().getGameTime() - visibleSince >= 4;
        }
        if (opening == null) {
            var state = player.level().getBlockState(position);
            boolean hasMenu = state.getMenuProvider(player.level(), position) != null
                    || player.level().getBlockEntity(position) instanceof net.minecraft.world.Container;
            if (!required && !hasMenu) return true; // World-facing ports such as a depot have no container screen.
            if (player.containerMenu != player.inventoryMenu || ClientRuntime.requireContext(player).minecraft().screen != null)
                throw new IllegalStateException("production_menu_busy: preserve the existing screen");
            target = position.immutable(); face = requestedFace; work.stopMovement();
            var request = new MachineMenu.OpenRequest(player.level().dimension().location().toString(), target, 0,
                    MachineSurvey.fingerprint(player, target, 0), target, face);
            opening = TaskFactory.create(player, MachineMenu.openTask("production-menu-open", player.level().getGameTime() + 200, request));
        }
        TaskState state = work.advanceChild(opening);
        if (state == null || !state.isTerminal()) return false;
        var result = opening.result(state); opening = null;
        if (!result.success()) throw new IllegalStateException("production_menu_open_failed: " + result.message());
        menu = player.containerMenu; visibleSince = changedAt = player.level().getGameTime();
        requireCurrent();
        return false;
    }

    void requireCurrent() {
        if (menu == null || player.containerMenu != menu || menu == player.inventoryMenu
                || !MenuVisibility.matches(ClientRuntime.requireContext(player).minecraft(), menu) || !menu.getCarried().isEmpty())
            throw new IllegalStateException("production_menu_changed: stop before another machine operation");
    }

    Integer containerId() { if (menu == null) return null; requireCurrent(); return menu.containerId; }
    void changed() { if (menu != null) changedAt = player.level().getGameTime(); }
    boolean beforeTravel(BlockPos destination) { return target == null || target.equals(destination) || close(); }

    boolean close() {
        if (opening != null) throw new IllegalStateException("production_menu_open_unsettled");
        if (menu == null) { target = null; return true; }
        if (closing == null) {
            requireCurrent();
            if (player.level().getGameTime() - changedAt < 4) return false;
            closing = TaskFactory.create(player, MachineMenu.closeTask("production-menu-close", player.level().getGameTime() + 100));
        }
        TaskState state = work.advanceChild(closing);
        if (state == null || !state.isTerminal()) return false;
        var result = closing.result(state); closing = null;
        if (!result.success()) throw new IllegalStateException("production_menu_close_failed: " + result.message());
        menu = null; target = null;
        return true;
    }

    void cancel() {
        for (Task child : new Task[]{opening, closing}) if (child != null) {
            try { child.stop(player, Task.StopReason.REPLACED); } finally { child.result(TaskState.CANCELLED); }
        }
        opening = closing = null;
        if (menu != null && player.containerMenu == menu) {
            try { var context = ClientRuntime.requireContext(player); context.menus().closeForTaskBoundary(context, 40, "production interaction finished"); }
            catch (RuntimeException revoked) { /* The actor handles cleanup after human takeover. */ }
        }
        menu = null; target = null;
    }
}
