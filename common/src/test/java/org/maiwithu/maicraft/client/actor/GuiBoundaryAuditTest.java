// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Source boundary audit: headless tests cannot launch the real crafting task or its native UI. */
public final class GuiBoundaryAuditTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        String base = "common/src/main/java/org/maiwithu/maicraft/";
        while (root != null && !Files.isDirectory(root.resolve(base))) root = root.getParent();
        check(root != null, "run the GUI regression suite from inside the project");
        String craft = Files.readString(root.resolve(base + "core/task/craft/CraftCompanionTask.java"));
        for (String method : new String[]{"placeRecipe", "takeResult", "stowResult", "returnCraftingGrid"}) {
            String body = method(craft, method);
            before(body, "craftingMenuReady()", ".menus().", method + " must await the GUI before mutation",
                    method.equals("placeRecipe") ? "placeRecipe(" : "click(");
        }
        String ready = method(craft, "craftingMenuReady");
        check(ready.contains(".menus().ensureVisible(context)"), "craft readiness uses the shared visible GUI gate");
        check(ready.contains("InputDriver.halt(player)"), "craft GUI waiting releases movement");
        String cleanup = method(craft, "cleanup");
        check(cleanup.contains("MenuVisibility.inventoryVisible"), "terminal cleanup includes the 2x2 GUI");
        check(cleanup.contains(".menus().closeForTaskBoundary("), "cancellation delegates closure to the actor boundary");
        check(!craft.contains("player.closeContainer("), "crafting cannot bypass serialized GUI closure");
        before(method(craft, "closeMenu"), ".menus().close(", "beginTemporaryStationRecovery(",
                "successful crafting closes its GUI before recovering a table", "");
        String parent = Files.readString(root.resolve(base + "core/task/base/AbstractCompanionTask.java"));
        before(method(parent, "result"), "cleanup();", "return switch", "every terminal result runs GUI cleanup", "");

        String port = Files.readString(root.resolve(base + "client/actor/DefaultMenuPort.java"));
        String opening = method(port, "ensureVisible");
        before(opening, "DefaultBodyControlPort.permitsWorldMovement(", ".minecraft().setScreen(",
                "the same world/chat policy allows tasks to open their visible inventory", "");
        before(opening, "current.claimMutation()", ".minecraft().setScreen(",
                "opening from chat still claims the per-tick mutation", "");
        check(opening.contains("visibility.ready(current)"),
                "opening from chat must still wait for the matching GUI to render");
        for (String name : new String[]{"click", "swapInventoryToHotbar", "placeRecipe"}) {
            before(method(port, name), "requireVisible(", ".gameMode().handle",
                    name + " cannot submit a native menu mutation while hidden", "");
        }
        String renderer = Files.readString(root.resolve(base + "core/mixin/GameRendererCameraMixin.java"));
        check(renderer.contains("Lnet/minecraft/client/Minecraft;pauseGame(Z)V")
                        && renderer.contains("!ClientRuntime.actor().effectiveAutomationControlRequested()"),
                "automatic focus-loss pause follows effective control, allowing native pause during human review");
        check(!renderer.contains("pauseOnLostFocus ="), "takeover cannot persist or overwrite user pause settings");
        String mouse = Files.readString(root.resolve(base + "core/mixin/MouseHandlerControlMixin.java"));
        check(mouse.contains("method = \"grabMouse\"") && mouse.contains("cancellable = true")
                        && mouse.contains("preventsMouseGrab()) callback.cancel()"),
                "every native cursor grab must respect automation, including screen closure and clicks");
        String mixins = Files.readString(root.resolve("common/src/main/resources/maicraft.mixins.json"));
        check(mixins.contains("\"MouseHandlerControlMixin\""), "the cursor guard must be registered");
        System.out.println("GuiBoundaryAuditTest: passed");
    }

    private static void before(String source, String guard, String target, String message, String suffix) {
        int gate = source.indexOf(guard);
        int mutation = source.indexOf(target + suffix);
        check(gate >= 0 && mutation >= 0 && gate < mutation, message);
    }

    private static String method(String source, String name) {
        var match = Pattern.compile("(?:public|private|protected)\\s+(?:final\\s+)?[\\w<>]+\\s+"
                + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*\\{").matcher(source);
        check(match.find(), "missing method boundary: " + name);
        int start = match.end(), depth = 1;
        for (int index = start; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) return source.substring(start, index);
        }
        throw new AssertionError("Unclosed method: " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
