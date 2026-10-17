// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.ability.design.api.BuildingArchive;
import org.maiwithu.maicraft.ability.design.api.BuildingDesign;
import org.maiwithu.maicraft.ability.design.api.BuildingRecords;
import org.maiwithu.maicraft.ability.design.api.CompiledDesign;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.BlueprintCheck;
import org.maiwithu.maicraft.behavior.construction.BuildingLayout;
import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.construction.ShowsPreview;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 建筑设计能力：画一张图纸、改它、看它、投到世界里看效果、导出成结构文件。图纸不绑地点，盖在哪由 build 决定。
 * 全部当场返回，不控制角色：create 与 update 校验、展开、存成一版并给统计；inspect 看对象与展开路径，带布局；
 * preview 把蓝图落到锚点交给预览叠加；export 写文件；read_site 把现场一栋建筑读成一版层图并给布局。
 * 不替 LLM 改图，也不检查地形。
 */
public final class DesignModule implements AbilityModule {

    private final DesignStore store;
    private final AnchorResolver anchors;
    private final ShowsPreview preview;
    private final Path schematics;
    private final BuildingRecords records;
    private final ReadsSiteCells site;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":design",
            "画一张建筑图纸、改它、看它、投影到世界里预览，或导出成结构文件；图纸不绑地点",
            AbilityDoc.forAbility("design"),
            ParamSpecs.of(
                    ParamSpec.of("operation", ParamType.CHOICE).required().choices("create", "update", "inspect", "preview", "export", "read_site")
                            .doc("create 新建；update 按名合并修改成新的一版；inspect 看；preview 投到 target 预览；export 导出；"
                                    + "read_site 把 target 处已有的一栋建筑读成一版层图，带楼层、门、门洞、床这些布局").build(),
                    ParamSpec.of("drawing", ParamType.JSON_OBJECT).doc("create 用：图纸正文，格式看 lookup(topic=knowledge, id=building/design)，屋顶看 lookup(topic=knowledge, id=building/roofs)").build(),
                    ParamSpec.of("design_id", ParamType.TEXT).doc("update / inspect / preview / export 用：设计编号").build(),
                    ParamSpec.of("edits", ParamType.JSON_OBJECT)
                            .doc("update 用：按名合并的修改，字段 objects、materials、components、remove_objects、remove_components、block_state_axes、overlap_policy、name").build(),
                    ParamSpec.of("name", ParamType.TEXT).doc("inspect 用：看某个对象（展开路径也行）；看组件在名字前加 component:。"
                            + "read_site 用：新图纸的名字，这一栋还没有建筑档案时用这个名字建档").build(),
                    ParamSpec.of("radius", ParamType.INTEGER).range(0, 15)
                            .doc("read_site 用：不自动框整栋，改读以 target 为中心、边长 2×radius+1 的方盒").build(),
                    ParamSpec.of("page", ParamType.INTEGER).range(0, 1_000_000).defaultValue(0).doc("inspect 的页码，从 0 起；结果带 next_page 才有下一页").build(),
                    ParamSpec.of("rotation", ParamType.INTEGER).range(0, 270).defaultValue(0).doc("preview 用：绕锚点转 0 / 90 / 180 / 270 度").build(),
                    ParamSpec.of("format", ParamType.CHOICE).choices("json", "nbt").defaultValue("json").doc("export 用：写成 json（与 build 的 cells 同格式）或原版 nbt").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.MEMORY_ONLY,
            Set.of(),
            List.of(),
            Listing.LISTED);

    /**
     * @param store      设计存储
     * @param anchors    preview 的锚点解析
     * @param preview    预览叠加；没接上传 {@link ShowsPreview#NONE}
     * @param schematics export 写到的目录
     * @param records    建筑档案、记过的地点、世界记忆、设施认法
     * @param site       读现场的只读接缝
     */
    public DesignModule(DesignStore store, AnchorResolver anchors, ShowsPreview preview, Path schematics,
            BuildingRecords records, ReadsSiteCells site) {
        this.store = Objects.requireNonNull(store, "store");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.preview = Objects.requireNonNull(preview, "preview");
        this.schematics = Objects.requireNonNull(schematics, "schematics");
        this.records = Objects.requireNonNull(records, "records");
        this.site = Objects.requireNonNull(site, "site");
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 全部当场做完：图纸格式不对、编号不存在这类都按参数问题如实失败，带定位。
    @Override public StepDecision decide(StepContext step) {
        ParamValues params = step.goal().params();
        String operation = params.text("operation");
        try {
            return switch (operation) {
                case "create" -> create(params);
                case "update" -> update(params);
                case "inspect" -> inspect(params);
                case "preview" -> preview(step);
                case "export" -> export(params);
                case "read_site" -> readSite(step);
                default -> fail("不认识的操作：" + operation, Problem.Kind.INVALID_PARAMETER, null);
            };
        } catch (IllegalArgumentException invalid) {
            return fail(invalid.getMessage(), Problem.Kind.INVALID_PARAMETER, "按错误里的定位改图纸再试");
        } catch (IllegalStateException failure) {
            return fail(failure.getMessage(), Problem.Kind.INTERNAL_ERROR, null);
        }
    }

    private StepDecision create(ParamValues params) {
        if (!params.has("drawing")) return fail("create 要给 drawing", Problem.Kind.INVALID_PARAMETER, "把图纸正文放在 drawing 里");
        // 参数入口只核过是对象；图纸的内容在这里一次转成作者模型，之后不再传 JSON。
        BuildingDesign design = store.create(params.json("drawing").getAsJsonObject());
        CompiledDesign compiled = DesignCompiler.compile(design.drawing());
        return done("图纸存好了：" + design.name() + "，" + compiled.cellCount() + " 格", DesignDetails.of(design.id(), null, compiled));
    }

    private StepDecision update(ParamValues params) {
        if (!params.has("design_id") || !params.has("edits")) return fail("update 要给 design_id 和 edits", Problem.Kind.INVALID_PARAMETER, null);
        BuildingDesign design = store.update(params.text("design_id"), params.json("edits").getAsJsonObject());
        CompiledDesign compiled = DesignCompiler.compile(design.drawing());
        return done("改好了，新的一版 " + design.id() + "，" + compiled.cellCount() + " 格", DesignDetails.of(design.id(), design.parentId(), compiled));
    }

    private StepDecision inspect(ParamValues params) {
        if (!params.has("design_id")) return fail("inspect 要给 design_id", Problem.Kind.INVALID_PARAMETER, null);
        BuildingDesign design = store.load(params.text("design_id"));
        int page = (int) params.integer("page");
        JsonObject text;
        if (!params.has("name")) {
            text = DesignInspection.describeDesign(design.drawing(), page);
        } else if (params.text("name").startsWith("component:")) {
            text = DesignInspection.describeComponent(design.drawing(), params.text("name").substring("component:".length()), page);
        } else {
            text = DesignInspection.describeObject(design.drawing(), params.text("name"), page);
        }
        // 整张看和看某个对象都带这张图纸的布局：楼层、门、门洞、床这些按图纸编译出来的格算。
        DesignDetails details = DesignDetails.inspection(design.id(), design.parentId(), text,
                layoutOf(DesignCompiler.compile(design.drawing()).cells()));
        return done("看了图纸 " + design.name(), details);
    }

    private StepDecision preview(StepContext step) {
        ParamValues params = step.goal().params();
        if (!params.has("design_id")) return fail("preview 要给 design_id", Problem.Kind.INVALID_PARAMETER, null);
        Rotation turn = Blueprint.turnOf(params.integer("rotation"));
        if (turn == null) return fail("rotation 只能是 0、90、180、270", Problem.Kind.INVALID_PARAMETER, null);
        AnchorResolver.Resolution anchor = anchors.resolve(step.goal().target());
        if (anchor instanceof AnchorResolver.Resolution.Failed failed) return fail(failed.problem().message(), failed.problem().kind(), failed.problem().suggestion());
        WorldPosition at = ((AnchorResolver.Resolution.Ready) anchor).anchor();
        BuildingDesign design = store.load(params.text("design_id"));
        CompiledDesign compiled = DesignCompiler.compile(design.drawing());
        Blueprint blueprint = Blueprint.at(at.dimension(), new BlockPos(at.x(), at.y(), at.z()), turn, compiled.cells());
        preview.show(blueprint);
        return done("已把 " + design.name() + " 投到 (" + at.x() + ", " + at.y() + ", " + at.z() + ")，" + compiled.cellCount() + " 格",
                DesignDetails.of(design.id(), design.parentId(), compiled));
    }

    private StepDecision export(ParamValues params) {
        if (!params.has("design_id")) return fail("export 要给 design_id", Problem.Kind.INVALID_PARAMETER, null);
        BuildingDesign design = store.load(params.text("design_id"));
        CompiledDesign compiled = DesignCompiler.compile(design.drawing());
        String format = params.text("format");
        DesignExport.Written written = DesignExport.write(schematics, design.id(), compiled.cells(), format);
        DesignDetails details = DesignDetails.of(design.id(), design.parentId(), compiled)
                .withFile(written.file().toString(), List.of(written.offset().getX(), written.offset().getY(), written.offset().getZ()));
        return done("导出到 " + written.file().getFileName(), details);
    }

    // 图纸的布局：编译出来的格就是知道的格，没声明的当空气；清空格也是空气。
    private BuildingLayout layoutOf(List<PlannedCell> cells) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (PlannedCell cell : cells) states.put(cell.pos(), cell.kind() == CellKind.AIR ? Blocks.AIR.defaultBlockState() : cell.state());
        Blueprint.Bounds bounds = Blueprint.boundsOf(cells);
        return BuildingLayout.of(bounds.min().offset(-1, -1, -1), bounds.max().offset(1, 1, 1),
                pos -> states.getOrDefault(pos, Blocks.AIR.defaultBlockState()), pos -> true, records.facility());
    }

    /**
     * 读现场：定范围（指着档案读档案范围、给了 radius 读方盒、不然从目标起自动框），读看得见的与自己放的方块，
     * 画成一版层图存起来，再给这一栋的布局；指着档案时另给整栋比对；给了 name 而这一栋没档案时建一份。
     * 档案名和一个记过的地点同名又不在一处：先问指哪个。
     */
    private StepDecision readSite(StepContext step) {
        ParamValues params = step.goal().params();
        Target target = step.goal().target();
        if (target == null) return fail("read_site 要给 target：指着一栋建筑档案、一面墙、一扇门，或给坐标", Problem.Kind.INVALID_PARAMETER, null);
        if (site.dimension() == null) return fail("角色现在不在世界里，读不了现场", Problem.Kind.NOT_POSSIBLE_HERE, null);
        BuildingArchive archive = null;
        if (target instanceof Target.Landmark landmark) {
            BuildingRecords.Lookup lookup = records.lookup(landmark.name());
            if (lookup instanceof BuildingRecords.Lookup.Found found) archive = found.archive();
            if (lookup instanceof BuildingRecords.Lookup.Ambiguous ambiguous) {
                if (step.answers().contains("cancel")) return new StepDecision.Finish(TaskResult.cancelled("读现场取消了"));
                if (step.answers().contains("building")) archive = ambiguous.archive();
                else if (!step.answers().contains("place")) return askWhich(landmark.name(), ambiguous);
            }
        }
        if (archive != null && !archive.dimension().equals(site.dimension())) {
            return fail("「" + archive.name() + "」在 " + archive.dimension() + "，角色现在在 " + site.dimension(), Problem.Kind.UNREACHABLE, "先 travel 回那个维度");
        }
        Blueprint.Bounds range;
        BlockPos origin;
        if (archive != null) {
            // 指着一栋档案：读档案的范围，设计原点就是档案的锚点，改完照这一版补建正好落回原处。
            range = archive.bounds();
            origin = archive.anchor();
        } else {
            AnchorResolver.Resolution anchor = anchors.resolve(target);
            if (anchor instanceof AnchorResolver.Resolution.Failed failed) return fail(failed.problem().message(), failed.problem().kind(), failed.problem().suggestion());
            WorldPosition at = ((AnchorResolver.Resolution.Ready) anchor).anchor();
            BlockPos start = new BlockPos(at.x(), at.y(), at.z());
            range = params.has("radius") ? SiteCapture.box(start, (int) params.integer("radius")) : SiteCapture.frame(start, site);
            origin = range.min();
        }
        SiteCapture.Read read = SiteCapture.read(range, site);
        String name = params.has("name") ? params.text("name").trim()
                : archive != null ? archive.name() : "现场@" + origin.getX() + "," + origin.getY() + "," + origin.getZ();
        BuildingDesign design = store.create(SiteCapture.drawing(read, name, origin));
        CompiledDesign compiled = DesignCompiler.compile(design.drawing());
        BuildingLayout layout = BuildingLayout.of(range.min().offset(-1, -1, -1), range.max().offset(1, 1, 1), read::state, read::known,
                records.facility());
        BlueprintCheck.Report check = archive == null ? null : BlueprintCheck.report(archive.laid(), readsBlocks());
        String archiveName = archive != null ? archive.name() : null;
        if (archive == null && params.has("name")) {
            // 给了名字、这一栋还没档案：照读出来的这一版建一份，锚点是范围最小角、朝向 0，之后 build 指着它改。
            String unique = records.archives().unusedName(name, site.dimension(), origin);
            BuildingArchive created = new BuildingArchive(unique, site.dimension(), origin, 0, design.id(), compiled.cells(),
                    Instant.now(), "读现场建的档，" + read.unseen() + " 格没看到");
            if (records.archives().save(created)) {
                records.remember().accept(created);
                archiveName = unique;
            }
        }
        JsonObject drawn = design.drawing().getAsJsonArray("objects").get(0).getAsJsonObject();
        WorldPosition at = new WorldPosition(origin.getX(), origin.getY(), origin.getZ(), site.dimension());
        String summary = "读了 " + (range.max().getY() - range.min().getY() + 1) + " 层、" + read.blocks().size() + " 格方块"
                + (read.unseen() > 0 ? "（" + read.unseen() + " 格没看到）" : "") + "，存成图纸 " + design.id();
        return done(summary, DesignDetails.of(design.id(), null, compiled)
                .withSite(at, read.unseen(), drawn, layout, check, archiveName));
    }

    private StepDecision askWhich(String name, BuildingRecords.Lookup.Ambiguous ambiguous) {
        WorldPosition place = ambiguous.place();
        BlockPos anchor = ambiguous.archive().anchor();
        return new StepDecision.Ask(new Question(Question.Reason.UNCLEAR_TARGET,
                "「" + name + "」既是记过的地点 (" + place.x() + ", " + place.y() + ", " + place.z()
                        + ")，又是锚点在 (" + anchor.getX() + ", " + anchor.getY() + ", " + anchor.getZ() + ") 的一栋建筑档案，两处不在一起",
                List.of(new Question.Option("building", "读那一栋建筑（档案的范围）"),
                        new Question.Option("place", "读记过的那个地点周围"),
                        new Question.Option("cancel", "不读了"))));
    }

    // 读现场那份接缝当整栋比对的现场：加载着就给这一刻的方块。
    private ReadsBlocks readsBlocks() {
        return new ReadsBlocks() {
            @Override public boolean loaded(BlockPos pos) {
                return site.loaded(pos);
            }

            @Override public BlockState state(BlockPos pos) {
                return site.state(pos);
            }
        };
    }

    private static StepDecision done(String summary, DesignDetails details) {
        return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.DONE, summary).details(details).build());
    }

    // 没开工就收场：一句话结论只带结论句，展开的原因（比如转述下一层的失败）只在问题里留一份。
    private static StepDecision fail(String message, Problem.Kind kind, String suggestion) {
        Problem problem = Problem.of(kind, message, suggestion);
        return new StepDecision.Finish(TaskResult.failed(problem.headline(), problem));
    }
}
