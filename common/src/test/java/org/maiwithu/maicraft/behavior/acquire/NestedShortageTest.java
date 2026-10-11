// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 备料一层套一层都没拿到时的说明：实机里要一张床，十二种床、各色羊毛、白色羊毛、线一层层都弄不来，
 * 失败说明写成了两万八千字，同一句"问各来源"重复几十遍。这里搭同样形状的配方，核对：
 * 每样东西只讲一次、同样卡住的几样合成一组；这一次里已经确认拿不到的不再问一遍；
 * 一样原料一件都没有时不去弄其余原料。
 */
class NestedShortageTest {

    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue"};

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();
    /** 各来源被问到的东西，按先后：核对已经确认拿不到的不再问一遍、不该碰的原料没去碰。 */
    private final List<String> asked = new ArrayList<>();

    /** 床的配方形状照原版：床要三块同色羊毛加三块木板；彩色羊毛是白色羊毛加染料；白色羊毛是四根线；染料从花来。 */
    private List<WorkstationRecipe> recipes() {
        List<WorkstationRecipe> all = new ArrayList<>();
        for (String color : COLORS) {
            tags.put("minecraft:" + color + "_bed", "minecraft:beds");
            all.add(recipe(color + "_bed", 1, color + "_wool", 3, "#minecraft:planks", 3));
            if (!color.equals("white")) {
                all.add(recipe(color + "_wool", 1, color + "_dye", 1, "white_wool", 1));
                all.add(recipe(color + "_dye", 1, color + "_flower", 1));
            }
        }
        all.add(recipe("white_wool", 1, "string", 4));
        tags.put("minecraft:oak_planks", "minecraft:planks");
        all.add(recipe("oak_planks", 4, "oak_log", 1));
        return all;
    }

    private static WorkstationRecipe recipe(String result, int count, Object... ingredients) {
        List<WorkstationRecipe.IngredientStack> stacks = new ArrayList<>();
        for (int i = 0; i < ingredients.length; i += 2) {
            String id = (String) ingredients[i];
            WantedItem item = id.startsWith("#") ? WantedItem.ofTag(id.substring(1)) : WantedItem.ofItem("minecraft:" + id);
            stacks.add(new WorkstationRecipe.IngredientStack(item, (Integer) ingredients[i + 1]));
        }
        return new WorkstationRecipe("minecraft:" + result, WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:" + result), count, stacks, true);
    }

    /**
     * 只认得两样的世界：原木挖得到（挖一次进一根），花挖得到（附近开着花），别的东西哪个来源都给不了。
     * 每个来源给不了时说一句带物品名的原因，和真实来源一样。
     */
    private final class World implements ItemSource {
        private final String name;
        private final AcquireVia via;

        World(String name, AcquireVia via) {
            this.name = name;
            this.via = via;
        }

        @Override public String describe() {
            return name;
        }

        @Override public AcquireVia via() {
            return via;
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            String item = request.wanted().specifier();
            asked.add(name + " " + item);
            boolean minable = item.equals("minecraft:oak_log") || item.endsWith("_flower");
            if (via == AcquireVia.MINE && minable) {
                return new SourceQuote.Offer(name, request.count(), new AcquisitionCost(5, 2), null);
            }
            return new SourceQuote.Unavailable(name, "附近没有" + request.wanted().describe() + "可拿");
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext tick) {
                    backpack.add(request.wanted().specifier(), request.count());
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "挖" + request.wanted().describe();
                }
            });
        }
    }

    /** 替身：在背包合成格里做，扣原料、加成品。 */
    private final class Crafts implements RecipeRuns {
        @Override public Optional<Action> run(WorkstationRecipe recipe, WorldPosition station, int times,
                Permissions permissions, TaskRecords records) {
            return runInInventory(recipe, times, records);
        }

        @Override public boolean craftsInInventory() {
            return true;
        }

        @Override public Optional<Action> runInInventory(WorkstationRecipe recipe, int times, TaskRecords records) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
                        String id = ingredient.item().isTag() ? "minecraft:oak_planks" : ingredient.item().itemId();
                        backpack.take(id, ingredient.count() * times);
                    }
                    backpack.add(recipe.result().itemId(), recipe.resultCount() * times);
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "做" + recipe.result().describe();
                }
            });
        }
    }

    private ItemAcquisition engine() {
        List<WorkstationRecipe> recipes = recipes();
        ItemAcquisition[] engine = new ItemAcquisition[1];
        ItemNeeds needs = new ItemNeeds() {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                return engine[0].actionFor(request, permissions);
            }

            @Override public boolean knownUnobtainable(WantedItem wanted) {
                return engine[0].knownUnobtainable(wanted);
            }
        };
        ReadsRecipes reads = wanted -> recipes.stream()
                .filter(recipe -> wanted.matches(recipe.result().itemId(), tags.tagsOf(recipe.result().itemId())))
                .toList();
        RecipeSource crafting = new RecipeSource(reads, new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")),
                "world-1"), itemId -> 0, new Crafts(), backpack, offhand, tags, needs);
        engine[0] = new ItemAcquisition(List.of(new World("记得的容器", AcquireVia.CONTAINER),
                new World("采掘", AcquireVia.MINE), new World("狩猎", AcquireVia.HUNT), crafting),
                backpack, offhand, tags, new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        return engine[0];
    }

    private ActionStatus.Failed bedFails() {
        Action need = engine().need(new ItemRequest(WantedItem.ofTag("minecraft:beds"), 1, "备今晚的床"), Permissions.DEFAULT);
        for (int tick = 1; tick <= 2000; tick++) {
            ActionStatus status = need.tick(new StubTick(tick));
            if (!(status instanceof ActionStatus.Running)) return assertInstanceOf(ActionStatus.Failed.class, status);
        }
        throw new AssertionError("两千刻还没定局");
    }

    @Test
    void 十二种床都卡在羊毛上_每样东西只讲一次_同样卡住的合成一组() {
        String message = bedFails().problem().message();

        assertTrue(message.length() < 3000, "说明有 " + message.length() + " 字：" + message);
        // 最底下卡住的线讲一次，白色羊毛讲一次。
        assertEquals(1, count(message, "minecraft:string×12（"), message);
        assertEquals(1, count(message, "minecraft:white_wool×3（"), message);
        // 十一种彩色羊毛卡在同一处（没有白色羊毛），合成一组说。
        assertTrue(message.contains("这 11 样都是"), message);
        // 十二种做法逐个列出，一种不少。
        for (String color : COLORS) {
            assertTrue(message.contains("minecraft:" + color + "_bed："), color + " 床那种做法要列出来：" + message);
        }
    }

    @Test
    void 这一次里确认拿不到的白色羊毛_换一种做法再要到时不重新问() {
        bedFails();
        // 白色羊毛那一场自己会问两轮（做砸一条路后重新问价）；之后彩色羊毛要到白色羊毛时一次也不再问。
        assertTrue(asked.lastIndexOf("记得的容器 minecraft:white_wool") < asked.indexOf("记得的容器 minecraft:orange_wool"),
                asked.toString());
        assertEquals(1, asked.stream().filter(line -> line.equals("记得的容器 minecraft:string")).count(), asked.toString());
    }

    @Test
    void 羊毛一件都拿不到_不去砍木头做床板_也不去采染色的花() {
        bedFails();
        assertFalse(asked.stream().anyMatch(line -> line.contains("oak_log") || line.contains("planks")), asked.toString());
        assertFalse(asked.stream().anyMatch(line -> line.contains("_flower") || line.contains("_dye")), asked.toString());
        assertEquals(0, backpack.stacks().size(), "没白跑去拿任何东西");
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) found++;
        return found;
    }
}
