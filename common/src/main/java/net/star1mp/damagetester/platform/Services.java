package net.star1mp.damagetester.platform;

import java.util.ServiceLoader;
import net.star1mp.damagetester.DamageTester;

public final class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    private static <T> T load(Class<T> clazz) {
        T service = ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to load service for " + clazz.getName()));
        DamageTester.LOG.debug("Loaded {} for service {}", service, clazz);
        return service;
    }
}
