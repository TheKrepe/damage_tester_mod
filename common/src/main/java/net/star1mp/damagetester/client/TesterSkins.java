package net.star1mp.damagetester.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.DyeColor;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import com.mojang.blaze3d.platform.NativeImage;

/** Picks the texture and cloth color for a tester's skin. Client only. */
public final class TesterSkins {
    /** Grayscale "carving" mask: 200 = plain wood, darker = carved, brighter = highlight, alpha 0 = empty. */
    private static final Identifier WOOD_MASK = DamageTester.id("textures/entity/damage_tester_wood_mask.png");
    private static final Map<String, Identifier> PLANK_TEXTURES = new HashMap<>();
    private static final int RAINBOW_TICKS_PER_COLOR = 25;

    private TesterSkins() {
    }

    public static void apply(DamageTesterEntity entity, DamageTesterRenderState state) {
        state.texture = DamageTesterRenderer.TEXTURE;
        state.clothColor = 0;
        state.glowTexture = null;
        String skin = entity.getSkin();

        // Name tag easter egg, works like jeb_ sheep.
        if (entity.hasCustomName() && "jeb_".equals(entity.getName().getString())) {
            state.texture = DamageTesterRenderer.TEXTURE_NO_CLOTH;
            state.clothColor = rainbow(entity.tickCount + entity.getId(), state.ageInTicks - (int) state.ageInTicks);
            return;
        }
        if (skin.equals(DamageTesterEntity.SKIN_STRAW)) {
            state.texture = DamageTesterRenderer.TEXTURE_STRAW;
        } else if (skin.equals(DamageTesterEntity.SKIN_JACK)) {
            state.texture = DamageTesterRenderer.TEXTURE_JACK;
            state.glowTexture = GlowLayer.JACK_GLOW;
        } else if (skin.startsWith(DamageTesterEntity.SKIN_PLANKS_PREFIX)) {
            state.texture = plankTexture(skin.substring(DamageTesterEntity.SKIN_PLANKS_PREFIX.length()));
        } else if (skin.startsWith(DamageTesterEntity.SKIN_DYE_PREFIX)) {
            DyeColor color = DyeColor.byName(skin.substring(DamageTesterEntity.SKIN_DYE_PREFIX.length()), DyeColor.WHITE);
            state.texture = DamageTesterRenderer.TEXTURE_NO_CLOTH;
            state.clothColor = ARGB.opaque(color.getTextureDiffuseColor());
        }
    }

    private static int rainbow(int ticks, float partial) {
        DyeColor[] colors = DyeColor.values();
        int index = ticks / RAINBOW_TICKS_PER_COLOR;
        int from = colors[index % colors.length].getTextureDiffuseColor();
        int to = colors[(index + 1) % colors.length].getTextureDiffuseColor();
        float t = ((ticks % RAINBOW_TICKS_PER_COLOR) + partial) / RAINBOW_TICKS_PER_COLOR;
        int r = (int) (ARGB.red(from) + (ARGB.red(to) - ARGB.red(from)) * t);
        int g = (int) (ARGB.green(from) + (ARGB.green(to) - ARGB.green(from)) * t);
        int b = (int) (ARGB.blue(from) + (ARGB.blue(to) - ARGB.blue(from)) * t);
        return ARGB.color(255, r, g, b);
    }

    /**
     * Builds (once) a tester texture out of any planks block's texture, so modded planks work too.
     * The texture is looked up at {@code <namespace>:textures/block/<path>.png}.
     */
    private static Identifier plankTexture(String itemId) {
        return PLANK_TEXTURES.computeIfAbsent(itemId, id -> {
            Identifier item = Identifier.tryParse(id);
            if (item == null) {
                return DamageTesterRenderer.TEXTURE;
            }
            Minecraft minecraft = Minecraft.getInstance();
            Identifier planksTexture = Identifier.fromNamespaceAndPath(item.getNamespace(), "textures/block/" + item.getPath() + ".png");
            try (NativeImage planks = read(minecraft, planksTexture); NativeImage mask = read(minecraft, WOOD_MASK)) {
                if (planks == null || mask == null) {
                    return DamageTesterRenderer.TEXTURE;
                }
                int tile = planks.getWidth(); // works for any resource pack resolution; animated strips use the first frame
                NativeImage out = new NativeImage(mask.getWidth(), mask.getHeight(), true);
                for (int y = 0; y < mask.getHeight(); y++) {
                    for (int x = 0; x < mask.getWidth(); x++) {
                        int m = mask.getPixel(x, y);
                        if (ARGB.alpha(m) == 0) {
                            out.setPixel(x, y, 0);
                            continue;
                        }
                        int px = planks.getPixel((x % 16) * tile / 16, (y % 16) * tile / 16);
                        float shade = ARGB.red(m) / 200.0F;
                        out.setPixel(x, y, ARGB.color(255,
                                Math.min(255, (int) (ARGB.red(px) * shade)),
                                Math.min(255, (int) (ARGB.green(px) * shade)),
                                Math.min(255, (int) (ARGB.blue(px) * shade))));
                    }
                }
                Identifier dynamicId = DamageTester.id("dynamic/planks/" + item.getNamespace() + "/" + item.getPath());
                minecraft.getTextureManager().register(dynamicId, new DynamicTexture(dynamicId::toString, out));
                return dynamicId;
            } catch (Exception e) {
                DamageTester.LOG.warn("Could not build a damage tester skin for {}", id, e);
                return DamageTesterRenderer.TEXTURE;
            }
        });
    }

    private static NativeImage read(Minecraft minecraft, Identifier location) throws java.io.IOException {
        java.util.Optional<Resource> resource = minecraft.getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            return null;
        }
        try (java.io.InputStream stream = resource.get().open()) {
            return NativeImage.read(stream);
        }
    }
}
