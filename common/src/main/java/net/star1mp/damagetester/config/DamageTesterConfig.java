package net.star1mp.damagetester.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.platform.Services;

/**
 * Tiny JSON config stored in {@code config/damage_tester.json}.
 * Kept loader-agnostic on purpose: no config library is required.
 */
public final class DamageTesterConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Values values = new Values();

    private DamageTesterConfig() {
    }

    public static Values get() {
        return values;
    }

    public static void load() {
        Path file = Services.PLATFORM.getConfigDir().resolve(DamageTester.MOD_ID + ".json");
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    Values loaded = GSON.fromJson(reader, Values.class);
                    if (loaded != null) {
                        values = loaded;
                    }
                }
            }
            values.sanitize();
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(values, writer);
            }
        } catch (Exception e) {
            DamageTester.LOG.warn("Could not read or write {}, using defaults", file, e);
            values = new Values();
        }
    }

    public static final class Values {
        /** Client: show floating damage numbers above the tester. */
        public boolean showDamageNumbers = true;
        /** Client: show numbers in hearts (1 heart = 2 damage) instead of raw damage points. */
        public boolean damageInHearts = false;
        /** Client: how strongly the tester wobbles when hit. 0 disables the animation. */
        public float wobbleIntensity = 1.0F;
        /** Server: send damage / DPS summary to the attacking player's action bar. */
        public boolean showDpsMessage = true;
        /** Server: seconds without hits after which the DPS counter resets. */
        public int combatResetSeconds = 5;
        /** Server: burst of hay particles on every hit. */
        public boolean hayParticles = true;

        void sanitize() {
            wobbleIntensity = Math.max(0.0F, Math.min(3.0F, wobbleIntensity));
            combatResetSeconds = Math.max(1, Math.min(120, combatResetSeconds));
        }
    }
}
