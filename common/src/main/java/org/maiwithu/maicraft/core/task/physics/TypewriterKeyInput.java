package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import org.maiwithu.maicraft.core.integration.create.CreateManualInput;

/** 模型给出按键及有限保持时间；按键不是玩家走路输入，不把 Shift 键转换成离开驾驶座。 */
record TypewriterKeyInput(List<Integer> keys,int holdTicks) {
    TypewriterKeyInput { keys=List.copyOf(keys); }
    static TypewriterKeyInput parse(JsonObject input,boolean bind) {
        var keys=new LinkedHashSet<Integer>();
        if(bind) {
            if(!input.has("key")||input.has("keys"))throw new IllegalArgumentException("配键需要单个 key，不能附带 keys");
            keys.add(code(text(input.get("key"))));
        } else {
            if(input.has("key")||!input.has("keys")||!input.get("keys").isJsonArray())
                throw new IllegalArgumentException("按键需要 keys 数组，不能附带 key");
            for(var key:input.getAsJsonArray("keys"))if(!keys.add(code(text(key))))
                throw new IllegalArgumentException("keys 不能重复");
            if(keys.isEmpty()||keys.size()>16)throw new IllegalArgumentException("一次按住 1..16 个不同按键");
        }
        int ticks=bind?0:input.has("duration_seconds")?CreateManualInput.durationTicks(input):20;
        if(!bind&&ticks==0)throw new IllegalArgumentException("按键保持时间必须大于零且不超过 30 秒");
        return new TypewriterKeyInput(List.copyOf(keys),ticks);
    }
    private static String text(JsonElement value) {
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("按键必须是名称字符串");
        return value.getAsString();
    }
    static int code(String name) {
        String key=name.toLowerCase(Locale.ROOT);
        if(!key.startsWith("key.keyboard."))key="key.keyboard."+key;
        InputConstants.Key parsed=InputConstants.getKey(key);
        // Escape 属于原生退出控制，不可伪装成持续舵机按键；鼠标和未知扫描码也不混入键盘协议。
        if(parsed.getType()!=InputConstants.Type.KEYSYM||parsed.getValue()==GLFW.GLFW_KEY_ESCAPE
                ||parsed.getValue()<GLFW.GLFW_KEY_SPACE||parsed.getValue()>GLFW.GLFW_KEY_LAST)
            throw new IllegalArgumentException("不支持的打字机按键: "+name);
        return parsed.getValue();
    }
    static String name(int code) { return InputConstants.Type.KEYSYM.getOrCreate(code).getName(); }
}
