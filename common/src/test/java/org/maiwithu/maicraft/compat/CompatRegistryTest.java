// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.kernel.task.Action;

/** 联动登记表按清单逐行检查：没装、版本不在范围、创建出错三种都不登记，也不影响别的模组。 */
class CompatRegistryTest {

    private static final VerifiedVersions TESTED = new VerifiedVersions("3.25.69", "3.26");
    private static final AcquireVia MOD_ROUTE = new AcquireVia("mod_route", "测试模组的途径");

    /** 交一个物品来源（或什么都不交）的联动入口替身。 */
    private static final class CountedModule extends CompatModule {
        private final ItemSource source;

        CountedModule(String modId, ItemSource source) {
            super(modId, "测试模组 " + modId);
            this.source = source;
        }

        @Override public void contribute(CompatRegistry registry) {
            if (source != null) {
                registry.itemSource(this, source);
            }
        }
    }

    /** 只有名字和途径的物品来源替身。 */
    private static ItemSource source(String name) {
        return new ItemSource() {
            @Override public String describe() { return name; }
            @Override public AcquireVia via() { return MOD_ROUTE; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return new SourceQuote.Unavailable(name, "测试");
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.empty();
            }
        };
    }

    /** 清单的一行，顺便数创建被调用了几次：没装、版本不对时创建不该被调用。 */
    private static SupportedMod supported(String modId, AtomicInteger created, ItemSource source) {
        return new SupportedMod(modId, "测试模组 " + modId, TESTED, () -> {
            created.incrementAndGet();
            return new CountedModule(modId, source);
        });
    }

    @Test
    void 没装的模组不登记也不创建() {
        AtomicInteger created = new AtomicInteger();
        CompatRegistry registry = CompatRegistry.load(List.of(supported("backpack", created, source("背包"))), new FakeLoader());
        assertEquals(0, created.get(), "没装就不能碰创建，引用模组类的代码不该被加载");
        assertTrue(registry.modules().isEmpty());
        assertTrue(registry.itemSources().isEmpty());
        assertEquals(List.of("测试模组 backpack（backpack）：没装，跳过"), registry.decisions());
    }

    @Test
    void 版本不在验证过的范围内不登记也不创建() {
        AtomicInteger created = new AtomicInteger();
        CompatRegistry registry = CompatRegistry.load(List.of(supported("backpack", created, source("背包"))),
                new FakeLoader().with("backpack", "3.26.9"));
        assertEquals(0, created.get());
        assertTrue(registry.modules().isEmpty());
        assertEquals("测试模组 backpack（backpack）：装的是 3.26.9，验证过的范围是 [3.25.69, 3.26)，不登记",
                registry.decisions().getFirst());
    }

    @Test
    void 版本在范围内的登记并把来源交上来() {
        AtomicInteger created = new AtomicInteger();
        CompatRegistry registry = CompatRegistry.load(List.of(supported("backpack", created, source("背包"))),
                new FakeLoader().with("backpack", "3.25.69"));
        assertEquals(1, created.get());
        assertEquals(1, registry.modules().size());
        assertEquals("backpack", registry.modules().getFirst().modId());
        // 交上来的来源被包了一层，名字和途径照旧，模组停用后由这一层回答不支持。
        assertInstanceOf(CompatItemSource.class, registry.itemSources().getFirst());
        assertEquals("背包", registry.itemSources().getFirst().describe());
        assertEquals(MOD_ROUTE, registry.itemSources().getFirst().via());
        assertEquals(List.of("测试模组 backpack（backpack）：已登记，版本 3.25.69"), registry.decisions());
    }

    @Test
    void 创建出错的不登记别的模组照常登记() {
        AtomicInteger created = new AtomicInteger();
        SupportedMod broken = new SupportedMod("broken", "坏模组", TESTED, () -> {
            throw new IllegalStateException("读写端建不起来");
        });
        SupportedMod linkage = new SupportedMod("linkage", "类对不上的模组", TESTED, () -> {
            throw new NoSuchMethodError("SomeMod.someMethod");
        });
        CompatRegistry registry = CompatRegistry.load(List.of(broken, linkage, supported("backpack", created, source("背包"))),
                new FakeLoader().with("broken", "3.25.69").with("linkage", "3.25.69").with("backpack", "3.25.69"));
        assertEquals(List.of("backpack"), registry.modules().stream().map(CompatModule::modId).toList());
        assertEquals(1, registry.itemSources().size());
        assertTrue(registry.decisions().get(0).contains("创建时出错，不登记"), registry.decisions().get(0));
        assertTrue(registry.decisions().get(1).contains("NoSuchMethodError"), registry.decisions().get(1));
    }

    @Test
    void 交接到一半出错不留下半个模组() {
        SupportedMod halfway = new SupportedMod("halfway", "交到一半的模组", TESTED, () -> new CompatModule("halfway", "交到一半的模组") {
            @Override public void contribute(CompatRegistry registry) {
                registry.itemSource(this, source("先交的来源"));
                throw new IllegalStateException("第二个接口建不起来");
            }
        });
        CompatRegistry registry = CompatRegistry.load(List.of(halfway), new FakeLoader().with("halfway", "3.25.69"));
        assertTrue(registry.modules().isEmpty());
        assertTrue(registry.itemSources().isEmpty(), "交了一半的来源要撤掉，不然引擎会问一个没登记成的模组");
    }

    @Test
    void 空登记表什么都没有() {
        CompatRegistry registry = CompatRegistry.empty();
        assertTrue(registry.modules().isEmpty());
        assertTrue(registry.itemSources().isEmpty());
        assertTrue(registry.carriedBackpacks().isEmpty());
        assertTrue(registry.knowledgeSources().isEmpty());
        assertTrue(registry.decisions().isEmpty());
    }
}
