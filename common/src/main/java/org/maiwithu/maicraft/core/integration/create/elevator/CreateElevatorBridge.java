package org.maiwithu.maicraft.core.integration.create.elevator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** Optional Create 6 client API. Snapshots contain live handles and must stay inside one tick. */
final class CreateElevatorBridge {
    private static final String ROOT = "com.simibubi.create.content.";
    private record MethodKey(Class<?> owner, String name, List<Class<?>> arguments) {}
    private static final Map<MethodKey, Method> METHODS = new java.util.concurrent.ConcurrentHashMap<>();
    private final Class<?> entityType = type(ROOT + "contraptions.AbstractContraptionEntity");
    private final Class<?> elevatorType = type(ROOT + "contraptions.elevator.ElevatorContraption");
    private final Class<?> controlsType = type(ROOT + "contraptions.actors.contraptionControls.ContraptionControlsBlock");
    private final Class<?> contactType = type(ROOT + "contraptions.elevator.ElevatorContactBlockEntity");
    private final Class<?> handler = type(ROOT + "contraptions.ContraptionHandlerClient");
    private final Class<?> scrolling = type(ROOT + "contraptions.elevator.ElevatorControlsHandler");
    private final Class<?> selectionType = type(ROOT + "contraptions.actors.contraptionControls.ContraptionControlsMovement$ElevatorFloorSelection");
    private final Object controlsSlot = construct(ROOT + "contraptions.actors.contraptionControls.ContraptionControlsBlockEntity$ControlsSlot");

    record Column(int x, int z, Direction side) { BlockPos at(int y) { return new BlockPos(x, y, z); } }
    record Floor(int contactY, String shortName, String longName) {}
    record Cabin(Entity entity, Object contraption, Column column, int offset, int targetY, boolean arrived,
                 Vec3 origin, Map<BlockPos, StructureBlockInfo> blocks, BlockGetter view,
                 List<BlockPos> controls, List<Floor> floors, int minY, int maxY) {
        Vec3 global(Vec3 local) { return local.add(origin); }
        Vec3 local(Vec3 global) { return global.subtract(origin); }
        Vec3 originAt(int floor) { return origin.add(0, floor - offset - entity.getY(), 0); }
        boolean serves(int floor) { return floor >= minY && floor <= maxY && floors.stream().anyMatch(f -> f.contactY == floor); }
        boolean aligned(int floor) { return arrived && targetY == floor && Math.abs(entity.getY() + offset - floor) < 1.0 / 64; }
    }

    static CreateElevatorBridge optional() {
        try { return new CreateElevatorBridge(); }
        catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    List<Cabin> cabins(LocalPlayerContext ctx) {
        return cabins(ctx.player());
    }

    List<Cabin> cabins(LocalPlayer player) {
        List<Cabin> result = new ArrayList<>();
        for (Entity entity : player.clientLevel.entitiesForRendering()) {
            if (entityType.isInstance(entity) && entity.isAlive()) {
                Cabin cabin = read(entity);
                if (cabin != null) result.add(cabin);
                if (result.size() == 32) break;
            }
        }
        return result;
    }

    Cabin find(LocalPlayerContext ctx, UUID identity) {
        for (Entity entity : ctx.level().entitiesForRendering()) {
            if (entity.getUUID().equals(identity) && entityType.isInstance(entity) && entity.isAlive()) return read(entity);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Cabin read(Entity entity) {
        Object contraption = call(entity, "getContraption");
        if (!elevatorType.isInstance(contraption)) return null;
        Map<BlockPos, StructureBlockInfo> blocks = (Map<BlockPos, StructureBlockInfo>) call(contraption, "getBlocks");
        if (blocks.size() > 4096) return null;
        Object column = call(contraption, "getGlobalColumn");
        List<Floor> floors = new ArrayList<>();
        for (Object entry : (List<?>) value(contraption, "namesList")) {
            Object names = call(entry, "getSecond");
            floors.add(new Floor(number(call(entry, "getFirst")), String.valueOf(call(names, "getFirst")),
                    String.valueOf(call(names, "getSecond"))));
        }
        List<BlockPos> controls = blocks.entrySet().stream().filter(e -> controlsType.isInstance(e.getValue().state().getBlock()))
                .map(e -> e.getKey().immutable()).toList();
        return new Cabin(entity, contraption,
                new Column(number(call(column, "x")), number(call(column, "z")), (Direction) call(column, "side")),
                number(call(contraption, "getContactYOffset")), number(value(contraption, "clientYTarget")),
                (boolean) value(contraption, "arrived"), (Vec3) call(entity, "toGlobalVector", Vec3.ZERO, 1F),
                blocks, (BlockGetter) call(contraption, "getContraptionWorld"), controls, List.copyOf(floors),
                number(value(contraption, "minContactY")), number(value(contraption, "maxContactY")));
    }

    boolean isContact(LocalPlayerContext ctx, BlockPos pos, Column column) {
        if (!ctx.level().hasChunkAt(pos) || !contactType.isInstance(ctx.level().getBlockEntity(pos))) return false;
        BlockState state = ctx.level().getBlockState(pos);
        return pos.getX() == column.x && pos.getZ() == column.z
                && state.hasProperty(BlockStateProperties.FACING) && state.getValue(BlockStateProperties.FACING) == column.side;
    }

    Vec3 controlAim(Cabin cabin, BlockPos control) {
        BlockState state = cabin.blocks.get(control).state();
        Vec3 offset = (Vec3) call(controlsSlot, "getLocalOffset", cabin.view, control, state);
        return cabin.global(Vec3.atLowerCornerOf(control).add(offset));
    }

    BlockHitResult hit(LocalPlayerContext ctx, Cabin cabin, BlockPos localPos) {
        if (ctx.player().isSpectator() || ctx.player().isHandsBusy()) return null;
        Vec3 from = ctx.player().getEyePosition();
        Vec3 end = from.add(ctx.player().getViewVector(1F).scale(ctx.player().blockInteractionRange()));
        HitResult world = org.maiwithu.maicraft.core.act.Interaction.nativeRaytrace(ctx.player(), ctx.player().blockInteractionRange());
        if (world.getType() != HitResult.Type.MISS) end = world.getLocation();
        BlockHitResult hit = (BlockHitResult) call(handler, "rayTraceContraption", from, end, cabin.entity);
        if (hit == null || !hit.getBlockPos().equals(localPos)) return null;
        double distance = cabin.global(hit.getLocation()).distanceToSqr(from);
        var bounds = new net.minecraft.world.phys.AABB(from, end).inflate(16);
        for (Entity other : ctx.level().entitiesForRendering()) {
            if (other == cabin.entity || !entityType.isInstance(other) || !other.getBoundingBox().intersects(bounds)
                    || call(other, "getContraption") == null) continue;
            BlockHitResult nearer = (BlockHitResult) call(handler, "rayTraceContraption", from, end, other);
            if (nearer != null && ((Vec3) call(other, "toGlobalVector", nearer.getLocation(), 1F)).distanceToSqr(from) < distance - 1e-6) return null;
        }
        return hit;
    }

    int selected(Cabin cabin, BlockPos control) {
        Object actor = call(cabin.contraption, "getActorAt", control);
        if (actor == null) return Integer.MIN_VALUE;
        Object movement = call(actor, "getRight");
        if (movement == null) return Integer.MIN_VALUE;
        Object selection = value(movement, "temporaryData");
        return !selectionType.isInstance(selection) ? Integer.MIN_VALUE : number(value(selection, "currentTargetY"));
    }

    boolean recentSupport(Cabin cabin, LocalPlayer player) {
        Object age = ((Map<?, ?>) value(cabin.entity, "collidingEntities")).get(player);
        return age instanceof Number count && count.intValue() <= 1
                && cabin.entity.getBoundingBox().inflate(0.25).intersects(player.getBoundingBox());
    }

    void scroll(LocalPlayerContext ctx, Cabin cabin, BlockPos control, int floor) {
        if (hit(ctx, cabin, control) == null) throw new IllegalStateException("controller is no longer under the crosshair");
        int current = selected(cabin, control);
        int from = index(cabin, current), to = index(cabin, floor);
        if (from < 0 || to < 0) throw new IllegalStateException("elevator floor selection is not synchronized");
        if (from != to && !Boolean.TRUE.equals(call(scrolling, "onScroll", (double) (to - from)))) {
            throw new IllegalStateException("native elevator scroll did not hit the controls dial");
        }
    }

    void click(LocalPlayerContext ctx, Cabin cabin, BlockPos control, int floor) {
        BlockHitResult hit = hit(ctx, cabin, control);
        if (hit == null || selected(cabin, control) != floor || !cabin.serves(floor)) {
            throw new IllegalStateException("elevator selection or native hit changed before submission");
        }
        // This is Create's exact right-click chain: the client behaviour sends its floor request,
        // then the generic interaction packet performs server-side feedback/validation.
        if (!Boolean.TRUE.equals(call(cabin.entity, "handlePlayerInteraction", ctx.player(), control, hit.getDirection(), InteractionHand.MAIN_HAND))) {
            throw new IllegalStateException("the native dynamic controller refused interaction");
        }
        send(construct(ROOT + "contraptions.sync.ContraptionInteractionPacket", cabin.entity, InteractionHand.MAIN_HAND, control, hit.getDirection()));
    }

    void requestFloors(Cabin cabin) {
        send(construct(ROOT + "contraptions.elevator.ElevatorFloorListPacket$RequestFloorList", cabin.entity));
    }

    int remoteChannel(LocalPlayerContext ctx, BlockPos receiver, ItemStack controller) {
        Class<?> item = type(ROOT + "redstone.link.controller.LinkedControllerItem");
        if (!item.isInstance(controller.getItem())) return -1;
        Class<?> link = type(ROOT + "redstone.link.LinkBehaviour");
        Object behaviour = call(type("com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour"),
                "get", ctx.level(), receiver, value(link, "TYPE"));
        if (behaviour == null || !Boolean.TRUE.equals(call(behaviour, "isListening"))) return -1;
        Object frequency = call(behaviour, "getNetworkKey");
        if (((ItemStack) call(call(frequency, "getFirst"), "getStack")).isEmpty()
                && ((ItemStack) call(call(frequency, "getSecond"), "getStack")).isEmpty()) return -1;
        for (int slot = 0; slot < 6; slot++) if (frequency.equals(call(item, "toFrequency", controller, slot))) return slot;
        return -1;
    }

    void remoteInput(int channel, boolean press) {
        send(construct(ROOT + "redstone.link.controller.LinkedControllerInputPacket", List.of(channel), press));
    }

    private static int index(Cabin cabin, int floor) {
        for (int i = 0; i < cabin.floors.size(); i++) if (cabin.floors.get(i).contactY == floor) return i;
        return -1;
    }
    private static int number(Object number) { return ((Number) number).intValue(); }
    private static Class<?> type(String name) {
        try { return Class.forName(name, false, CreateElevatorBridge.class.getClassLoader()); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Create elevator API unavailable: " + name, failure); }
    }
    private static Object value(Object owner, String name) {
        try {
            Field field = (owner instanceof Class<?> c ? c : owner.getClass()).getField(name);
            field.setAccessible(true);
            return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Create field " + name, failure); }
    }
    private static Object call(Object owner, String name, Object... args) {
        Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
        MethodKey key = new MethodKey(type, name, Arrays.stream(args).<Class<?>>map(a -> a == null ? Void.class : a.getClass()).toList());
        Method method = METHODS.computeIfAbsent(key, unused -> Arrays.stream(type.getMethods())
                .filter(m -> m.getName().equals(name) && accepts(m.getParameterTypes(), args)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Create method " + type.getName() + "." + name)));
        try { method.setAccessible(true); return method.invoke(owner instanceof Class<?> ? null : owner, args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Create invocation " + name, failure); }
    }
    private static Object construct(String name, Object... args) {
        Constructor<?> ctor = Arrays.stream(type(name).getConstructors()).filter(c -> accepts(c.getParameterTypes(), args)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Create constructor " + name));
        try { return ctor.newInstance(args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Create construction " + name, failure); }
    }
    private static boolean accepts(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) { if (types[i].isPrimitive()) return false; }
            else if (types[i].isPrimitive() ? !(args[i] instanceof Number || args[i] instanceof Boolean) : !types[i].isInstance(args[i])) return false;
        }
        return true;
    }
    private static void send(Object packet) { call(value(type("net.createmod.catnip.platform.CatnipServices"), "NETWORK"), "sendToServer", packet); }
}
