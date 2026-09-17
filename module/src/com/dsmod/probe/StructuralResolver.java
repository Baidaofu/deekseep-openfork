package com.dsmod.probe;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural host-symbol lookup: find a method by the shape of its signature instead of by an
 * obfuscated name, so a renamed host build stays usable without a module release.
 *
 * <p>Two backends, in order:</p>
 * <ol>
 *   <li><b>DexKit</b> over the host dex. Nothing is loaded, so a signature that references a type
 *       the runtime cannot resolve (for example {@code android.view.RenderNode} on some hosts)
 *       cannot derail the search. Preferred whenever the bridge is open.</li>
 *   <li><b>Reflection scan</b> over the loaded dex. Slower and it has to load classes, but it needs
 *       no dependency and covers a host where DexKit is unavailable.</li>
 * </ol>
 *
 * <p>The scan isolates every class: one class whose method signatures cannot be resolved is
 * skipped instead of aborting the whole search. That detail is not optional - the first version
 * guarded only {@code Class.forName} and the whole scan failed on a single such class.</p>
 */
final class StructuralResolver {
    /** Additional constraint beyond the parameter types. */
    interface Filter {
        boolean accept(Method method);
    }

    private StructuralResolver() {}

    /**
     * Finds the first method with the exact parameter list {@code params} that also satisfies
     * {@code filter}. Returns null when nothing matches; never throws.
     */
    static Method find(ClassLoader cl, Class<?>[] params, Filter filter, String what) {
        if (params == null || params.length == 0) return null;
        try {
            Method viaDexKit = viaDexKit(cl, params, filter, what);
            if (viaDexKit != null) return viaDexKit;
        } catch (Throwable t) {
            Main.log("[STRUCT] dexkit lookup for " + what + " failed: "
                    + Main.safeThrowableMessage(t));
        }
        return viaReflection(cl, params, filter, what);
    }

    // ── DexKit backend ───────────────────────────────────────────────────────

    private static Method viaDexKit(ClassLoader cl, Class<?>[] params, Filter filter, String what) {
        String[] names = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            names[i] = params[i] == null ? null : params[i].getName();
        }
        String[] ownerAndName = HostSymbols.findMethodByParams(names);
        if (ownerAndName == null) return null;
        try {
            Class<?> owner = Class.forName(ownerAndName[0], false, cl);
            for (Method m : owner.getDeclaredMethods()) {
                if (!m.getName().equals(ownerAndName[1])) continue;
                if (!sameParams(m.getParameterTypes(), params)) continue;
                if (filter != null && !filter.accept(m)) continue;
                m.setAccessible(true);
                Main.log("[STRUCT] " + what + " resolved via DexKit: "
                        + owner.getName() + "." + m.getName());
                return m;
            }
        } catch (Throwable t) {
            Main.log("[STRUCT] " + what + " dexkit hit " + ownerAndName[0] + "."
                    + ownerAndName[1] + " did not verify: " + Main.safeThrowableMessage(t));
        }
        return null;
    }

    // ── reflection backend ───────────────────────────────────────────────────

    private static Method viaReflection(ClassLoader cl, Class<?>[] params, Filter filter, String what) {
        int scanned = 0;
        try {
            List<String> names = listDexClasses(cl);
            for (String name : names) {
                if (name == null) continue;
                if (name.indexOf('.') >= 0) continue;   // defpackage obfuscated classes only
                if (name.length() > 6) continue;        // obfuscated names are short
                Class<?> candidate;
                try {
                    candidate = Class.forName(name, false, cl);
                } catch (Throwable ignored) {
                    continue;
                }
                scanned++;
                try {
                    for (Method m : candidate.getDeclaredMethods()) {
                        if (!sameParams(m.getParameterTypes(), params)) continue;
                        if (filter != null && !filter.accept(m)) continue;
                        m.setAccessible(true);
                        Main.log("[STRUCT] " + what + " resolved by scan: "
                                + candidate.getName() + "." + m.getName());
                        return m;
                    }
                } catch (Throwable ignored) {
                    // Resolving one method signature can throw when it references an unresolvable
                    // type. Skip this class and keep going.
                    continue;
                }
            }
            Main.log("[STRUCT] " + what + " not found (scanned=" + scanned + "/" + names.size() + ")");
        } catch (Throwable t) {
            Main.log("[STRUCT] " + what + " scan failed: " + Main.safeThrowableMessage(t));
        }
        return null;
    }

    static boolean sameParams(Class<?>[] actual, Class<?>[] wanted) {
        if (actual == null || wanted == null || actual.length != wanted.length) return false;
        for (int i = 0; i < wanted.length; i++) {
            if (wanted[i] == null) continue;            // null = wildcard
            if (actual[i] != wanted[i]) return false;
        }
        return true;
    }

    /** Shorthands for the constraints the module needs today. */
    static Filter usableReturn() {
        return new Filter() {
            @Override public boolean accept(Method m) {
                return m.getReturnType() != void.class && !m.getReturnType().isPrimitive();
            }
        };
    }

    static Filter staticReturningObject() {
        return new Filter() {
            @Override public boolean accept(Method m) {
                return Modifier.isStatic(m.getModifiers())
                        && m.getReturnType() == Object.class;
            }
        };
    }

    /** Every class name in the host's own dex elements. */
    static List<String> listDexClasses(ClassLoader cl) throws Exception {
        ArrayList<String> out = new ArrayList<String>();
        Class<?> bdcl = Class.forName("dalvik.system.BaseDexClassLoader");
        java.lang.reflect.Field pathListField = bdcl.getDeclaredField("pathList");
        pathListField.setAccessible(true);
        Object pathList = pathListField.get(cl);
        java.lang.reflect.Field dexElementsField =
                pathList.getClass().getDeclaredField("dexElements");
        dexElementsField.setAccessible(true);
        Object[] elements = (Object[]) dexElementsField.get(pathList);
        for (Object element : elements) {
            java.lang.reflect.Field dexFileField =
                    element.getClass().getDeclaredField("dexFile");
            dexFileField.setAccessible(true);
            Object dexFile = dexFileField.get(element);
            if (dexFile == null) continue;
            Method entries = dexFile.getClass().getDeclaredMethod("entries");
            entries.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Enumeration<String> enumeration =
                    (java.util.Enumeration<String>) entries.invoke(dexFile);
            while (enumeration.hasMoreElements()) out.add(enumeration.nextElement());
        }
        return out;
    }
}
