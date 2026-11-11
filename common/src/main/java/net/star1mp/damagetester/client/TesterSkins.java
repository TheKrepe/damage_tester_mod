package net.star1mp.damagetester.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.DyeColor;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import org.jetbrains.annotations.Nullable;

/** Picks the texture and cloth color for a tester's skin. Client only. */
public final class TesterSkins {
    /** Grayscale "carving" mask: 200 = plain wood, darker = carved, brighter = highlight, alpha 0 = empty. */
    private static final ResourceLocation WOOD_MASK = DamageTester.id("textures/entity/damage_tester_wood_mask.png");
    private static final Map<String, ResourceLocation> PLANK_TEXTURES = new HashMap<>();
    private static final int RAINBOW_TICKS_PER_COLOR = 25;

    private TesterSkins() {
    }

    private static boolean isJeb(DamageTesterEntity entity) {
        return entity.hasCustomName() && "jeb_".equals(entity.getName().getString());
    }

    public static ResourceLocation texture(DamageTesterEntity entity) {
        if (isJeb(entity)) {
            return DamageTesterRenderer.TEXTURE_NO_CLOTH;
        }
        String skin = entity.getSkin();
        if (skin.equals(DamageTesterEntity.SKIN_STRAW)) {
            return DamageTesterRenderer.TEXTURE_STRAW;
        } else if (skin.equals(DamageTesterEntity.SKIN_JACK)) {
            return DamageTesterRenderer.TEXTURE_JACK;
        } else if (skin.startsWith(DamageTesterEntity.SKIN_PLANKS_PREFIX)) {
            return plankTexture(skin.substring(DamageTesterEntity.SKIN_PLANKS_PREFIX.length()));
        } else if (skin.startsWith(DamageTesterEntity.SKIN_DYE_PREFIX)) {
            return DamageTesterRenderer.TEXTURE_NO_CLOTH;
        }
        return DamageTesterRenderer.TEXTURE;
    }

    /** ARGB color of the cloth overlay, or 0 when the cloth is part of the main texture. */
    public static int clothColor(DamageTesterEntity entity, float partialTick) {
        if (isJeb(entity)) {
            return rainbow(entity.tickCount + entity.getId(), partialTick);
        }
        String skin = entity.getSkin();
        if (skin.startsWith(DamageTesterEntity.SKIN_DYE_PREFIX)) {
            DyeColor color = DyeColor.byName(skin.substring(DamageTesterEntity.SKIN_DYE_PREFIX.length()), DyeColor.WHITE);
            return FastColor.ARGB32.opaque(color.getTextureDiffuseColor());
        }
        return 0;
    }

    /** Optional emissive texture drawn full-bright on top (e.g. the jack o'lantern's glowing face). */
    @Nullable
    public static ResourceLocation glowTexture(DamageTesterEntity entity) {
        return !isJeb(entity) && entity.getSkin().equals(DamageTesterEntity.SKIN_JACK) ? GlowLayer.JACK_GLOW : null;
    }

    private static int rainbow(int ticks, float partial) {
        DyeColor[] colors = DyeColor.values();
        int index = ticks / RAINBOW_TICKS_PER_COLOR;
        int from = colors[index % colors.length].getTextureDiffuseColor();
        int to = colors[(index + 1) % colors.length].getTextureDiffuseColor();
        float t = ((ticks % RAINBOW_TICKS_PER_COLOR) + partial) / RAINBOW_TICKS_PER_COLOR;
        int r = (int) (FastColor.ARGB32.red(from) + (FastColor.ARGB32.red(to) - FastColor.ARGB32.red(from)) * t);
        int g = (int) (FastColor.ARGB32.green(from) + (FastColor.ARGB32.green(to) - FastColor.ARGB32.green(from)) * t);
        int b = (int) (FastColor.ARGB32.blue(from) + (FastColor.ARGB32.blue(to) - FastColor.ARGB32.blue(from)) * t);
        return FastColor.ARGB32.color(255, r, g, b);
    }

    // NativeImage stores pixels as ABGR on this version.
    private static int alpha(int abgr) {
        return (abgr >>> 24) & 0xFF;
    }

    private static int red(int abgr) {
        return abgr & 0xFF;
    }

    private static int green(int abgr) {
        return (abgr >> 8) & 0xFF;
    }

    private static int blue(int abgr) {
        return (abgr >> 16) & 0xFF;
    }

    private static int abgr(int a, int r, int g, int b) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * Builds (once) a tester texture out of any planks block's texture, so modded planks work too.
     * The texture is looked up at {@code <namespace>:textures/block/<path>.png}.
     */
    private static ResourceLocation plankTexture(String itemId) {
        return PLANK_TEXTURES.computeIfAbsent(itemId, id -> {
            ResourceLocation item = ResourceLocation.tryParse(id);
            if (item == null) {
                return DamageTesterRenderer.TEXTURE;
            }
            Minecraft minecraft = Minecraft.getInstance();
            ResourceLocation planksTexture = ResourceLocation.fromNamespaceAndPath(item.getNamespace(), "textures/block/" + item.getPath() + ".png");
            try (NativeImage planks = read(minecraft, planksTexture); NativeImage mask = read(minecraft, WOOD_MASK)) {
                if (planks == null || mask == null) {
                    return DamageTesterRenderer.TEXTURE;
                }
                int tile = planks.getWidth(); // works for any resource pack resolution; animated strips use the first frame
                NativeImage out = new NativeImage(mask.getWidth(), mask.getHeight(), true);
                for (int y = 0; y < mask.getHeight(); y++) {
                    for (int x = 0; x < mask.getWidth(); x++) {
                        int m = mask.getPixelRGBA(x, y);
                        if (alpha(m) == 0) {
                            out.setPixelRGBA(x, y, 0);
                            continue;
                        }
                        int px = planks.getPixelRGBA((x % 16) * tile / 16, (y % 16) * tile / 16);
                        float shade = red(m) / 200.0F;
                        out.setPixelRGBA(x, y, abgr(255,
                                Math.min(255, (int) (red(px) * shade)),
                                Math.min(255, (int) (green(px) * shade)),
                                Math.min(255, (int) (blue(px) * shade))));
                    }
                }
                ResourceLocation dynamicId = DamageTester.id("dynamic/planks/" + item.getNamespace() + "/" + item.getPath());
                minecraft.getTextureManager().register(dynamicId, new DynamicTexture(out));
                return dynamicId;
            } catch (Exception e) {
                DamageTester.LOG.warn("Could not build a damage tester skin for {}", id, e);
                return DamageTesterRenderer.TEXTURE;
            }
        });
    }

    @Nullable
    private static NativeImage read(Minecraft minecraft, ResourceLocation location) throws java.io.IOException {
        java.util.Optional<Resource> resource = minecraft.getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            return null;
        }
        try (java.io.InputStream stream = resource.get().open()) {
            return NativeImage.read(stream);
        }
    }
}
