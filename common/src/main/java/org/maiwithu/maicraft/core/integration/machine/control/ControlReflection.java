package org.maiwithu.maicraft.core.integration.machine.control;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Optional mod API calls; a missing member is an observation error, never an empty circuit. */
public final class ControlReflection {
    private ControlReflection() {}
    public static Class<?> type(String name) {
        try { return Class.forName(name, false, ControlReflection.class.getClassLoader()); }
        catch (ReflectiveOperationException | LinkageError failure) { throw new IllegalStateException("unavailable API: " + name, failure); }
    }
    public static boolean is(Object value, String type) {
        if (value == null) return false;
        for (Class<?> c = value.getClass(); c != null; c = c.getSuperclass()) if (c.getName().equals(type)) return true;
        return false;
    }
    public static Object field(Object owner, String name) {
        for (Class<?> c = owner instanceof Class<?> t ? t : owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name); field.setAccessible(true);
                return field.get(owner instanceof Class<?> ? null : owner);
            } catch (NoSuchFieldException next) { }
            catch (ReflectiveOperationException | RuntimeException failure) { throw new IllegalStateException("unreadable " + name, failure); }
        }
        throw new IllegalStateException("unavailable field " + name);
    }
    public static Object call(Object owner, String name, Object... args) {
        Class<?> type = owner instanceof Class<?> t ? t : owner.getClass();
        Method[] candidates = Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(name)
                && matches(m.getParameterTypes(), args)).toArray(Method[]::new);
        if (candidates.length != 1) throw new IllegalStateException("unavailable or ambiguous API " + type.getName() + "." + name);
        try { candidates[0].setAccessible(true); return candidates[0].invoke(owner instanceof Class<?> ? null : owner, args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("failed API " + name, failure); }
    }
    public static Object construct(String type, Object... args) {
        for (var constructor : type(type).getConstructors()) if (matches(constructor.getParameterTypes(),args)) {
            try { return constructor.newInstance(args); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("cannot construct " + type,failure); }
        }
        throw new IllegalStateException("unavailable constructor " + type);
    }
    private static boolean matches(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i=0; i<types.length; i++) {
            Class<?> type = types[i];
            if (type.isPrimitive()) type = type == int.class ? Integer.class : type == float.class ? Float.class
                    : type == double.class ? Double.class : type == boolean.class ? Boolean.class : type == long.class ? Long.class : type;
            if (args[i] == null ? types[i].isPrimitive() : !type.isInstance(args[i])) return false;
        }
        return true;
    }
}
