// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceServices;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;
import org.maiwithu.maicraft.kernel.task.Action;

/** 联动登记表按清单逐行检查：没装、版本不在范围、创建出错三种都不登记，也不影响别的模组。 */
class CompatRegistryTest {

    private static final VerifiedVersions TESTED = new VerifiedVersions("3.25.69", "3.26");
    private static final AcquireVia MOD_ROUTE = new AcquireVia("mod_route", "测试模组的途径");
    /** 这里的来源建法都不碰玩家行为：登记表只把它原样交给建法。 */
    private static final SourceServices NO_SERVICES = null;

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
        assertTrue(registry.itemSources(NO_SERVICES).isEmpty());
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
        assertInstanceOf(CompatItemSource.class, registry.itemSources(NO_SERVICES).getFirst());
        assertEquals("背包", registry.itemSources(NO_SERVICES).getFirst().describe());
        assertEquals(MOD_ROUTE, registry.itemSources(NO_SERVICES).getFirst().via());
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
        assertEquals(1, registry.itemSources(NO_SERVICES).size());
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
        assertTrue(registry.itemSources(NO_SERVICES).isEmpty(), "交了一半的来源要撤掉，不然引擎会问一个没登记成的模组");
    }

    @Test
    void 空登记表什么都没有() {
        CompatRegistry registry = CompatRegistry.empty();
        assertTrue(registry.modules().isEmpty());
        assertTrue(registry.itemSources(NO_SERVICES).isEmpty());
        assertTrue(registry.carriedBackpacks().isEmpty());
        assertTrue(registry.knowledgeSources().isEmpty());
        assertTrue(registry.decisions().isEmpty());
    }

    /** 联动入口替身：交什么由测试写在 contribute 里。 */
    private abstract static class Contributing extends CompatModule {
        Contributing(String modId) {
            super(modId, "测试模组 " + modId);
        }
    }

    private static CompatRegistry loadOne(String modId, CompatModule module) {
        return CompatRegistry.load(List.of(new SupportedMod(modId, "测试模组 " + modId, TESTED, () -> module)),
                new FakeLoader().with(modId, "3.25.69"));
    }

    // 终端那样的证明：角色侧 36 格，容器侧为空。
    private static MenuLayoutProof terminal(String type) {
        return new MenuLayoutProof() {
            @Override public Set<String> menuTypes() { return Set.of(type); }
            @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots,
                    List<Integer> otherSlots) {
                return new MenuLayout.Supported(playerSlots, List.of(), Set.of());
            }
        };
    }

    private static MenuSlots slots(String typeId) {
        return new MenuSlots() {
            @Override public String menuTypeId() { return typeId; }
            @Override public int slotCount() { return 40; }
            @Override public boolean playerBacked(int slot) { return slot >= 4; }
        };
    }

    @Test
    void 进世界时建来源_建法出错只少这一个() {
        // 进世界才建：建法拿到玩家行为；一个建法读写端出错，另一个照常建出来。
        CompatRegistry registry = loadOne("ae", new Contributing("ae") {
            @Override public void contribute(CompatRegistry registry) {
                registry.itemSource(this, services -> source("终端"));
                registry.itemSource(this, services -> {
                    throw new IllegalStateException("读写端出错");
                });
            }
        });
        assertEquals(List.of("终端"), registry.itemSources(NO_SERVICES).stream().map(ItemSource::describe).toList());
    }

    @Test
    void 建来源时模组接口对不上就停用这个模组() {
        Contributing module = new Contributing("ae") {
            @Override public void contribute(CompatRegistry registry) {
                registry.itemSource(this, services -> {
                    throw new NoSuchMethodError("Terminal.open");
                });
            }
        };
        CompatRegistry registry = loadOne("ae", module);
        assertTrue(registry.itemSources(NO_SERVICES).isEmpty());
        assertFalse(module.active(), "建法碰到 LinkageError 时整个模组的联动停用");
    }

    @Test
    void 登记的界面布局证明一起判_停用后证明不了() {
        Contributing module = new Contributing("ae") {
            @Override public void contribute(CompatRegistry registry) {
                registry.menuLayout(this, terminal("ae2:item_terminal"));
            }
        };
        CompatRegistry registry = loadOne("ae", module);
        assertInstanceOf(MenuLayout.Supported.class, registry.menuLayouts().classify(slots("ae2:item_terminal")));
        // 停用后不再照证明点格子，如实说证明不了。
        assertThrows(ModApiMismatch.class, () -> module.call("取一件", () -> {
            throw new NoSuchMethodError("Terminal.extract");
        }));
        assertInstanceOf(MenuLayout.Unsupported.class, registry.menuLayouts().classify(slots("ae2:item_terminal")));
    }

    @Test
    void 证明想改写原版界面的模组不登记() {
        CompatRegistry registry = loadOne("bad", new Contributing("bad") {
            @Override public void contribute(CompatRegistry registry) {
                registry.menuLayout(this, terminal("minecraft:generic_9x3"));
            }
        });
        assertTrue(registry.modules().isEmpty());
        assertTrue(registry.decisions().getFirst().contains("创建时出错，不登记"), registry.decisions().getFirst());
        assertInstanceOf(MenuLayout.Supported.class, registry.menuLayouts().classify(slots("minecraft:generic_9x3")),
                "撤掉的证明不留在登记表里，原版界面照旧");
    }
}
