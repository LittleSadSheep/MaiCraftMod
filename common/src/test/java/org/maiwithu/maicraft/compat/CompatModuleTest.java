// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.maiwithu.maicraft.kernel.task.Action;

/** 联动入口给读写端的调用包的那一层：模组接口对不上就停用，停用后登记的来源如实回答不支持。 */
class CompatModuleTest {

    private static final ItemRequest REQUEST = new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "测试");
    private static final SourceContext CONTEXT = new SourceContext(WorldPosition.here(0, 64, 0), Permissions.DEFAULT);
    private static final AcquireVia BACKPACK_ROUTE = new AcquireVia("backpack", "随身背包里拿");

    /** 不交任何接口的联动入口，只用它的调用包装。 */
    private static final class BareModule extends CompatModule {
        BareModule() {
            super("backpack", "精妙背包");
        }

        @Override public void contribute(CompatRegistry registry) {}
    }

    @Test
    void 读写端抛类对不上的错误时转成模组接口对不上并停用() {
        BareModule module = new BareModule();
        ModApiMismatch mismatch = assertThrows(ModApiMismatch.class,
                () -> module.call("认背包物品", () -> { throw new NoSuchMethodError("BackpackItem.isBackpack"); }));
        assertFalse(module.active());
        assertTrue(module.disabledReason().orElseThrow().contains("认背包物品"), module.disabledReason().orElseThrow());
        assertTrue(mismatch.getMessage().contains("精妙背包"), mismatch.getMessage());
        assertInstanceOf(NoSuchMethodError.class, mismatch.getCause());
    }

    @Test
    void 停用之后不再碰读写端() {
        BareModule module = new BareModule();
        assertThrows(ModApiMismatch.class, () -> module.run("认背包物品", () -> { throw new NoClassDefFoundError("BackpackItem"); }));
        AtomicInteger touched = new AtomicInteger();
        ModApiMismatch again = assertThrows(ModApiMismatch.class,
                () -> module.call("数空格", () -> touched.incrementAndGet()));
        assertEquals(0, touched.get(), "停用后读写端一下都不该再被调用");
        assertTrue(again.getMessage().contains("已停用"), again.getMessage());
        assertTrue(again.getMessage().contains("数空格"), again.getMessage());
    }

    @Test
    void 正常时原样返回读写端的结果() {
        BareModule module = new BareModule();
        assertEquals(3, module.call("数空格", () -> 3));
        assertTrue(module.active());
    }

    @Test
    void 停用后登记的来源问价回答不支持并写明原因() {
        BareModule module = new BareModule();
        assertThrows(ModApiMismatch.class, () -> module.run("认背包物品", () -> { throw new NoSuchMethodError("x"); }));
        AtomicInteger asked = new AtomicInteger();
        CompatItemSource wrapped = new CompatItemSource(module, offering("随身背包", asked));
        SourceQuote.Unsupported unsupported = assertInstanceOf(SourceQuote.Unsupported.class, wrapped.quote(REQUEST, CONTEXT));
        assertEquals("随身背包", unsupported.source());
        assertTrue(unsupported.reason().contains("精妙背包的联动已停用"), unsupported.reason());
        assertTrue(wrapped.begin(REQUEST, new SourceQuote.Offer("随身背包", 1, AcquisitionCost.free(), null), CONTEXT).isEmpty());
        assertEquals(0, asked.get(), "停用后不该再问里面的来源");
    }

    @Test
    void 来源自己碰到模组接口对不上也答不支持() {
        BareModule module = new BareModule();
        ItemSource breaking = new ItemSource() {
            @Override public String describe() { return "随身背包"; }
            @Override public AcquireVia via() { return BACKPACK_ROUTE; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return module.call("读背包内容", () -> { throw new NoSuchFieldError("inventory"); });
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return module.call("开背包", () -> { throw new NoSuchMethodError("open"); });
            }
        };
        CompatItemSource wrapped = new CompatItemSource(module, breaking);
        SourceQuote.Unsupported unsupported = assertInstanceOf(SourceQuote.Unsupported.class, wrapped.quote(REQUEST, CONTEXT));
        assertTrue(unsupported.reason().contains("读背包内容"), unsupported.reason());
        assertFalse(module.active());
        assertTrue(wrapped.begin(REQUEST, new SourceQuote.Offer("随身背包", 1, AcquisitionCost.free(), null), CONTEXT).isEmpty());
    }

    @Test
    void 没停用时来源的回答原样转交() {
        BareModule module = new BareModule();
        AtomicInteger asked = new AtomicInteger();
        CompatItemSource wrapped = new CompatItemSource(module, offering("随身背包", asked));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, wrapped.quote(REQUEST, CONTEXT));
        assertEquals(2, offer.obtainableCount());
        assertEquals(1, asked.get());
        assertEquals(BACKPACK_ROUTE, wrapped.via());
    }

    @Test
    void 停用后登记的知识来源目录为空状态写明原因() {
        BareModule module = new BareModule();
        KnowledgeSource inner = new KnowledgeSource() {
            @Override public List<KnowledgeDocument.Entry> entries() {
                return List.of(new KnowledgeDocument.Entry("maicraft://knowledge/x", "x", "条目", "说明", ""));
            }
            @Override public KnowledgeDocument read(String uri) {
                return new KnowledgeDocument(uri, "x", "条目", "说明", "正文");
            }
            @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
                return entries();
            }
            @Override public String status() { return "available"; }
        };
        CompatKnowledgeSource wrapped = new CompatKnowledgeSource(module, inner);
        assertEquals(1, wrapped.entries().size());
        assertEquals(1, wrapped.entriesAbout("create:mechanical_mixer").size());
        assertEquals("available", wrapped.status());
        assertThrows(ModApiMismatch.class, () -> module.run("读任务书", () -> { throw new NoSuchMethodError("quests"); }));
        assertTrue(wrapped.entries().isEmpty());
        assertNull(wrapped.read("maicraft://knowledge/x"));
        assertTrue(wrapped.entriesAbout("create:mechanical_mixer").isEmpty(), "停用后物品资料页也不再列它的条目");
        assertTrue(wrapped.status().contains("联动已停用"), wrapped.status());
    }

    /** 每次问价都报能给 2 件的来源，顺便数被问了几次。 */
    private static ItemSource offering(String name, AtomicInteger asked) {
        return new ItemSource() {
            @Override public String describe() { return name; }
            @Override public AcquireVia via() { return BACKPACK_ROUTE; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                asked.incrementAndGet();
                return new SourceQuote.Offer(name, 2, AcquisitionCost.free(), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                asked.incrementAndGet();
                return Optional.empty();
            }
        };
    }
}
