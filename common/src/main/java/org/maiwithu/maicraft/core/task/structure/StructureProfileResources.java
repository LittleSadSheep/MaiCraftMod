package org.maiwithu.maicraft.core.task.structure;

import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.maiwithu.maicraft.core.task.structure.StructureEvidenceProfiles.Profile;

/** 模组资源和玩家配置声明可见方块组合；缺失规则时如实报告不能识别，不使用种子定位。 */
public final class StructureProfileResources {
    private static final Gson GSON = new Gson();
    private static List<String> problems = List.of();
    private StructureProfileResources() {}

    public static void refresh() {
        var minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        load(minecraft.getResourceManager(), minecraft.gameDirectory.toPath().resolve("config/maicraft/structure_evidence"));
    }

    public static List<String> problems() { return problems; }

    /** 同名规则先取资源包实际覆盖结果，再用显式本地配置覆盖；错误保留在目录回执中。 */
    static void load(ResourceManager manager, Path directory) {
        Map<String, Profile> profiles = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        manager.listResources("maicraft/structure_evidence", id -> id.getPath().endsWith(".json"))
                .entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    try (Reader reader = entry.getValue().openAsReader()) { add(profiles, reader); }
                    catch (Exception invalid) { errors.add(entry.getKey() + ": " + invalid.getMessage()); }
                });
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    try (Reader reader = Files.newBufferedReader(file)) { add(profiles, reader); }
                    catch (Exception invalid) { errors.add(file.getFileName() + ": " + invalid.getMessage()); }
                }
            } catch (Exception unavailable) { errors.add("structure_evidence: " + unavailable.getMessage()); }
        }
        StructureEvidenceProfiles.installResources(profiles);
        problems = List.copyOf(errors);
    }

    private static void add(Map<String, Profile> profiles, Reader reader) {
        Profile profile = parse(reader);
        profiles.put(profile.canonicalId(), profile);
    }

    /** 校验规则形状与资源 ID，实际方块是否安装由 resolve 按当前注册表决定。 */
    static Profile parse(Reader reader) {
        Profile profile = GSON.fromJson(reader, Profile.class);
        if (profile == null || !validId(profile.canonicalId()) || profile.clusterRadius() > 64
                || profile.evidenceDescription() == null || profile.evidenceDescription().isBlank())
            throw new IllegalArgumentException("invalid structure evidence profile");
        if (profile.dimensions().stream().anyMatch(id -> !validId(id))
                || profile.groups().stream().flatMap(group -> group.blockIds().stream()).anyMatch(id -> !validId(id)))
            throw new IllegalArgumentException("structure profile needs namespaced dimension and block IDs");
        return profile;
    }

    private static boolean validId(String id) { return id != null && id.contains(":") && ResourceLocation.tryParse(id) != null; }
}
