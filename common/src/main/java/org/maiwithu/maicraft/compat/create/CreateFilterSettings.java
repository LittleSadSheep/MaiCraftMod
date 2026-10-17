// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 机械动力过滤器与属性过滤器写规则（所有者定 C5，归 machine_configure 对手上物品设置）。
 * 列表过滤器的键：filter.1 ~ filter.18 写物品 ID（empty 清空），filter.mode 写 allow / deny，
 * filter.match_components 写 true / false（连物品组件一起比）。属性过滤器的键：attribute.mode 写 any / all / deny，
 * attributes 写"样本物品 属性文字片段"，几条用分号隔开，片段前加 ! 是取反，写 clear 是清空；
 * 照界面那样先清空再一条条加（加的时候在样本物品的属性里找文字包含片段的那条）。读的时候 attributes 是每条属性的游戏原文。
 * 改：拿到主手 → 右键打开它的界面 → 一下一项 → 关上（关上时 Create 才把规则写进物品）。
 */
final class CreateFilterSettings implements ItemSettings {

    static final String FILTER = CreateMachineCatalog.MOD_ID + ":filter";
    static final String ATTRIBUTE_FILTER = CreateMachineCatalog.MOD_ID + ":attribute_filter";
    private static final int SLOTS = 18;

    private final CreateCompat compat;
    private final PlayerServices services;

    CreateFilterSettings(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public boolean covers(ItemStack stack) {
        String id = idOf(stack);
        return id.equals(FILTER) || id.equals(ATTRIBUTE_FILTER);
    }

    @Override public List<MachineSetting> settings(ItemStack stack) {
        List<MachineSetting> out = new ArrayList<>();
        compat.filterRules(stack).ifPresent(rules -> {
            for (int i = 0; i < SLOTS; i++) {
                String item = i < rules.items().size() ? rules.items().get(i) : "";
                out.add(setting("filter." + (i + 1), item.isEmpty() ? "empty" : item, "第 " + (i + 1) + " 格：物品 ID 或 empty"));
            }
            out.add(setting("filter.mode", rules.deny() ? "deny" : "allow", "allow 只让这些过 / deny 不让这些过"));
            out.add(setting("filter.match_components", String.valueOf(rules.matchComponents()), "true 连物品组件一起比"));
        });
        compat.attributeRules(stack).ifPresent(rules -> {
            out.add(setting("attribute.mode", rules.mode(), "any 满足任一条 / all 全部满足 / deny 满足的不让过"));
            out.add(setting("attributes", String.join("；", rules.attributes()),
                    "写\"样本物品 属性文字片段\"，几条用分号隔开，片段前加 ! 取反，clear 清空"));
        });
        return out;
    }

    // attributes 写法和读法不一样：写的每条片段都能在读到的某条属性原文里找到、条数一样，才算是它。
    @Override public boolean holds(ItemStack stack, String key, String wanted) {
        if (!key.equals("attributes")) return ItemSettings.super.holds(stack, key, wanted);
        Optional<CreateFilters.AttributeRules> rules = compat.attributeRules(stack);
        if (rules.isEmpty()) return false;
        List<String> entries = attributeEntries(wanted);
        if (entries.size() != rules.get().attributes().size()) return false;
        for (String entry : entries) {
            String fragment = fragmentOf(entry).toLowerCase(Locale.ROOT);
            if (rules.get().attributes().stream().noneMatch(text -> text.toLowerCase(Locale.ROOT).contains(fragment))) return false;
        }
        return true;
    }

    @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
        List<Step> steps = new ArrayList<>();
        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            Optional<List<Step>> parsed = steps(entry.getKey(), entry.getValue());
            if (parsed.isEmpty()) return Optional.empty();
            steps.addAll(parsed.get());
        }
        return Optional.of(new Editing(inventorySlot, steps));
    }

    /** 一项设置拆成界面上的几下；认不了的键或值为空。 */
    Optional<List<Step>> steps(String key, String value) {
        if (key.startsWith("filter.") && key.substring(7).chars().allMatch(Character::isDigit) && key.length() > 7) {
            int slot = Integer.parseInt(key.substring(7)) - 1;
            if (slot < 0 || slot >= SLOTS) return Optional.empty();
            String item = value.equals("empty") ? "" : value;
            return Optional.of(List.of(new Step("第 " + (slot + 1) + " 格设成 " + value, () -> {
                compat.setFilterSlot(slot, item);
                return Optional.empty();
            })));
        }
        String button = switch (key) {
            case "filter.mode" -> value.equals("deny") || value.equals("allow") ? value : null;
            case "filter.match_components" -> value.equals("true") ? "match_components" : value.equals("false") ? "ignore_components" : null;
            case "attribute.mode" -> value.equals("any") || value.equals("all") || value.equals("deny") ? value : null;
            default -> null;
        };
        if (button != null) {
            return Optional.of(List.of(new Step("按 " + button, () -> {
                compat.pressFilterButton(button);
                return Optional.empty();
            })));
        }
        if (!key.equals("attributes")) return Optional.empty();
        // 照界面：先按垃圾桶清空，再一条条加。
        List<Step> out = new ArrayList<>();
        out.add(new Step("清空属性", () -> {
            compat.clearFilter();
            return Optional.empty();
        }));
        for (String entry : attributeEntries(value)) {
            String[] parts = entry.split("\\s+", 2);
            if (parts.length < 2) return Optional.empty();
            boolean inverted = parts[1].startsWith("!");
            String fragment = inverted ? parts[1].substring(1).trim() : parts[1];
            out.add(new Step("加属性 " + entry, () -> compat.addAttribute(parts[0], fragment, inverted)
                    .map(options -> parts[0] + " 的属性里没有含\"" + fragment + "\"的，它有：" + String.join("；", options))));
        }
        return Optional.of(out);
    }

    /** attributes 的写法拆成一条条，纯函数：分号隔开（中英文分号都认），clear 与空串是清空。 */
    static List<String> attributeEntries(String value) {
        if (value.isBlank() || value.trim().equals("clear")) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split("[;；]")) if (!part.isBlank()) out.add(part.trim());
        return out;
    }

    // "样本物品 片段"里的片段（去掉取反的 !）。
    private static String fragmentOf(String entry) {
        String[] parts = entry.split("\\s+", 2);
        String fragment = parts.length < 2 ? "" : parts[1];
        return fragment.startsWith("!") ? fragment.substring(1).trim() : fragment;
    }

    private static MachineSetting setting(String key, String value, String doc) {
        return new MachineSetting(key, value, List.of(), doc);
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 界面里的一下：发出去；做不了时给一句原因。 */
    record Step(String what, Supplier<Optional<String>> run) {
    }

    /** 拿到主手 → 右键打开 → 一刻一下 → 关上。 */
    private final class Editing implements Action {
        private enum Stage { HAND, OPEN, EDIT, CLOSE, FINISHED }

        /** 右键后等过滤器界面打开的期限（刻）。 */
        private static final int OPEN_TIMEOUT_TICKS = 20;
        private static final int CLOSE_TIMEOUT_TICKS = 40;

        private final int inventorySlot;
        private final Deque<Step> steps;
        private Stage stage = Stage.HAND;
        private Action current;
        private PendingMenuAction closing;
        private PendingInteraction opening;
        private Problem problem;

        Editing(int inventorySlot, List<Step> steps) {
            this.inventorySlot = inventorySlot;
            this.steps = new ArrayDeque<>(steps);
        }

        @Override public ActionStatus tick(TickContext context) {
            PlayerContext player = context.player();
            if (player == null || player.localPlayer() == null) return ActionStatus.running();
            return switch (stage) {
                case HAND -> run(context, () -> toHand(player.localPlayer()), Stage.OPEN, "没能把过滤器拿到主手");
                case OPEN -> open(player);
                case EDIT -> edit(player);
                case CLOSE -> close(player);
                case FINISHED -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        // 那件拿到主手：已经在主手不动；在主背包换到选中的快捷栏格；别处按物品找。
        private Action toHand(LocalPlayer self) {
            int selected = self.getInventory().selected;
            if (inventorySlot == selected) return null;
            if (inventorySlot >= 9 && inventorySlot <= 35) {
                return new ClientMovesToMainhand(services.context()).actionToHotbar(inventorySlot, selected, "把过滤器拿到主手").orElse(null);
            }
            return services.toMainhand().moveToMainhand(idOf(self.getInventory().getItem(inventorySlot))).orElse(null);
        }

        private ActionStatus run(TickContext context, Supplier<Action> make, Stage next, String failed) {
            if (current == null) {
                current = make.get();
                if (current == null) {
                    stage = next;
                    return ActionStatus.progressed();
                }
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed broken) return finish(context.player(), Problem.of(broken.problem().kind(),
                    failed + "：" + broken.problem().message()));
            stage = next;
            return ActionStatus.progressed();
        }

        // 右键一下手里的过滤器：原版这一下就打开过滤器界面，不是吃东西那样的持续使用；界面开了就算成。
        // 原先按持续使用等"开始使用"，界面明明开了也判没打开，还把界面留着没关，人卡在界面里走不动。
        private ActionStatus open(PlayerContext player) {
            if (opening == null) {
                opening = player.interactionSender().useItem(player, InteractionHand.MAIN_HAND,
                        c -> compat.filterMenuOpen(c.localPlayer()) ? InteractionConfirmation.Verdict.APPLIED
                                : InteractionConfirmation.Verdict.PENDING, OPEN_TIMEOUT_TICKS);
                return ActionStatus.running();
            }
            if (!opening.terminal()) opening = player.interactionSender().poll(player, opening);
            if (!opening.terminal()) return ActionStatus.running();
            if (opening.status() != PendingInteraction.Status.CONFIRMED_APPLIED && !compat.filterMenuOpen(player.localPlayer())) {
                return finish(player, Problem.of(Problem.Kind.STUCK, "过滤器的界面没打开：" + opening.detail()));
            }
            stage = Stage.EDIT;
            return ActionStatus.progressed();
        }

        // 一刻一下；界面被关掉了就不再发，如实说。
        private ActionStatus edit(PlayerContext player) {
            if (!compat.filterMenuOpen(player.localPlayer())) return finish(player, Problem.of(Problem.Kind.STUCK, "过滤器的界面中途关了"));
            Step next = steps.poll();
            if (next == null) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            Optional<String> refused = next.run().get();
            if (refused.isPresent()) {
                problem = Problem.of(Problem.Kind.INVALID_PARAMETER, refused.get());
                steps.clear();
                stage = Stage.CLOSE;
            }
            return ActionStatus.progressed();
        }

        // 关上界面：Create 在关的时候把规则写进物品，关完才读回。
        private ActionStatus close(PlayerContext player) {
            if (closing == null) {
                closing = player.menuActions().close(player, CLOSE_TIMEOUT_TICKS);
                return ActionStatus.progressed();
            }
            closing = player.menuActions().poll(player, closing);
            if (!closing.terminal()) return ActionStatus.running();
            stage = Stage.FINISHED;
            return problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        }

        // 做不下去了：界面还开着就先关上再收场（关的时候 Create 照样把已改的写进物品），不把界面留给后面的事。
        private ActionStatus finish(PlayerContext player, Problem reason) {
            problem = reason;
            if (player != null && compat.filterMenuOpen(player.localPlayer())) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            stage = Stage.FINISHED;
            return ActionStatus.failed(reason);
        }

        @Override public void close() {
            if (current != null) current.close();
            // 中途被收掉（换活、被打断收尾）：过滤器界面还开着就交给原版关上，不留给后面的事。
            PlayerContext player = services.context().get();
            if (stage == Stage.FINISHED || closing != null || player == null || player.localPlayer() == null
                    || !compat.filterMenuOpen(player.localPlayer())) {
                return;
            }
            try {
                player.menuActions().closeForTaskBoundary(player, CLOSE_TIMEOUT_TICKS, "改过滤器收尾");
            } catch (RuntimeException notNow) {
                // 这一刻交不出关界面（不在本刻的交互窗口里）：换任务时的收尾还会再关一次。
            }
        }

        @Override public Interruptibility interruptibility() {
            return stage == Stage.EDIT || stage == Stage.CLOSE ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return switch (stage) {
                case HAND -> "把过滤器拿到主手";
                case OPEN -> "打开过滤器的界面";
                case EDIT -> steps.isEmpty() ? "改完了" : steps.peek().what();
                case CLOSE -> "关上过滤器的界面";
                case FINISHED -> "过滤器改完了";
            };
        }
    }
}
