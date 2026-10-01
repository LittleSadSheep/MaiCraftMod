// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;

/** 等真实报价稳定后提交唯一一次附魔按钮；回执未确认时只观察服务端同步，任何异常都不能重新消费。 */
final class EnchantTransaction {
    private final LocalPlayer player;
    private final EnchantTaskRecord record;
    private final BooleanSupplier beforeSubmit;
    private EnchantmentQuote quote;
    private EnchantmentQuote.Offer offer;
    private MenuReceipt receipt;
    private long quoteSince, waitingSince = -1;
    private int levelBefore, lapisBefore, levelsSpent, lapisSpent;
    private boolean attempted, confirmed;
    private int notAppliedAttempts;
    private String nativeStatus;
    private ConfirmationEvidence candidate, evidence;

    /** 回执观察到完整成品和费用同刻吻合时保存副本；之后拾经验或换物品不能改写这次消费的历史证据。 */
    private record ConfirmationEvidence(ItemStack result, int levelsSpent, int lapisSpent) {
        private ConfirmationEvidence { result = result.copy(); }
    }

    EnchantTransaction(LocalPlayer player, EnchantTaskRecord record, BooleanSupplier beforeSubmit) {
        this.player = player; this.record = record; this.beforeSubmit = beforeSubmit;
    }

    boolean prepare(LocalPlayerContext context, EnchantmentMenu menu) {
        long now = context.tickRevision();
        if (waitingSince < 0) waitingSince = now;
        if (quote == null) {
            quote = EnchantmentQuote.capture(player, menu); quoteSince = now; return false;
        }
        if (!quote.stillMatches(player, menu)) {
            // 按钮尚未提交时，刷新服务端同步后的报价并重新等待稳定；档位与费用上限仍按原授权核对。
            quote = EnchantmentQuote.capture(player, menu); quoteSince = now;
        }
        if (now - quoteSince < 2) return false;
        if (!available() && now - waitingSince < 60) return false;
        offer = quote.requireChoice(record.offerTier, record.maxLevelsSpent, record.maxLapis);
        return true;
    }

    boolean submit(LocalPlayerContext context, EnchantmentMenu menu) {
        if (attempted) throw new IllegalStateException("enchantment_button_already_attempted");
        if (!prepare(context, menu)) return false;
        // 不可重复边界先异步落盘，等待期间继续显示真实报价；完成后再次核对，磁盘等待不能授权使用已经变化的报价。
        if (!beforeSubmit.getAsBoolean()) return false;
        if (!quote.stillMatches(player, menu)) return false;
        levelBefore = player.experienceLevel; lapisBefore = menu.getSlot(1).getItem().getCount();
        receipt = context.menus().pressButton(context, offer.tier() - 1, captureConfirmation(menu), 100);
        nativeStatus = receipt.status().name().toLowerCase(Locale.ROOT);
        attempted = true;
        return true;
    }

    private MenuConfirmation captureConfirmation(EnchantmentMenu menu) {
        var confirmation = quote.confirmation(offer);
        return (context, pending) -> {
            var verdict = confirmation.observe(context, pending);
            // 总任务暂停时菜单端口仍逐刻检查；把每次完整匹配的成本和成品保存为候选，最终是否成功仍由原生回执决定。
            candidate = verdict == MenuConfirmation.Verdict.APPLIED
                    ? new ConfirmationEvidence(menu.getSlot(0).getItem(), levelBefore - context.player().experienceLevel,
                            lapisBefore - menu.getSlot(1).getItem().getCount()) : null;
            return verdict;
        };
    }

    boolean poll(LocalPlayerContext context) {
        if (receipt == null) throw new IllegalStateException("enchantment_button_receipt_missing");
        receipt = context.menus().poll(context, receipt);
        if (!receipt.terminal()) return false;
        nativeStatus = receipt.status().name().toLowerCase(Locale.ROOT);
        if (receipt.status() == MenuReceipt.Status.CONFIRMED_NOT_APPLIED) {
            // 原生已证明本次按钮未执行，沿同一持久身份重读报价；有界恢复不重放未知或已消费的附魔。
            attempted = false; receipt = null; candidate = null; quote = null; offer = null;
            waitingSince = context.tickRevision();
            if (++notAppliedAttempts <= 2) return false;
            throw new IllegalStateException("enchantment_button_not_applied_after_refresh");
        }
        if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED)
            throw new IllegalStateException("enchantment_button_outcome_uncertain: " + receipt.detail());
        // 已成功的回执认领确认当刻的证据；恢复后的当前经验可能来自后来拾取，不能据此重新计算附魔成本。
        if (candidate == null || candidate.levelsSpent() != offer.levelsSpent() || candidate.lapisSpent() != offer.lapisCost())
            throw new IllegalStateException("enchantment_confirmation_evidence_missing");
        evidence = candidate; levelsSpent = evidence.levelsSpent(); lapisSpent = evidence.lapisSpent();
        confirmed = true; return true;
    }

    ItemStack confirmedResult() {
        if (!confirmed || evidence == null) throw new IllegalStateException("enchantment_result_not_confirmed");
        return evidence.result().copy();
    }

    boolean attempted() { return attempted; }
    boolean readyToRefresh() { return !attempted && notAppliedAttempts > 0; }
    boolean confirmed() { return confirmed; }
    int lapisSpent() { return lapisSpent; }

    Map<String, Object> data() {
        var data = new LinkedHashMap<String, Object>();
        data.put("button_attempted", attempted); data.put("enchantment_confirmed", confirmed);
        data.put("native_consumption_reserved", record.submissionReserved());
        data.put("not_applied_button_attempts", notAppliedAttempts);
        if (nativeStatus != null) data.put("native_button_status", nativeStatus);
        data.put("mechanical_retry_allowed", !attempted && !record.submissionReserved());
        if (quote != null) data.put("quote", quote.describe());
        if (offer != null) data.put("selected_offer", offer.describe());
        if (confirmed) {
            data.put("actual_levels_spent", levelsSpent); data.put("actual_lapis_spent", lapisSpent);
            data.put("player_level_before", levelBefore); data.put("player_level_after_enchantment", levelBefore - levelsSpent);
        }
        return data;
    }

    private boolean available() {
        var selected = quote.offers().get(record.offerTier - 1);
        return selected.requiredLevel() > 0 && !selected.clue().isEmpty() && selected.clueLevel() > 0;
    }
}
