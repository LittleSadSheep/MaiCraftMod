// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在库存请求器下单取货的一趟：走到库存管理员跟前 → 右键它打开请求界面（现货随界面同步过来）→
 * 下单（件数取还缺的与现货的小者，寄到取货口的地址）→ 关界面 → 走到取货口 → 等包裹（够了就取；一分钟没新包裹或满五分钟收场）
 * → 点开取货口，把下单后新到、装着要的东西的包裹快速移动进背包 → 主手拿着一件件右键拆开。
 * 下了单没到齐的写进问题里（"还在路上"），不重下单；拆包背包放不下就停，不往地上扔。
 */
final class StockOrdering implements Action {

    private enum Stage { APPROACH, OPEN, STOCK, ORDER, CLOSE, TO_PICKUP, WAIT, TAKE, UNPACK, FINISHED }

    private static final int SYNC_TICKS = 40;
    private static final int UNPACK_HOLD_TICKS = 10;

    private final CreateCompat compat;
    private final PlayerServices services;
    private final CreateLogistics.Ticker ticker;
    private final CreateLogistics.Pickup pickup;
    private final Predicate<String> wanted;
    private final int need;
    private final Permissions permissions;
    private Stage stage = Stage.APPROACH;
    private Action current;
    private ClientMenuChannel request;
    private ClientMenuOpening opening;
    private OpenedMenu pickupMenu;
    private List<ItemStack> baseline = List.of();
    private String orderedItem;
    private int ordered;
    /** 开了请求界面后等现货同步过来已经等了几刻；等包裹另有自己的计时。 */
    private int waited;
    /** 走到取货口才开始等包裹：从那一刻起算一分钟、五分钟，不和等现货同步的刻数混在一起。 */
    private ParcelWait parcelWait;
    private Problem problem;

    StockOrdering(CreateCompat compat, PlayerServices services, CreateLogistics.Ticker ticker, CreateLogistics.Pickup pickup,
            Predicate<String> wanted, int need, Permissions permissions) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.ticker = ticker;
        this.pickup = pickup;
        this.wanted = wanted;
        this.need = need;
        this.permissions = permissions;
    }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (player == null || player.localPlayer() == null || player.level() == null) return ActionStatus.running();
        return switch (stage) {
            case APPROACH -> step(context, () -> keeper(player).map(keeper -> services.approach().toward(
                    ApproachTarget.ofEntity(keeper.getBoundingBox()), permissions)).orElse(null),
                    Stage.OPEN, "走不到库存管理员跟前");
            case OPEN -> open(context, player);
            case STOCK -> stock(player);
            case ORDER -> order(player);
            case CLOSE -> closeRequest();
            case TO_PICKUP -> step(context, () -> services.approach().toward(ApproachTarget.ofBlock(pickup.pos()), permissions),
                    Stage.WAIT, "走不到取货口跟前");
            case WAIT -> await(context);
            case TAKE -> take(context, player);
            case UNPACK -> unpack(context, player);
            case FINISHED -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    // 一步要做就推进那个动作，不用做或做完进下一段。
    private ActionStatus step(TickContext context, Supplier<Action> make, Stage next, String failed) {
        if (current == null) {
            current = make.get();
            if (current == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "库存管理员不见了"));
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed broken) return finish(Problem.of(broken.problem().kind(),
                failed + "：" + broken.problem().message()));
        stage = next;
        return ActionStatus.progressed();
    }

    // 右键管理员开请求界面：界面编号变了、是请求界面才算开了；网络锁着时游戏不让开，如实交回。
    private ActionStatus open(TickContext context, PlayerContext player) {
        if (current == null) {
            Optional<Entity> keeper = keeper(player);
            if (keeper.isEmpty()) return finish(Problem.of(Problem.Kind.TARGET_GONE, "请求器旁边没有坐着的库存管理员"));
            current = services.interactions().useEntity(keeper.get(),
                    InteractionConfirmation.menuChanged(player.localPlayer().containerMenu.containerId));
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed broken) {
            return finish(Problem.of(Problem.Kind.REFUSED_BY_GAME, "右键库存管理员没打开请求界面（网络锁着时不让用）："
                    + broken.problem().message()));
        }
        request = ClientMenuChannel.claimCurrent(services.context());
        if (request == null || !compat.requestMenuOpen(player.localPlayer())) {
            return finish(Problem.of(Problem.Kind.REFUSED_BY_GAME, "打开的不是库存请求器的请求界面"));
        }
        waited = 0;
        stage = Stage.STOCK;
        return ActionStatus.progressed();
    }

    // 等现货随界面同步过来；同步好了挑要下的那一种：符合要求的里面现货最多的。
    private ActionStatus stock(PlayerContext player) {
        Optional<CreateLogistics.Ticker> now = tickerNow(player);
        if (now.isEmpty()) return closeWith(Problem.of(Problem.Kind.TARGET_GONE, "库存请求器不见了"));
        if (now.get().ticksSinceUpdate() > StockTickerSource.FRESH_TICKS / 5 && ++waited <= SYNC_TICKS) return ActionStatus.running();
        Optional<Map.Entry<String, Integer>> best = now.get().stock().entrySet().stream()
                .filter(entry -> wanted.test(entry.getKey()) && entry.getValue() > 0)
                .max(Map.Entry.comparingByValue());
        if (best.isEmpty()) return closeWith(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "物流网络的现货里没有要的东西"));
        orderedItem = best.get().getKey();
        ordered = Math.min(need, best.get().getValue());
        stage = Stage.ORDER;
        return ActionStatus.progressed();
    }

    // 下单：先记下取货口里已有的包裹（下单后新到的才算这一单），再照"发送"那样寄到取货口。
    private ActionStatus order(PlayerContext player) {
        if (!request.stillOpen()) return finish(Problem.of(Problem.Kind.STUCK, "请求界面中途关了，没下成单"));
        baseline = pickupNow(player).map(CreateLogistics.Pickup::packages).orElse(List.of());
        compat.order(ticker.pos(), Map.of(orderedItem, ordered), pickup.address());
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    private ActionStatus closeRequest() {
        if (request.stillOpen()) {
            request.requestClose();
            return ActionStatus.running();
        }
        if (problem != null) return finish(problem);
        stage = Stage.TO_PICKUP;
        return ActionStatus.progressed();
    }

    // 走到取货口后站着等包裹：够了去取；一分钟没新包裹或满五分钟收场，取已经到的；一件都没到如实交回，不重下单。
    // 新包裹算进展、期限以内每秒报一次，拿东西任务的一分钟判卡住不抢在这段期限前面（见 ParcelWait）。
    private ActionStatus await(TickContext context) {
        if (parcelWait == null) parcelWait = new ParcelWait(ordered, tick -> arrivedNow(tick.player()));
        ActionStatus status = parcelWait.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        if (arrived() == 0) return finish(Problem.of(Problem.Kind.STUCK, "下了 " + ordered + " 件 " + orderedItem
                + "，等到收场一件都没送到 " + pickup.address() + "（链条断了、地址没人收都会这样），没有重下单"));
        stage = Stage.TAKE;
        return ActionStatus.progressed();
    }

    // 取货口里下单后新到、装着要的东西的包裹，合计装着几件要的东西。
    private int arrivedNow(PlayerContext player) {
        List<ItemStack> packages = pickupNow(player).map(CreateLogistics.Pickup::packages).orElse(List.of());
        return newParcels(player).stream().mapToInt(slot -> contentsOf(packages.get(slot))).sum();
    }

    // 等包裹时到了几件要的东西；还没开始等是 0。
    private int arrived() {
        return parcelWait == null ? 0 : parcelWait.arrived();
    }

    // 点开取货口，把新到的包裹一件件快速移动进背包。
    private ActionStatus take(TickContext context, PlayerContext player) {
        if (pickupMenu == null) {
            if (opening == null) {
                opening = new ClientMenuOpening(pickup.pos(), permissions, services.menuLayouts(), services.approach(),
                        services.interactions(), services.context());
            }
            ActionStatus status = opening.tick(context);
            if (status instanceof ActionStatus.Failed broken) return finish(broken.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            pickupMenu = opening.opened().orElse(null);
            if (pickupMenu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "取货口的界面没打开"));
            return ActionStatus.progressed();
        }
        if (pickupMenu.busy()) return ActionStatus.running();
        Optional<MenuContent.Reading> reading = pickupMenu.reading();
        if (reading.isEmpty()) return ActionStatus.running();
        List<Integer> fresh = newParcels(player);
        if (fresh.isEmpty()) {
            if (current == null) current = pickupMenu.closing();
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            current = null;
            stage = Stage.UNPACK;
            return ActionStatus.progressed();
        }
        int slot = fresh.getFirst();
        if (slot >= reading.get().containerSlotIds().size()) return ActionStatus.running();
        pickupMenu.quickMove(reading.get().containerSlotIds().get(slot));
        return ActionStatus.progressed();
    }

    // 拆包：背包里寄到这个地址、装着要的东西的包裹，一件件拿到主手右键；背包放不下就停。
    private ActionStatus unpack(TickContext context, PlayerContext player) {
        if (current != null) {
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed broken) return finish(broken.problem());
            return ActionStatus.progressed();
        }
        LocalPlayer self = player.localPlayer();
        int slot = parcelSlot(self);
        if (slot < 0) return finish(arrived() >= ordered ? null : Problem.of(Problem.Kind.STUCK, "下了 " + ordered + " 件 "
                + orderedItem + "，到了 " + arrived() + " 件，其余还在路上，没有重下单"));
        int free = (int) self.getInventory().items.stream().filter(ItemStack::isEmpty).count();
        int stacks = compat.parcel(self.getInventory().items.get(slot)).map(parcel -> parcel.contents().size()).orElse(1);
        if (free < stacks) return finish(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "背包放不下包裹里的东西，没再拆"));
        if (slot != self.getInventory().selected) {
            if (slot >= 9) {
                current = new ClientMovesToMainhand(services.context()).actionToHotbar(slot, self.getInventory().selected,
                        "把包裹拿到主手").orElse(null);
            } else {
                current = services.toMainhand().moveToMainhand(BuiltInRegistries.ITEM
                        .getKey(self.getInventory().items.get(slot).getItem()).toString()).orElse(null);
            }
            return current == null ? finish(Problem.of(Problem.Kind.STUCK, "没能把包裹拿到主手")) : ActionStatus.progressed();
        }
        ItemStack before = self.getMainHandItem().copy();
        current = services.interactions().useHeldItem(InteractionHand.MAIN_HAND,
                c -> !ItemStack.matches(c.localPlayer().getMainHandItem(), before)
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING, UNPACK_HOLD_TICKS);
        return ActionStatus.progressed();
    }

    // 取货口里下单后新到、装着要的东西、寄到这个地址的包裹在哪几格。
    private List<Integer> newParcels(PlayerContext player) {
        List<Integer> slots = new ArrayList<>();
        Optional<CreateLogistics.Pickup> now = pickupNow(player);
        if (now.isEmpty()) return slots;
        List<ItemStack> packages = now.get().packages();
        for (int slot = 0; slot < packages.size(); slot++) {
            ItemStack stack = packages.get(slot);
            boolean old = slot < baseline.size() && ItemStack.matches(stack, baseline.get(slot));
            if (!old && ours(stack)) slots.add(slot);
        }
        return slots;
    }

    private int contentsOf(ItemStack stack) {
        return compat.parcel(stack).map(parcel -> parcel.contents().entrySet().stream()
                .filter(entry -> wanted.test(entry.getKey())).mapToInt(Map.Entry::getValue).sum()).orElse(0);
    }

    private boolean ours(ItemStack stack) {
        return !stack.isEmpty() && compat.parcel(stack).filter(parcel -> parcel.address().equals(pickup.address())).isPresent()
                && contentsOf(stack) > 0;
    }

    private int parcelSlot(LocalPlayer self) {
        var items = self.getInventory().items;
        if (ours(items.get(self.getInventory().selected))) return self.getInventory().selected;
        for (int slot = 0; slot < items.size(); slot++) if (ours(items.get(slot))) return slot;
        return -1;
    }

    private Optional<Entity> keeper(PlayerContext player) {
        return tickerNow(player).flatMap(now -> now.keeper().isPresent()
                ? Optional.ofNullable(player.level().getEntity(now.keeper().getAsInt())) : Optional.empty());
    }

    private Optional<CreateLogistics.Ticker> tickerNow(PlayerContext player) {
        return compat.tickers(player.level(), ticker.pos(), 0).stream().findFirst();
    }

    private Optional<CreateLogistics.Pickup> pickupNow(PlayerContext player) {
        return compat.pickups(player.level(), pickup.pos(), 0).stream().findFirst();
    }

    private ActionStatus closeWith(Problem reason) {
        problem = reason;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    private ActionStatus finish(Problem reason) {
        problem = reason;
        stage = Stage.FINISHED;
        return reason == null ? ActionStatus.done() : ActionStatus.failed(reason);
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    // 中途收场：请求界面、取货口界面还开着就请游戏关一次。
    @Override public void close() {
        if (current != null) current.close();
        if (request != null && request.stillOpen()) request.requestClose();
        if (pickupMenu != null && stage != Stage.FINISHED) pickupMenu.abandon();
    }

    @Override public Interruptibility interruptibility() {
        return stage == Stage.WAIT ? Interruptibility.BETWEEN_ACTIONS
                : current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return switch (stage) {
            case APPROACH -> "走到库存管理员跟前";
            case OPEN -> "右键库存管理员开请求界面";
            case STOCK -> "看请求器的现货";
            case ORDER, CLOSE -> "下单寄到 " + pickup.address();
            case TO_PICKUP -> "走到取货口";
            case WAIT -> parcelWait == null ? "等包裹（到了 0 / " + ordered + " 件）" : parcelWait.describe();
            case TAKE -> "从取货口拿包裹";
            case UNPACK -> "拆包裹";
            case FINISHED -> "库存请求器这一趟做完了";
        };
    }
}
