// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.maiwithu.maicraft.ability.design.api.BuildingDesign;
import org.maiwithu.maicraft.ability.design.api.CompiledDesign;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
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
 * 全部当场返回，不控制角色：create 与 update 校验、展开、存成一版并给统计；inspect 看对象与展开路径；
 * preview 把蓝图落到锚点交给预览叠加；export 写文件。不替 LLM 改图，也不检查地形。
 */
public final class DesignModule implements AbilityModule {

    private final DesignStore store;
    private final AnchorResolver anchors;
    private final ShowsPreview preview;
    private final Path schematics;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":design",
            "画一张建筑图纸、改它、看它、投影到世界里预览，或导出成结构文件；图纸不绑地点",
            AbilityDoc.forAbility("design"),
            ParamSpecs.of(
                    ParamSpec.of("operation", ParamType.CHOICE).required().choices("create", "update", "inspect", "preview", "export")
                            .doc("create 新建；update 按名合并修改成新的一版；inspect 看；preview 投到 target 预览；export 导出").build(),
                    ParamSpec.of("drawing", ParamType.JSON).doc("create 用：图纸正文，格式看 lookup 的 building/design").build(),
                    ParamSpec.of("design_id", ParamType.TEXT).doc("update / inspect / preview / export 用：设计编号").build(),
                    ParamSpec.of("edits", ParamType.JSON)
                            .doc("update 用：按名合并的修改，字段 objects、materials、components、remove_objects、remove_components、block_state_axes、overlap_policy、name").build(),
                    ParamSpec.of("name", ParamType.TEXT).doc("inspect 用：看某个对象（展开路径也行）；看组件在名字前加 component:").build(),
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
     */
    public DesignModule(DesignStore store, AnchorResolver anchors, ShowsPreview preview, Path schematics) {
        this.store = Objects.requireNonNull(store, "store");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.preview = Objects.requireNonNull(preview, "preview");
        this.schematics = Objects.requireNonNull(schematics, "schematics");
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
        JsonElement drawing = params.json("drawing");
        if (!drawing.isJsonObject()) return fail("drawing 要是 JSON 对象", Problem.Kind.INVALID_PARAMETER, null);
        BuildingDesign design = store.create(drawing.getAsJsonObject());
        CompiledDesign compiled = DesignCompiler.compile(design.drawing());
        return done("图纸存好了：" + design.name() + "，" + compiled.cellCount() + " 格", DesignDetails.of(design.id(), null, compiled));
    }

    private StepDecision update(ParamValues params) {
        if (!params.has("design_id") || !params.has("edits")) return fail("update 要给 design_id 和 edits", Problem.Kind.INVALID_PARAMETER, null);
        JsonElement edits = params.json("edits");
        if (!edits.isJsonObject()) return fail("edits 要是 JSON 对象", Problem.Kind.INVALID_PARAMETER, null);
        BuildingDesign design = store.update(params.text("design_id"), edits.getAsJsonObject());
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
        DesignDetails details = new DesignDetails(design.id(), design.parentId(), null, null, null, null, null, null, text, null, null);
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

    private static StepDecision done(String summary, DesignDetails details) {
        return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.DONE, summary).details(details).build());
    }

    private static StepDecision fail(String message, Problem.Kind kind, String suggestion) {
        return new StepDecision.Finish(TaskResult.failed(message, Problem.of(kind, message, suggestion)));
    }
}
