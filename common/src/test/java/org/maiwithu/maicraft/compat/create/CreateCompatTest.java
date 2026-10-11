// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.maiwithu.maicraft.kernel.task.Action;

/** 机械动力的联动入口：装了、版本对就交出目录里每种机器、兜底的两种与应力网读取器；认方块按方块对象比。 */
class CreateCompatTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 装了对的版本就交出全部机器类型与应力网读取器() {
        CompatRegistry registry = CompatRegistry.load(List.of(supported()), loaderWith("6.0.11"));
        List<MachineType> types = registry.machineTypes(offlineServices());
        assertEquals(CreateMachineCatalog.kinds().size() + 2, types.size());
        List<NetworkReader> readers = registry.networkReaders(offlineServices());
        assertEquals(1, readers.size());
        assertEquals("kinetic", readers.getFirst().kind().id());
        assertTrue(types.stream().anyMatch(type -> type.id().equals("create:mechanical_press")
                && type.role() == MachineRole.PROCESSING));
        // 石磨的产物留在肚子里、锯做好的东西掉在地上：出口读不到；压机下面的置物台、工作盆读得到。
        assertFalse(types.stream().filter(type -> type.id().equals("create:millstone")).findFirst().orElseThrow()
                .outputVisible(BlockPos.ZERO));
        assertFalse(types.stream().filter(type -> type.id().equals("create:mechanical_saw")).findFirst().orElseThrow()
                .outputVisible(BlockPos.ZERO));
        assertTrue(types.stream().filter(type -> type.id().equals("create:mechanical_press")).findFirst().orElseThrow()
                .outputVisible(BlockPos.ZERO));
    }

    @Test
    void 扳手_砂纸打磨与工具箱也交给登记表() {
        CompatRegistry registry = CompatRegistry.load(List.of(supported()), loaderWith("6.0.11"));
        assertEquals(1, registry.dismantleTools(offlineServices()).size());
        List<ItemSource> sources = registry.itemSources(offlineServices());
        assertEquals(List.of("sandpaper", "toolbox"), sources.stream().map(source -> source.via().name()).toList());
    }

    @Test
    void 砂纸报价按产物找配方_磨几次要几张砂纸写进风险() {
        CreateTools.Polishing quartz = new CreateTools.Polishing(List.of("create:rose_quartz"),
                "create:polished_rose_quartz", 1);
        assertTrue(SandpaperSource.recipeFor(WantedItem.ofItem("create:polished_rose_quartz"), List.of(quartz))
                .isPresent());
        assertTrue(SandpaperSource.recipeFor(WantedItem.ofItem("minecraft:stone"), List.of(quartz)).isEmpty());
        SourceQuote.Offer offer = (SourceQuote.Offer) SandpaperSource.offerFor(quartz, 10);
        assertEquals(10, offer.obtainableCount());
        assertTrue(offer.risk().contains("2 张砂纸"), offer.risk());
        assertEquals("create:rose_quartz", offer.hint());
        // 一次出两件的配方，要五件磨三次就够。
        assertEquals(3, SandpaperSource.roundsFor(new CreateTools.Polishing(List.of("a:b"), "a:c", 2), 5));
    }

    @Test
    void 机器资料挂到物品页_压机那一篇写着加工处在正下方第二格() {
        CompatRegistry registry = CompatRegistry.load(List.of(supported()), loaderWith("6.0.11"));
        KnowledgeSource knowledge = registry.knowledgeSources().getFirst();
        List<KnowledgeDocument.Entry> about = knowledge.entriesAbout("create:mechanical_press");
        assertEquals(1, about.size());
        KnowledgeDocument page = knowledge.read(about.getFirst().uri());
        assertTrue(page.text().contains("正下方第二格"), page.text());
        assertTrue(knowledge.read(CreateMachineKnowledge.INDEX).text().contains("链条输送机"));
        assertTrue(knowledge.entriesAbout("minecraft:stone").isEmpty());
    }

    @Test
    void 版本不在验证过的范围就什么都不交() {
        CompatRegistry registry = CompatRegistry.load(List.of(supported()), loaderWith("6.1.0"));
        assertTrue(registry.machineTypes(offlineServices()).isEmpty());
        assertTrue(registry.networkReaders(offlineServices()).isEmpty());
    }

    @Test
    void 目录里每种方块只出现在一行里() {
        Set<String> seen = new HashSet<>();
        for (CreateMachineCatalog.Kind kind : CreateMachineCatalog.kinds()) {
            assertTrue(kind.id().startsWith("create:"));
            for (String block : kind.blocks()) {
                assertTrue(seen.add(block), "方块出现在两行里：" + block);
            }
        }
    }

    @Test
    void 认方块按目录那一行的方块_兜底的只认动力方块() {
        CreateCompat compat = compat();
        CreateMachineCatalog.Kind stoneMill = new CreateMachineCatalog.Kind("create:test_mill", "测试磨",
                MachineRole.PROCESSING, CreateMachineCatalog.Work.TURNING, List.of("minecraft:stone"));
        Set<Block> blocks = CreateMachines.blocksOf(stoneMill.blocks());
        CreateMachineType listed = new CreateMachineType(stoneMill, compat, offlineServices(),
                state -> blocks.contains(state.getBlock()), new StationHandling(compat, offlineServices()),
                Optional.empty());
        assertTrue(listed.covers(Blocks.STONE.defaultBlockState()));
        assertFalse(listed.covers(Blocks.DIRT.defaultBlockState()));
        // 不在世界里时状态如实说读不到，不猜。
        assertEquals(MachineState.Activity.UNKNOWN, listed.state(new BlockPos(0, 64, 0)).activity());
        assertTrue(CreateMachines.blocksOf(List.of("create:no_such_block", "not a key")).isEmpty());
    }

    private static SupportedMod<CompatModule> supported() {
        return new SupportedMod<>(CreateCompat.MOD_ID, "机械动力", new VerifiedVersions("6.0.11", "6.1"),
                CreateCompatTest::compat);
    }

    /** 读写端的替身：哪一格都不是动力方块，什么都读不到；这里只看联动入口交了什么、怎么认方块。 */
    private static CreateCompat compat() {
        CreateKinetics kinetics = new CreateKinetics() {
            @Override public Optional<Reading> reading(ClientLevel level, BlockPos at) { return Optional.empty(); }
            @Override public boolean kinetic(BlockState state) { return false; }
            @Override public boolean generator(BlockState state) { return false; }
            @Override public Set<Direction> shaftFaces(ClientLevel level, BlockPos at, BlockState state) {
                return Set.of();
            }
            @Override public double stressImpact(Block block) { return 0; }
            @Override public double stressCapacity(Block block) { return 0; }
            @Override public Optional<Integer> generatedSpeed(Block block) { return Optional.empty(); }
            @Override public Optional<Double> minimumSpeed(BlockState state) { return Optional.empty(); }
        };
        CreateBelts belts = new CreateBelts() {
            @Override public Optional<Segment> segment(ClientLevel level, BlockPos at) { return Optional.empty(); }
            @Override public Optional<BlockPos> firstShaft(ItemStack held) { return Optional.empty(); }
            @Override public boolean canConnect(ClientLevel level, BlockPos first, BlockPos second) { return false; }
            @Override public int maxLength() { return 20; }
            @Override public boolean shaft(ClientLevel level, BlockPos at) { return false; }
        };
        CreateChainConveyors chains = new CreateChainConveyors() {
            @Override public Optional<Set<BlockPos>> connections(ClientLevel level, BlockPos at) {
                return Optional.empty();
            }
            @Override public int chainCost(BlockPos delta) { return 1; }
            @Override public int maxLength() { return 32; }
            @Override public int maxConnections() { return 4; }
        };
        CreateStations stations = new CreateStations() {
            @Override public Optional<List<ItemStack>> depotItems(ClientLevel level, BlockPos at) {
                return Optional.empty();
            }
            @Override public Optional<List<ItemStack>> basinItems(ClientLevel level, BlockPos at) {
                return Optional.empty();
            }
            @Override public Optional<List<ItemStack>> drainItems(ClientLevel level, BlockPos at) {
                return Optional.empty();
            }
            @Override public Optional<List<ItemStack>> beltItems(ClientLevel level, BlockPos at) {
                return Optional.empty();
            }
            @Override public Optional<StockSnapshot> stock(ClientLevel level, BlockPos at) { return Optional.empty(); }
            @Override public Optional<DeployerReading> deployer(ClientLevel level, BlockPos at) { return Optional.empty(); }
            @Override public List<FluidReading> fluids(ClientLevel level, BlockPos at) { return List.of(); }
            @Override public Optional<String> shownText(ClientLevel level, BlockPos at) { return Optional.empty(); }
            @Override public Optional<LinkReading> link(ClientLevel level, BlockPos at) { return Optional.empty(); }
            @Override public Optional<Boolean> moving(ClientLevel level, BlockPos at) { return Optional.empty(); }
            @Override public Optional<CrafterReading> crafter(ClientLevel level, BlockPos at) { return Optional.empty(); }
            @Override public List<ArmPoint> armPoints(ClientLevel level, BlockPos at) { return List.of(); }
            @Override public int armRange() { return 5; }
            @Override public boolean craftersConnected(ClientLevel level, BlockPos first, BlockPos second) { return false; }
            @Override public List<Direction> crafterLinkFaces(ClientLevel level, BlockPos at, Direction toward) {
                return List.of();
            }
        };
        CreateTools tools = new CreateTools() {
            @Override public boolean wrenchable(BlockState state) { return false; }
            @Override public List<Polishing> polishing(ClientLevel level) { return List.of(); }
            @Override public void glue(BlockPos from, BlockPos to) { }
            @Override public boolean glued(ClientLevel level, BlockPos from, BlockPos to) { return false; }
            @Override public boolean track(BlockState state) { return false; }
            @Override public Optional<BlockPos> trackStart(ItemStack held) { return Optional.empty(); }
            @Override public boolean trackConnected(ClientLevel level, BlockPos from, BlockPos to) { return false; }
            @Override public List<Toolbox> toolboxes(ClientLevel level, Player player) { return List.of(); }
            @Override public int toolboxRange() { return 10; }
            @Override public void equipFromToolbox(BlockPos box, int compartment, int hotbarSlot) { }
            @Override public Optional<PotatoAmmo> potatoAmmo(Player player, ItemStack ammo) { return Optional.empty(); }
        };
        CreateValues values = new CreateValues() {
            @Override public Boxes boxes(ClientLevel level, BlockPos at) { return new Boxes(List.of(), List.of()); }
            @Override public void sendValue(BlockPos at, int netId, Direction side, int row, int value) { }
        };
        CreateSchematics schematics = new CreateSchematics() {
            @Override public Optional<String> uploadProblem(String file) { return Optional.empty(); }
            @Override public void startUpload(String file) { }
            @Override public Optional<String> schematicFile(ItemStack stack) { return Optional.empty(); }
            @Override public Optional<Placement> placement(ItemStack stack) { return Optional.empty(); }
            @Override public void deploy(int slot, BlockPos anchor, int rotation) { }
            @Override public Optional<CannonReading> cannon(ClientLevel level, BlockPos at) { return Optional.empty(); }
            @Override public void pressCannon(String button) { }
            @Override public Optional<Float> uploading(ClientLevel level, BlockPos at) { return Optional.empty(); }
        };
        CreateFilters filters = new CreateFilters() {
            @Override public Optional<FilterRules> filter(ItemStack stack) { return Optional.empty(); }
            @Override public Optional<AttributeRules> attributeFilter(ItemStack stack) { return Optional.empty(); }
            @Override public boolean filterMenuOpen(LocalPlayer player) { return false; }
            @Override public void setFilterSlot(int slot, String itemId) { }
            @Override public void pressFilterButton(String button) { }
            @Override public Optional<List<String>> addAttribute(String sampleItem, String fragment, boolean inverted) {
                return Optional.empty();
            }
            @Override public void clearFilter() { }
        };
        return new CreateCompat(kinetics, belts, chains, stations, tools, values, schematics, filters);
    }

    /** 不在世界里的玩家行为：角色为空，靠近与交互都碰不到；只够建出机器类型、回答"读不到"。 */
    static PlayerServices offlineServices() {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), new NoGear(), inner -> inner, (request, permissions) -> null, nobodyOwns,
                itemId -> Set.of(), MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, emptyBackpack());
    }

    /** 背包视图的替身：身上什么都没有，Create 的测试用不到它。 */
    private static BackpackView emptyBackpack() {
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() { return List.of(); }
            @Override public int usedSlots() { return 0; }
            @Override public int totalSlots() { return 36; }
        };
    }

    private static LoaderEnvironment loaderWith(String version) {
        return new LoaderEnvironment() {
            @Override public String loaderName() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return modId.equals(CreateCompat.MOD_ID); }
            @Override public Optional<String> modVersion(String modId) {
                return isModLoaded(modId) ? Optional.of(version) : Optional.empty();
            }
            @Override public Path gameDirectory() { return Path.of("."); }
            @Override public Path configDirectory() { return Path.of("."); }
            @Override public boolean isDevelopment() { return true; }
            @Override public FurnaceFuels furnaceFuels() { return stack -> 0; }
        };
    }

    /** 换不了装备的替身。 */
    private static final class NoGear implements GearChanges {
        @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
            return Optional.empty();
        }

        @Override public Optional<Action> takeOff(GearSlotName slot) {
            return Optional.empty();
        }
    }
}
