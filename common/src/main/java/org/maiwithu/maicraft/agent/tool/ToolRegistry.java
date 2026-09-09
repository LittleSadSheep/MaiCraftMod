package org.maiwithu.maicraft.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据内部工具名找到执行对象，例如 goto 找到移动工具。通常启动时登记，之后由目标适配和内部调用查找。
 * 对外公开的四个 MCP 工具另由 PublicToolCatalog 定义；这里的列表和旧分类不会自动变成公开功能。
 */
public final class ToolRegistry {

    private static final Map<String, MaiCraftTool> TOOLS = new LinkedHashMap<>();

    private ToolRegistry() {}

    /** 内部工具名的格式约束，在注册时统一检查。 */
    private static final java.util.regex.Pattern LEGAL_NAME =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    /** 注册内部工具，并在启动时检查名称格式和重名，尽早暴露功能接线错误。 */
    public static void register(MaiCraftTool tool) {
        String name = tool.name();
        if (name == null || !LEGAL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "工具名不合规(只允许 [a-zA-Z0-9_-],1~64 字符): '" + name
                            + "' — " + tool.getClass().getName());
        }
        // 当前先写入再检查重名，所以抛出重名异常时旧映射已经被覆盖；这不是一次没有产生变化的拒绝。
        MaiCraftTool prior = TOOLS.put(name, tool);
        if (prior != null) {
            throw new IllegalStateException(
                    "Duplicate MaiCraftTool name: " + name
                            + " (was " + prior.getClass().getName()
                            + ", now " + tool.getClass().getName() + ")");
        }
    }

    /** 从名称索引移除工具，返回原实例；这不会取消已经创建的任务。 */
    public static MaiCraftTool remove(String name) {
        return TOOLS.remove(name);
    }

    public static MaiCraftTool get(String name) {
        return TOOLS.get(name);
    }

    /** 先精确查名，找不到时再把输入转成小写查询；不猜测近义词，也不做自然语言匹配。 */
    public static MaiCraftTool resolve(String name) {
        if (name == null) return null;
        MaiCraftTool exact = TOOLS.get(name);
        if (exact != null) return exact;
        String lower = name.toLowerCase();
        if (lower.equals(name)) return null;  // 已经全小写，继续转换也找不到别的名称。
        return TOOLS.get(lower);
    }

    /** 按注册顺序返回工具列表的副本，调用方修改列表不会改变注册表。 */
    public static List<MaiCraftTool> all() {
        return new ArrayList<>(TOOLS.values());
    }

    /**
     * 按工具声明的 RESIDENT 分类筛选；此分类不会让工具自动出现在 MCP 的公开工具列表中。
     */
    public static List<MaiCraftTool> resident() {
        return byResidency(MaiCraftTool.Residency.RESIDENT);
    }

    /**
     * 按工具声明的 DEFERRED 分类筛选，具体使用方式由调用方决定。
     */
    public static List<MaiCraftTool> deferred() {
        return byResidency(MaiCraftTool.Residency.DEFERRED);
    }

    private static List<MaiCraftTool> byResidency(MaiCraftTool.Residency want) {
        List<MaiCraftTool> out = new ArrayList<>();
        for (MaiCraftTool t : TOOLS.values()) {
            if (t.residency() == want) out.add(t);
        }
        return out;
    }

    public static int size() {
        return TOOLS.size();
    }
}
