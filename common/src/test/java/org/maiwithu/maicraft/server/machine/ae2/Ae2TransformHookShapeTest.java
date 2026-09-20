// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import java.util.Arrays;

/** 对实际安装 AE2 JAR 检查原生注入位置与两条不同配方；只读字节码和资源，不加载或执行模组。 */
public final class Ae2TransformHookShapeTest {
    private static final String ROOT = "appeng/recipes/transform/";
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: Ae2TransformHookShapeTest <AE2.jar>");
        try (var jar = new JarFile(Path.of(args[0]).toFile())) {
            MethodNode transform = method(read(jar, ROOT + "TransformLogic"), "tryTransform",
                    "(Lnet/minecraft/world/entity/item/ItemEntity;Ljava/util/function/Predicate;)Z");
            var adds = calls(transform, "net/minecraft/world/level/Level", "addFreshEntity");
            check(adds.size() == 1 && adds.getFirst().desc.equals("(Lnet/minecraft/world/entity/Entity;)Z"),
                    "原生转化必须仍有唯一可核验的实体生成调用");
            int spawn = transform.instructions.indexOf(adds.getFirst());
            check(calls(transform, "net/minecraft/world/item/ItemStack", "split").size() == 1
                            && transform.instructions.indexOf(calls(transform, "net/minecraft/world/item/ItemStack", "split").getFirst()) < spawn
                            && transform.instructions.indexOf(calls(transform, ROOT + "TransformRecipe", "assemble").getFirst()) < spawn,
                    "注入点必须在实际扣料和原版组装之后");
            // 钩子使用这些真实局部变量读取配方与已扣原料；依赖版本改变变量形状时要在实机前明确失败。
            for (String descriptor : List.of("Lnet/minecraft/world/item/crafting/RecipeHolder;", "Ljava/util/ArrayList;",
                    "Lit/unimi/dsi/fastutil/objects/Reference2IntMap;")) {
                long count = transform.localVariables.stream().filter(local -> local.desc.equals(descriptor)
                        && transform.instructions.indexOf(local.start) <= spawn && transform.instructions.indexOf(local.end) > spawn).count();
                check(count == 1, "生成位置的原生局部变量不再唯一：" + descriptor);
            }
            ClassNode recipe = read(jar, ROOT + "TransformRecipe");
            method(recipe, "getCircumstance", "()L" + ROOT + "TransformCircumstance;");
            method(recipe, "getResultItem", "(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;");
            boolean waterDefault = recipe.methods.stream().flatMap(value -> Arrays.stream(value.instructions.toArray()))
                    .anyMatch(value -> value instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                            && field.owner.equals("net/minecraft/tags/FluidTags") && field.name.equals("WATER"));
            check(waterDefault, "省略 circumstance 的默认水标签规则发生变化");
            JsonObject first = json(jar, "fluix_crystals"), second = json(jar, "certus_quartz_crystals");
            check(first.get("type").getAsString().equals("ae2:transform") && second.get("type").getAsString().equals("ae2:transform")
                            && !first.has("circumstance") && !second.has("circumstance")
                            && first.getAsJsonArray("ingredients").size() == 3 && second.getAsJsonArray("ingredients").size() == 2
                            && !first.getAsJsonObject("result").get("id").equals(second.getAsJsonObject("result").get("id")),
                    "两条实测配方应覆盖不同输入数和输出种类，且都通过原生默认流体条件执行");
            check(json(jar, "entangled_singularity").getAsJsonObject("circumstance").get("type").getAsString().equals("explosion"),
                    "爆炸配方必须保留不同环境，不能被流体任务接受");
        }
        System.out.println("Ae2TransformHookShapeTest: native spawn shape and distinct installed recipes passed");
    }

    private static ClassNode read(JarFile jar, String name) throws IOException {
        try (var input = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(value -> value.name.equals(name) && value.desc.equals(descriptor)).findFirst()
                .orElseThrow(() -> new AssertionError("原生方法不存在：" + type.name + "." + name + descriptor));
    }
    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name) {
        var matches = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) matches.add(call);
        return matches;
    }
    private static JsonObject json(JarFile jar, String recipe) throws IOException {
        try (var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("data/ae2/recipe/transform/" + recipe + ".json")), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
