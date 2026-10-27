package dev.kizuna.playeranimation.mobs;

import java.io.IOException;
import java.util.List;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/** 读取附属几何并在资源重载边界重新注册到 PA。 */
final class MobGeometryAssets implements SimpleSynchronousResourceReloadListener {
    private static final String NAMESPACE = MobAnimationPackets.MOD_ID;
    private static final List<Registration> REGISTRATIONS = List.of(
            new Registration("geometry.mob.zombie", "geo/mob_skeletons.geo.json",
                    List.of("minecraft:zombie", "minecraft:husk"), ""),
            new Registration("geometry.mob.skeleton", "geo/mob_skeletons.geo.json",
                    List.of("minecraft:skeleton"), ""),
            new Registration("geometry.mob.skeleton", "geo/mob_skeletons.geo.json",
                    List.of("minecraft:stray"), "geometry.mob.stray_overlay"),
            new Registration("geometry.mob.drowned", "geo/mob_skeletons.geo.json",
                    List.of("minecraft:drowned"), "geometry.mob.drowned_overlay"),
            new Registration("geometry.mob.zombie_villager", "geo/mob_skeletons.geo.json",
                    List.of("minecraft:zombie_villager"), ""),
            new Registration("geometry.mob.wither_skeleton", "geo/mob_skeletons.geo.json",
                    List.of("minecraft:wither_skeleton"), ""),
            new Registration("geometry.mob.piglin", "geo/mob_skeletons.geo.json",
                    List.of("minecraft:piglin", "minecraft:piglin_brute", "minecraft:zombified_piglin"), ""),
            new Registration("geometry.mob.villager", "geo/mob_skeletons.geo.json",
                    List.of("minecraft:villager", "minecraft:wandering_trader"), ""),
            new Registration("geometry.mob.illager", "geo/mob_skeletons.geo.json",
                    List.of("minecraft:vindicator", "minecraft:pillager", "minecraft:evoker", "minecraft:illusioner"),
                    "")
    );

    @Override
    public Identifier getFabricId() {
        return new Identifier(NAMESPACE, "mob_geometry");
    }

    @Override
    public void reload(ResourceManager manager) {
        for (Registration registration : REGISTRATIONS) {
            Identifier resourceId = new Identifier(NAMESPACE, registration.path());
            try (var stream = manager.getResourceOrThrow(resourceId).getInputStream()) {
                byte[] geometry = stream.readAllBytes();
                MobAnimationClient.registerGeometry(registration.geometryId(), geometry, registration.entityTypes(),
                        registration.overlayGeometryId());
            } catch (IOException exception) {
                throw new IllegalStateException("无法读取生物几何: " + resourceId, exception);
            }
        }
    }

    private record Registration(String geometryId, String path, List<String> entityTypes, String overlayGeometryId) { }
}
