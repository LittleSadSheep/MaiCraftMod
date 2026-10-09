// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;

/**
 * 交易的来源：和村民换东西。交易还没有接入——问价一律如实回答不支持，
 * 让指名要买东西的请求当场知道"这条路还没通"，不空转也不假装去找村民。
 * 接入后这里补上找村民、走到跟前、按报价交换的问价与执行。
 */
public final class TradeSource implements ItemSource {

    @Override public String describe() {
        return "和村民交易";
    }

    @Override public AcquireVia via() {
        return AcquireVia.TRADE;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        return new SourceQuote.Unsupported(describe(), "交易还没接入，暂时买不了东西");
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        // 问价都不支持，不会被选中；真被选中说明程序写岔了，这里不给动作。
        return Optional.empty();
    }
}
