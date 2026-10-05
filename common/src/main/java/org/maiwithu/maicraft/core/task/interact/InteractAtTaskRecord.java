package org.maiwithu.maicraft.core.task.interact;
import org.maiwithu.maicraft.core.task.MouseButton;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存本次交互的目标、手持物和持续方式；公开定点请求额外开启自动接近，没有坐标时沿当前朝向操作。
 * expectedBlock 要求操作后出现某种方块；requiredBlock 要求操作前目标仍是指定方块，两者用途不同。
 * 满桶的 aim 是流体落格而非被点的支撑块；expectedOutputItem 是使用后应增加的库存物品类型。
 */
public final class InteractAtTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "interact_at";

    public final MouseButton button;
    public final BlockPos aim;     // null 表示沿当前朝向使用物品（对空气使用）。
    public final int holdTicks;
    public final Item item;        // null 表示使用当前手持物；否则先装备指定物品。
    public final Block expectedBlock;
    /**
     * 执行原版使用前再确认目标身份；它不是操作后的结果要求。
     */
    public final Block requiredBlock;
    public boolean heldItemUseOnly;
    public boolean emptyHand;
    public boolean approachTarget;
    public boolean mayAlterTerrain;
    public boolean observeMenu;
    /** 点火类使用要求点击后目标面的相邻格真的出现火；只认点击确认不算诚实结果。 */
    public boolean expectIgnition;
    public Item expectedOutputItem;
    public String itemResourceId;
    /** 告示牌写字请求的四行以内目标文字；非 null 表示右键打开原版编辑屏后填入这些行。 */
    public List<String> signLines;

    /** 原版告示牌固定四行；提交不足四行时余下行按空串覆盖。 */
    public static final int SIGN_LINE_COUNT = 4;
    /**
     * 计划期的行长度拒绝线。原版真正按 90 像素行宽校验（约 18 个半角字符），
     * 装不下的字符由编辑屏自己丢弃，最终以告示牌真实文字与提交内容对账收场；
     * 这里只拦下显然不可能装下的行，像素级结果不在此冒充可预知。
     */
    public static final int SIGN_LINE_CHAR_LIMIT = 64;

    /**
     * 解析告示牌写字的文字：按换行拆行，最多四行，每行有长度上限。
     * 返回 null 表示本次目标不是写字；内容原样保留，不做任何语义审查。
     */
    public static List<String> parseSignText(String text) {
        if (text == null) return null;
        if (text.isBlank()) throw new IllegalArgumentException(
                "sign write needs non-blank text; an all-empty write cannot be distinguished from a missing request");
        String[] raw = text.split("\n", -1);
        if (raw.length > SIGN_LINE_COUNT) throw new IllegalArgumentException(
                "sign text supports at most " + SIGN_LINE_COUNT + " lines, got " + raw.length);
        List<String> lines = new ArrayList<>();
        for (String line : raw) {
            if (line.length() > SIGN_LINE_CHAR_LIMIT) throw new IllegalArgumentException(
                    "each sign line is limited to " + SIGN_LINE_CHAR_LIMIT + " characters at planning time; line "
                            + (lines.size() + 1) + " has " + line.length()
                            + ". The vanilla editor enforces a ~90px line width and silently drops overflow, so keep lines short.");
            lines.add(line);
        }
        return List.copyOf(lines);
    }

    /** 语义交互由 Mod 自行走到可点击位置；只在原请求允许时才为通行拆挖或垫块。 */
    public InteractAtTaskRecord withApproach(boolean alterTerrain) {
        if (aim == null || heldItemUseOnly) throw new IllegalArgumentException("approach requires a block target");
        approachTarget = true; mayAlterTerrain = alterTerrain; return this;
    }

    /** 打开容器的意图在点击后等待原生菜单出现；未开出菜单也只报告观察，不重放已经确认的点击。 */
    public InteractAtTaskRecord withMenuObservation() {
        if (aim == null || button != MouseButton.RIGHT) throw new IllegalArgumentException("menu observation requires block use");
        observeMenu = true; return this;
    }

    /** 只给已点名物品的方块交互绑定观察身份；选择哪一进度由调用者决定，执行器不替换成同名其他工件。 */
    public InteractAtTaskRecord withItemResourceId(String resourceId) {
        if (item == null || aim == null || button != MouseButton.RIGHT || resourceId == null
                || resourceId.isBlank() || resourceId.length() > 512)
            throw new IllegalArgumentException("item_resource_id requires a named item and block use, with a bounded observed identity");
        itemResourceId = resourceId; return this;
    }

    /** 语义目标没点名物品时先收好战斗或施工留下的工具，再空手点击方块，避免把工具当材料放进机器。 */
    public InteractAtTaskRecord withEmptyHand() {
        if (aim == null || button != MouseButton.RIGHT || item != null || heldItemUseOnly)
            throw new IllegalArgumentException("empty-hand interaction needs a block target and no named item");
        emptyHand = true; return this;
    }

    /** 使用物品自身逻辑时不先点击准星后的方块；副手材料由原生物品处理，产物仍须读真实背包。 */
    public InteractAtTaskRecord useHeldItemOnly(Item expectedOutput) {
        if (aim != null || button != MouseButton.RIGHT || item == null || expectedBlock != null)
            throw new IllegalArgumentException("held item use needs a named item and no block target");
        heldItemUseOnly = true; expectedOutputItem = expectedOutput; return this;
    }

    /** 定点倒桶或加工后等实际返还物入包；只增加观察要求，不重复已经提交的原生使用。 */
    public InteractAtTaskRecord withExpectedOutput(Item output) {
        if (button != MouseButton.RIGHT || output == null) throw new IllegalArgumentException("expected output requires right-click item use");
        expectedOutputItem = output; return this;
    }

    /** 点火类使用在确认后核对相邻格真有火；需要右键方块目标，火落在被点面的外侧空气格。 */
    public InteractAtTaskRecord withIgnitionCheck() {
        if (button != MouseButton.RIGHT || aim == null || heldItemUseOnly)
            throw new IllegalArgumentException("ignition check requires a right-click block target");
        expectIgnition = true; return this;
    }

    /**
     * 告示牌写字要求右键方块目标且不点名物品：右键由原版打开编辑屏，
     * 带物品会被原版当成对手持物的操作请求，写字语义就不再成立。
     */
    public InteractAtTaskRecord withSignText(List<String> lines) {
        if (button != MouseButton.RIGHT || aim == null || item != null || heldItemUseOnly)
            throw new IllegalArgumentException("sign writing requires an item-free right-click block target");
        if (lines == null || lines.isEmpty() || lines.size() > SIGN_LINE_COUNT)
            throw new IllegalArgumentException("sign text must contain 1.." + SIGN_LINE_COUNT + " lines");
        for (String line : lines) if (line == null || line.length() > SIGN_LINE_CHAR_LIMIT)
            throw new IllegalArgumentException("each sign line is limited to " + SIGN_LINE_CHAR_LIMIT + " characters");
        signLines = List.copyOf(lines);
        return this;
    }

    public InteractAtTaskRecord(String toolCallId, long deadlineGameTime,
                                MouseButton button, BlockPos aim, int holdTicks, Item item) {
        this(toolCallId, deadlineGameTime, button, aim, holdTicks, item, null);
    }

    public InteractAtTaskRecord(String toolCallId, long deadlineGameTime,
                                MouseButton button, BlockPos aim, int holdTicks, Item item, Block expectedBlock) {
        this(toolCallId, deadlineGameTime, button, aim, holdTicks, item, expectedBlock, null);
    }

    public InteractAtTaskRecord(String toolCallId, long deadlineGameTime,
                                MouseButton button, BlockPos aim, int holdTicks, Item item, Block expectedBlock, Block requiredBlock) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if (requiredBlock != null && aim == null) throw new IllegalArgumentException("required block needs an explicit aim");
        this.button = button;
        this.aim = aim != null ? aim.immutable() : null;
        this.holdTicks = holdTicks;
        this.item = item;
        this.expectedBlock = expectedBlock;
        this.requiredBlock = requiredBlock;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + (button == MouseButton.LEFT ? "left" : "right")
                + (item != null ? " " + BuiltInRegistries.ITEM.getKey(item).getPath() : "")
                + (aim != null ? " @" + aim.getX() + "," + aim.getY() + "," + aim.getZ() : " (forward)")
                + (holdTicks != 0 ? " hold=" + holdTicks : "");
    }
}
