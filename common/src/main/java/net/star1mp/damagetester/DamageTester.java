package net.star1mp.damagetester;

import net.minecraft.resources.ResourceLocation;
import net.star1mp.damagetester.config.DamageTesterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entry point of the Damage Tester mod.
 * Every loader module calls {@link #init()} once during mod construction.
 */
public final class DamageTester {
    public static final String MOD_ID = "damage_tester";
    public static final String MOD_NAME = "Damage Tester";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private DamageTester() {
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private static final java.util.Set<String> RENDER_ERRORS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Rendering problems are logged once per kind instead of crashing or flooding the log. */
    public static void logRenderError(String what, Throwable error) {
        if (RENDER_ERRORS.add(what)) {
            LOG.warn("Failed to draw {} (further errors of this kind are not logged)", what, error);
        }
    }

    public static void init() {
        DamageTesterConfig.load();
        ModContent.init();
        SelfTestHook.init();
    }
}
