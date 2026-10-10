// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookOperations;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookStatus;
import org.maiwithu.maicraft.ability.quest.spi.QuestView;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 机器类型、网络读取器、任务书操作这三个槽：存建法、进世界才建，建出来包一层；
 * 模组停用或接口对不上时如实回答"用不了"，交出的动作推进到一半停用时按不支持收场。
 */
class CompatSlotsTest {

    /** 这里的建法都不碰玩家行为：登记表只把它原样交给建法。 */
    private static final PlayerServices NO_SERVICES = null;
    private static final BlockPos AT = new BlockPos(1, 64, 1);

    /** 交什么由测试写在 contribute 里的联动入口。 */
    private abstract static class Contributing extends CompatModule {
        Contributing() {
            super("create", "机械动力");
        }
    }

    private static CompatRegistry load(CompatModule module) {
        return CompatRegistry.load(List.of(new SupportedMod<>("create", "机械动力",
                new VerifiedVersions("6.0.11", "6.1"), () -> module)), new FakeLoader().with("create", "6.0.11"));
    }

    // 让模组停用：经它的读写包装碰一下就对不上。
    private static void breakModule(CompatModule module) {
        assertThrows(ModApiMismatch.class, () -> module.call("读压机", () -> {
            throw new NoSuchMethodError("MechanicalPressBlockEntity.getRunningTicks");
        }));
    }

    @Test
    void 三个槽进世界才建_建出来包一层() {
        AtomicReference<PlayerServices> given = new AtomicReference<>();
        CompatRegistry registry = load(new Contributing() {
            @Override public void contribute(CompatRegistry registry) {
                registry.machineType(this, services -> new Press());
                registry.networkReader(this, services -> new Kinetic());
                registry.questBook(this, services -> {
                    given.set(services);
                    return new Book();
                });
            }
        });
        assertInstanceOf(CompatMachineType.class, registry.machineTypes(NO_SERVICES).getFirst());
        assertInstanceOf(CompatNetworkReader.class, registry.networkReaders(NO_SERVICES).getFirst());
        assertInstanceOf(CompatQuestBookOperations.class, registry.questBooks(NO_SERVICES).getFirst());
        assertEquals("kinetic", registry.networkReaders(NO_SERVICES).getFirst().kind().id());
        assertSame(NO_SERVICES, given.get(), "建法拿到的就是这次进世界的那一份玩家行为");
    }

    @Test
    void 交接到一半出错时新槽里交的也撤掉() {
        CompatRegistry registry = load(new Contributing() {
            @Override public void contribute(CompatRegistry registry) {
                registry.machineType(this, services -> new Press());
                registry.networkReader(this, services -> new Kinetic());
                throw new IllegalStateException("任务书读写端建不起来");
            }
        });
        assertTrue(registry.modules().isEmpty());
        assertTrue(registry.machineTypes(NO_SERVICES).isEmpty());
        assertTrue(registry.networkReaders(NO_SERVICES).isEmpty());
    }

    @Test
    void 停用后机器类型不再认领方块_读状态如实说读不到_动作给不出() {
        Contributing module = new Contributing() {
            @Override public void contribute(CompatRegistry registry) {
                registry.machineType(this, services -> new Press());
            }
        };
        MachineType press = load(module).machineTypes(NO_SERVICES).getFirst();
        assertTrue(press.covers(null), "停用前照常认领");
        breakModule(module);
        assertFalse(press.covers(null));
        MachineState state = press.state(AT);
        assertEquals(MachineState.Activity.UNKNOWN, state.activity());
        assertTrue(state.note().contains("机械动力的联动已停用"), state.note());
        assertTrue(press.feed(AT, "minecraft:iron_ingot", 1, Permissions.DEFAULT).isEmpty());
        assertEquals("create:mechanical_press", press.id(), "名字这类登记时就定下的照常回答");
    }

    @Test
    void 交出的动作推进到一半停用时按不支持收场() {
        Contributing module = new Contributing() {
            @Override public void contribute(CompatRegistry registry) {
                registry.machineType(this, services -> new Press());
            }
        };
        Action feeding = load(module).machineTypes(NO_SERVICES).getFirst()
                .feed(AT, "minecraft:iron_ingot", 1, Permissions.DEFAULT).orElseThrow();
        breakModule(module);
        ActionStatus status = feeding.tick(null);
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, status).problem();
        assertEquals(Problem.Kind.UNSUPPORTED, problem.kind());
    }

    @Test
    void 停用后网络读不到_任务书说用不了_发送答没发出去() {
        Contributing module = new Contributing() {
            @Override public void contribute(CompatRegistry registry) {
                registry.networkReader(this, services -> new Kinetic());
                registry.questBook(this, services -> new Book());
            }
        };
        CompatRegistry registry = load(module);
        NetworkReader kinetic = registry.networkReaders(NO_SERVICES).getFirst();
        QuestBookOperations book = registry.questBooks(NO_SERVICES).getFirst();
        assertTrue(book.submit("0000000000000001", "0000000000000002"), "停用前照常发");
        breakModule(module);
        assertTrue(kinetic.membership(AT).isEmpty());
        NetworkSummary summary = kinetic.summary("net-1");
        assertFalse(summary.readable());
        assertTrue(summary.note().contains("机械动力的联动已停用"), summary.note());
        assertFalse(book.status().usable());
        assertTrue(book.quest("0000000000000001").isEmpty());
        assertFalse(book.submit("0000000000000001", "0000000000000002"));
    }

    @Test
    void 读写时碰到接口对不上就收住_不往外抛() {
        // 读写端经联动入口碰模组时对不上：包装那一层收住，汇总如实说读不到并带上在做什么。
        final class Fragile extends Contributing {
            @Override public void contribute(CompatRegistry registry) {
                registry.networkReader(this, services -> new Kinetic() {
                    @Override public NetworkSummary summary(String networkId) {
                        return Fragile.this.call("读应力网络", () -> {
                            throw new NoClassDefFoundError("KineticNetwork");
                        });
                    }
                });
            }
        }
        NetworkSummary summary = load(new Fragile()).networkReaders(NO_SERVICES).getFirst().summary("net-1");
        assertFalse(summary.readable());
        assertTrue(summary.note().contains("读应力网络"), summary.note());
    }

    /** 压机替身：什么都认领，投料给一个一刻就做完的动作。 */
    private static class Press implements MachineType {
        @Override public String id() { return "create:mechanical_press"; }
        @Override public String name() { return "动力压机"; }
        @Override public String modId() { return "create"; }
        @Override public boolean covers(BlockState state) { return true; }
        @Override public MachineRole role() { return MachineRole.PROCESSING; }
        @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) { return List.of(); }
        @Override public MachineState state(BlockPos at) {
            return new MachineState(MachineState.Activity.RUNNING, 64.0, null, List.of(), Map.of(), "");
        }
        @Override public Optional<String> installationProblem(Installation installation) { return Optional.empty(); }
        @Override public Optional<String> partProblem(PartCell part, BlockState host) { return Optional.empty(); }
        @Override public Optional<Action> install(Installation installation, Permissions permissions) {
            return Optional.empty();
        }
        @Override public boolean installed(Installation installation) { return false; }
        @Override public Optional<Action> mount(PartCell part, Permissions permissions) { return Optional.empty(); }
        @Override public boolean mounted(PartCell part) { return false; }
        @Override public List<MachineSetting> settings(BlockPos at) { return List.of(); }
        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            return Optional.empty();
        }
        @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() { return "投料"; }
            });
        }
        @Override public List<MachineState.Shown> output(BlockPos at) { return List.of(); }
        @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
            return Optional.empty();
        }
    }

    /** 应力网络读取器替身：每格都在 net-1 里。 */
    private static class Kinetic implements NetworkReader {
        @Override public NetworkKind kind() { return new NetworkKind("kinetic", "应力网络"); }
        @Override public Optional<String> membership(BlockPos at) { return Optional.of("net-1"); }
        @Override public NetworkSummary summary(String networkId) {
            return new NetworkSummary(networkId, true, Map.of("capacity", 2048L), "");
        }
    }

    /** 任务书替身：能用，发什么都算发出去了。 */
    private static class Book implements QuestBookOperations {
        @Override public QuestBookStatus status() { return QuestBookStatus.ready(); }
        @Override public Optional<QuestView> quest(String questId) { return Optional.empty(); }
        @Override public boolean submit(String questId, String requirementId) { return true; }
        @Override public boolean confirm(String questId, String requirementId) { return true; }
        @Override public boolean claim(String questId, String rewardId, String choice) { return true; }
    }
}
