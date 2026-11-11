package net.star1mp.damagetester;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Creates a {@link BlockEntityType}. Its constructor is not public on this Minecraft version
 * (NeoForge opens it, Fabric does not), so it is called reflectively. Only parameter shapes are
 * used, never names, so this also works with Fabric's remapped runtime names.
 */
public final class BlockEntityTypes {
    private BlockEntityTypes() {
    }

    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> BlockEntityType<T> create(BiFunction<BlockPos, BlockState, T> factory, Block... blocks) {
        try {
            for (Constructor<?> ctor : BlockEntityType.class.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length != 2 || !params[0].isInterface() || !Set.class.isAssignableFrom(params[1])) {
                    continue;
                }
                Class<?> supplierType = params[0];
                Object supplier = Proxy.newProxyInstance(supplierType.getClassLoader(), new Class<?>[]{supplierType}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> "BlockEntitySupplier";
                        };
                    }
                    if (method.isDefault()) {
                        return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    return factory.apply((BlockPos) args[0], (BlockState) args[1]);
                });
                ctor.setAccessible(true);
                return (BlockEntityType<T>) ctor.newInstance(supplier, Set.of(blocks));
            }
            throw new IllegalStateException("No BlockEntityType constructor found");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create a block entity type", e);
        }
    }
}
