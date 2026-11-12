package net.star1mp.damagetester;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hooks for the automated end-to-end test (CI only). Everything here does nothing unless the game
 * runs with {@code -Ddamagetester.selftest=true}; the test driver itself is only compiled into
 * test builds ({@code -Pselftest}).
 */
public final class SelfTestHook {
    public static final boolean ENABLED = Boolean.getBoolean("damagetester.selftest");
    private static final Logger LOG = LoggerFactory.getLogger("DT-SELFTEST");
    private static final Map<String, String> STATES = new HashMap<>();
    private static Consumer<MinecraftServer> serverTicker;
    private static Runnable clientCheck;

    private SelfTestHook() {
    }

    static void init() {
        if (!ENABLED) {
            return;
        }
        try {
            Class.forName("net.star1mp.damagetester.selftest.SelfTest").getMethod("install").invoke(null);
            LOG.info("[DT-SELFTEST] driver installed");
        } catch (ReflectiveOperationException | LinkageError e) {
            LOG.error("[DT-SELFTEST] driver missing (build with -Pselftest)", e);
        }
    }

    public static void setServerTicker(Consumer<MinecraftServer> ticker) {
        serverTicker = ticker;
    }

    public static void setClientCheck(Runnable check) {
        clientCheck = check;
    }

    public static void serverTick(MinecraftServer server) {
        if (serverTicker != null) {
            serverTicker.accept(server);
        }
    }

    /** Logs a client event (every call). */
    public static void clientEvent(String message) {
        if (ENABLED) {
            LOG.info("[DT-SELFTEST-CLIENT] {}", message);
            runClientCheck();
        }
    }

    /** Logs a client state whenever it changes. */
    public static synchronized void clientState(String key, String state) {
        if (ENABLED && !state.equals(STATES.put(key, state))) {
            LOG.info("[DT-SELFTEST-CLIENT] {} {}", key, state);
            runClientCheck();
        }
    }

    private static void runClientCheck() {
        Runnable check = clientCheck;
        if (check != null) {
            clientCheck = null;
            try {
                check.run();
            } catch (RuntimeException | LinkageError e) {
                LOG.error("[DT-SELFTEST-CLIENT] check crashed", e);
            }
        }
    }
}
