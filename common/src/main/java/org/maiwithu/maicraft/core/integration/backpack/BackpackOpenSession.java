// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.inventory.ItemComponentDiff;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;

/**
 * 拿起本人随身背包并原生右键打开，等待槽位同步；只关闭本会话确认的菜单，鼠标有余物则保留现场。
 * 核对出开错了包时：不在那只包里存取，先把鼠标物品放下 -> 原生关掉它 -> 以确定失败返回，由上层接着试下一只。
 */
public final class BackpackOpenSession {
    public enum Status { RUNNING, READY, CLOSED, FAILED }
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private LocalPlayer owner;
    private Object level;
    private InteractionHand hand;
    private int slot = -1;
    private long started = -1;
    private NativeActionReceipt opening;
    private MenuReceipt closing;
    private AbstractContainerMenu menu;
    private String failure;
    private boolean closed, uncertain;
    private final BackpackCarriers.Carrier requestedCarrier;
    private BackpackCarriers.Carrier carrier;
    private Origin origin;
    private ItemStack initiating = ItemStack.EMPTY;
    // 开错的那只包：菜单与其观察、核对说明、在途的鼠标放置与关闭回执，以及已确认放下的物品。
    private AbstractContainerMenu stray;
    private BackpackMenuAccess.Snapshot strayView;
    private String rejection;
    private MenuReceipt placing, dismissing;
    private Map<String, Object> placingFacts;
    private final List<Map<String, Object>> placed = new ArrayList<>();
    private String leftForNativeClose, placementUnsettled;
    private boolean wrongMenuClosed;

    /** 开包前记下的发起事实：服务端会采用的原生地址、背包种类、持久内容身份，以及同行其他背包的身份。 */
    record Origin(BackpackCarriers.Carrier address, Item item, String storage, Set<String> others) {
        Origin { others = Set.copyOf(others); }
    }

    public BackpackOpenSession() { this(-1); }
    /** 多只随身背包由调用方逐只观察；选定格失效后不擅自改开另一只包。 */
    public BackpackOpenSession(int requestedSlot) {
        this(requestedSlot == -1 ? null : BackpackCarriers.Carrier.vanilla(requestedSlot));
    }
    /** 扩展穿戴槽沿用模组给出的handler地址，不能把饰品槽编号拿去做主背包SWAP。 */
    public BackpackOpenSession(BackpackCarriers.Carrier requestedCarrier) { this.requestedCarrier = requestedCarrier; }

    public static int carriedSlot(LocalPlayer player) {
        // 主背包和副手都可走原生持物使用；穿戴栏与其他模组饰品栏不冒充主背包槽号。
        for (int slot = 0; slot < 36; slot++) if (BackpackMenuAccess.isBackpack(player.getInventory().getItem(slot))) return slot;
        return BackpackMenuAccess.isBackpack(player.getOffhandItem()) ? 40 : -1;
    }

    public Status open(LocalPlayerContext context) {
        if (failure != null) return Status.FAILED;
        if (closed) return Status.CLOSED;
        LocalPlayer player = context.player();
        if (stray != null) return dismiss(context);
        if (owner == null) {
            // 打开随身背包前先结清并退出旧页面，鼠标物品由原生返还，选定背包仍在本次会话内继续打开。
            if (!context.menus().ensureWorldVisible(context)) return Status.RUNNING;
            carrier = requestedCarrier;
            if (carrier == null) {
                var carriers = BackpackCarriers.observe(player);
                if (!carriers.complete()) return fail(carriers.problem(), false);
                if (carriers.entries().isEmpty()) return fail("no carried Sophisticated Backpack was observed", false);
                carrier = carriers.entries().getFirst().carrier();
            }
            if (!BackpackMenuAccess.isBackpack(BackpackCarriers.current(player, carrier))) return fail("the selected carried backpack slot changed", false);
            slot = carrier.vanillaSlot();
            owner = player; level = player.level(); started = player.level().getGameTime();
            hand = slot < 0 ? null : slot == 40 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        }
        if (player != owner || player.level() != level) return fail("backpack owner or world changed", opening != null);
        // 已提交的原生使用先结算；同步较慢只等待，不向同一只背包反复发右键。
        if (opening != null) {
            opening = context.actions().poll(context, opening);
            if (!opening.terminal()) return Status.RUNNING;
            if (opening.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED)
                return fail("native backpack open was not confirmed: " + opening.detail(), true);
            var read = BackpackMenuAccess.read(player);
            if ("awaiting_sync".equals(read.status()) && player.level().getGameTime() - started < 120) return Status.RUNNING;
            if (read.snapshot() == null) return fail("opened backpack is not observable: " + read.status(), false);
            // 按服务端下发的开包地址取回那一格，再核对种类与内容身份；打开标签、渲染清单等界面组件变化不代表换了包。
            String mismatch; ItemStack atAddress = ItemStack.EMPTY;
            try {
                atAddress = BackpackCarriers.current(player, origin.address());
                mismatch = mismatch(origin, BackpackMenuAccess.openedAddress(read.snapshot().menu()), read.snapshot().backpack(),
                        atAddress, BackpackMenuAccess::contentsIdentity);
            } catch (RuntimeException unverifiable) {
                // 读不到该格或菜单地址时只能按无法核对处理，异常没有说明文字也不能被当成核对通过。
                mismatch = "the carried backpack cannot be verified: " + unverifiable.getClass().getSimpleName();
            }
            if (mismatch == null) { menu = read.snapshot().menu(); return Status.READY; }
            // 开错或认不出的包一律不在里面存取；附上三份副本的组件差异作证据，再放下鼠标物品并关掉它。
            rejection = mismatch + "; menu_vs_carried_components=" + ItemComponentDiff.changed(read.snapshot().backpack(), atAddress)
                    + "; carried_vs_initiating_components=" + ItemComponentDiff.changed(atAddress, initiating);
            stray = read.snapshot().menu(); strayView = read.snapshot();
            return dismiss(context);
        }
        if (player.level().getGameTime() - started >= 120) return fail("backpack preparation timed out", selection.pending());
        if (hand == InteractionHand.MAIN_HAND) {
            var status = selection.select(player, slot);
            if (status == FirstPersonActionGate.Status.FAILED) return fail(selection.failure(), selection.pending());
            if (status != FirstPersonActionGate.Status.READY) return Status.RUNNING;
        }
        if (!context.mutationAvailable()) return Status.RUNNING;
        if (hand == null) {
            try {
                // 穿戴包的原生地址就是本次选定的handler格；提交前记下它和包内身份，开包后据此核对。
                initiating = BackpackCarriers.current(player, carrier).copy();
                origin = origin(player, carrier, initiating);
                opening = BackpackCarriers.openWorn(context, carrier);
            }
            catch (RuntimeException unavailable) { return fail(unavailable.getMessage(), false); }
            return Status.RUNNING;
        }
        if (!BackpackMenuAccess.isBackpack(player.getItemInHand(hand))) return fail("selected backpack changed before native use", false);
        // 持物右键时服务端按主手当前选中格或副手开包；提交前记下这一地址与手中包的身份，不另换开包方式。
        initiating = player.getItemInHand(hand).copy();
        origin = origin(player, hand == InteractionHand.OFF_HAND ? BackpackCarriers.Carrier.vanilla(40)
                : new BackpackCarriers.Carrier("main", "", player.getInventory().selected), initiating);
        opening = context.actions().useItem(context, hand, NativeConfirmation.menuChanged(player.containerMenu.containerId), 60);
        return Status.RUNNING;
    }

    public Status close(LocalPlayerContext context) {
        if (closed) return Status.CLOSED;
        if (context.player() != owner || owner.level() != level || menu == null)
            return fail("backpack menu ownership changed before close", true);
        // 发出关闭后玩家菜单会先切回背包，再收到关闭回执；必须先结算这笔关闭，不能误判成外来换界面。
        if (closing != null) {
            closing = context.menus().poll(context, closing);
            if (!closing.terminal()) return Status.RUNNING;
            if (closing.status() != MenuReceipt.Status.CONFIRMED_APPLIED || owner.containerMenu != owner.inventoryMenu
                    || context.minecraft().screen != null) return fail("backpack close was not confirmed", true);
            closed = true; selection.reset(); return Status.CLOSED;
        }
        if (owner.containerMenu != menu) return fail("backpack menu was replaced before closing", true);
        // 等上一笔搬运结清后让原生关包处理余物；背包是否取足仍由各笔真实转移回执决定。
        if (context.menus().hasPendingTransaction()) return Status.RUNNING;
        if (!context.mutationAvailable()) return Status.RUNNING;
        closing = context.menus().close(context, 40); return Status.RUNNING;
    }

    public void cancel(LocalPlayerContext context) {
        // 取消时先结清已提交的开包，再仅收拾本会话的空鼠标菜单；外来界面和未结余物原样保留。
        if (owner == null) return;
        if (context.player() != owner || owner.level() != level) { uncertain = true; return; }
        if (opening != null && !opening.terminal()) {
            opening = context.actions().poll(context, opening);
            if (!opening.terminal()) {
                uncertain = true;
                if (context.mutationAvailable() && opening.kind() == NativeActionReceipt.Kind.USE_ITEM)
                    opening = context.actions().releaseUsingItem(context, opening);
                else if (opening.kind() == NativeActionReceipt.Kind.MOD_PROTOCOL)
                    opening = context.actions().retireOneShotForTaskBoundary(context, opening, "worn backpack open cancelled");
            }
            uncertain |= opening.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED;
        }
        // 开错的包尚未关掉时同样只收拾空鼠标的菜单；鼠标放置还在途则不关，保留现场并记为未知。
        AbstractContainerMenu own = menu != null ? menu : stray;
        if (placing != null && !placing.terminal()) uncertain = true;
        else if (context.player() == owner && owner.level() == level && own != null && owner.containerMenu == own && own.getCarried().isEmpty())
            context.menus().closeForTaskBoundary(context, 40, "backpack access cancelled");
        else if (owner != null && owner.containerMenu == owner.inventoryMenu) selection.reset();
        else if (owner != null) uncertain = true;
    }
    private Status dismiss(LocalPlayerContext context) {
        // 开错的包：结清在途放置 -> 鼠标物品逐次放到落点 -> 原生关包 -> 确认回到世界后以确定失败返回；全程不在这只包里取存材料。
        if (context.player() != owner || owner.level() != level) return fail(rejected() + "; owner or world changed before the wrong backpack closed", true);
        if (dismissing != null) {
            dismissing = context.menus().poll(context, dismissing);
            if (!dismissing.terminal()) return Status.RUNNING;
            if (dismissing.status() != MenuReceipt.Status.CONFIRMED_APPLIED || owner.containerMenu != owner.inventoryMenu
                    || context.minecraft().screen != null) return fail(rejected() + "; closing the wrong backpack was not confirmed", true);
            wrongMenuClosed = true; selection.reset(); return fail(rejected(), false);
        }
        if (placing != null) {
            placing = context.menus().poll(context, placing);
            if (!placing.terminal()) return Status.RUNNING;
            if (placing.status() == MenuReceipt.Status.CONFIRMED_APPLIED) placed.add(placingFacts);
            else {
                // 放置没被确认：不再点第二次，直接原生关包让原版返还鼠标物品，避免停在开错的包里；明确未生效才不算未知。
                placementUnsettled = placing.status().name().toLowerCase(Locale.ROOT) + ": " + placing.detail();
                uncertain |= placing.status() != MenuReceipt.Status.CONFIRMED_NOT_APPLIED;
            }
            placing = null; placingFacts = null;
        }
        if (owner.containerMenu != stray) return fail(rejected() + "; the wrong backpack menu was replaced before closing", true);
        if (context.menus().hasPendingTransaction() || !context.mutationAvailable()) return Status.RUNNING;
        ItemStack carried = stray.getCarried();
        // 每次放置都要等回执；整叠放不下时最多再找几次落点，避免在同一只包上无限点击。
        int target = carried.isEmpty() || placed.size() >= 8 || placementUnsettled != null ? -1 : cursorTarget(stray, strayView, carried);
        if (target >= 0) {
            if (!context.menus().ensureVisible(context)) return Status.RUNNING;
            ItemStack before = stray.getSlot(target).getItem().copy();
            placingFacts = Map.of("menu_slot", target, "slot_role", strayView.playerSlots().containsValue(target) ? "player_inventory" : "backpack_storage",
                    "item_id", String.valueOf(BuiltInRegistries.ITEM.getKey(carried.getItem())), "cursor_count", carried.getCount());
            placing = context.menus().click(context, target, 0, ClickType.PICKUP, placedOn(target, carried, before), 20);
            return Status.RUNNING;
        }
        // 鼠标已空，或主背包与这只包都没有落点：交给原生关闭，余物由原版放回背包、实在放不下才落地，并如实记下。
        if (!carried.isEmpty()) leftForNativeClose = BuiltInRegistries.ITEM.getKey(carried.getItem()) + "x" + carried.getCount();
        dismissing = context.menus().close(context, 40);
        return Status.RUNNING;
    }
    private String rejected() { return "opened menu does not match the initiating carried backpack: " + rejection; }

    /** 开错的包里给鼠标物品找落点：先整叠并入主背包同类、再放主背包空格、再部分并入，最后放这只包的空存储格；都没有返回 -1。 */
    static int cursorTarget(AbstractContainerMenu menu, BackpackMenuAccess.Snapshot view, ItemStack carried) {
        int empty = -1, partial = -1;
        for (int inventory = 0; inventory < 36; inventory++) {
            Integer index = view.playerSlots().get(inventory);
            if (index == null) continue;
            var slot = menu.getSlot(index); ItemStack stack = slot.getItem();
            if (stack.isEmpty()) { if (empty < 0 && slot.mayPlace(carried)) empty = index; continue; }
            if (!ItemStack.isSameItemSameComponents(stack, carried)) continue;
            int room = slot.getMaxStackSize(carried) - stack.getCount();
            if (room >= carried.getCount()) return index;
            if (room > 0 && partial < 0) partial = index;
        }
        if (empty >= 0) return empty;
        if (partial >= 0) return partial;
        for (int index : view.storageSlots()) {
            var slot = menu.getSlot(index);
            if (slot.getItem().isEmpty() && slot.mayPlace(carried)) return index;
        }
        return -1;
    }
    // 左键放下后鼠标须减少、落点按同样数量增加且仍是同一物品；什么都没变就继续等，出现第三种情况视为分歧。
    private static MenuConfirmation placedOn(int index, ItemStack carried, ItemStack before) {
        ItemStack cursorBefore = carried.copy(), slotBefore = before.copy();
        return (context, receipt) -> {
            var current = context.player().containerMenu;
            if (current.containerId != receipt.containerId()) return MenuConfirmation.Verdict.DIVERGED;
            ItemStack cursor = current.getCarried(), slot = current.getSlot(index).getItem();
            int moved = cursorBefore.getCount() - (cursor.isEmpty() ? 0 : cursor.getCount());
            if (moved == 0 && ItemStack.matches(slot, slotBefore)) return MenuConfirmation.Verdict.PENDING;
            boolean sameCursor = cursor.isEmpty() || ItemStack.isSameItemSameComponents(cursor, cursorBefore);
            boolean landed = ItemStack.isSameItemSameComponents(slot, cursorBefore) && slot.getCount() == slotBefore.getCount() + moved;
            return moved > 0 && sameCursor && landed ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.DIVERGED;
        };
    }
    private static Origin origin(LocalPlayer player, BackpackCarriers.Carrier address, ItemStack initiating) {
        // 发起时顺带记下同行其他包的内容身份；新包首次打开才分到身份时，用它排除误开了另一只。
        String storage = BackpackMenuAccess.contentsIdentity(initiating);
        var others = new LinkedHashSet<String>();
        for (var entry : BackpackCarriers.observe(player).entries()) {
            String id = BackpackMenuAccess.contentsIdentity(entry.stack());
            if (id != null && !id.equals(storage)) others.add(id);
        }
        return new Origin(address, initiating.getItem(), storage, others);
    }
    /**
     * 判断打开的菜单是否就是发起的那只包：服务端下发的原生地址、背包种类和持久内容身份都要对上，返回 null 表示核对通过。
     * 精妙会在开包前后原地改写打开标签、排序与渲染清单等界面组件，菜单里的物品副本也会与该格脱钩，所以不比较全部组件。
     */
    static String mismatch(Origin origin, BackpackCarriers.Carrier opened, ItemStack menuBackpack, ItemStack atAddress,
            Function<ItemStack, String> identity) {
        if (opened == null) return "native menu address is unavailable";
        if (!opened.equals(origin.address())) return "native menu opened " + opened.key() + " instead of " + origin.address().key();
        if (!menuBackpack.is(origin.item()) || !atAddress.is(origin.item()))
            return "opened backpack item differs from the initiating " + BuiltInRegistries.ITEM.getKey(origin.item());
        String menuStorage = identity.apply(menuBackpack), heldStorage = identity.apply(atAddress);
        if (origin.storage() != null) {
            // 已有内容身份的包，菜单与该格必须都是同一身份；同型号的两只包也只能靠它区分。
            if (origin.storage().equals(menuStorage) && origin.storage().equals(heldStorage)) return null;
            return "opened storage " + menuStorage + " with carried " + heldStorage + " differs from the initiating " + origin.storage();
        }
        // 首次打开的新包由服务端补发身份；此时只要求它不是同行另一只包的身份，且菜单与该格互不矛盾。
        if (menuStorage != null && origin.others().contains(menuStorage) || heldStorage != null && origin.others().contains(heldStorage))
            return "opened storage belongs to another carried backpack";
        if (menuStorage != null && heldStorage != null && !menuStorage.equals(heldStorage))
            return "opened storage " + menuStorage + " differs from the carried slot " + heldStorage;
        return null;
    }
    private Status fail(String reason, boolean unknown) { failure = reason; uncertain |= unknown; return Status.FAILED; }
    public String failure() { return failure; }
    public boolean uncertain() { return uncertain; }
    /** 开错的包已经放好鼠标物品并原生关闭，这只包没有任何存取；上层可据此接着试下一只。 */
    public boolean wrongMenuClosed() { return wrongMenuClosed; }
    /** 处理开错的包时实际发生的鼠标放置与原生关闭返还，供回执如实列出。 */
    public Map<String, Object> settlement() {
        if (stray == null) return Map.of();
        var facts = new LinkedHashMap<String, Object>();
        facts.put("wrong_menu_closed", wrongMenuClosed); facts.put("cursor_placements", List.copyOf(placed));
        if (leftForNativeClose != null) facts.put("cursor_left_for_native_close", leftForNativeClose);
        if (placementUnsettled != null) facts.put("cursor_placement_unconfirmed", placementUnsettled);
        return facts;
    }
    public AbstractContainerMenu menu() { return menu; }
}
