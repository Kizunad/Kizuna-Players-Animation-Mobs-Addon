package dev.kizuna.playeranimation.mobs;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.model.DrownedModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.PiglinModel;
import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.WitchModel;
import net.minecraft.client.model.ZombieVillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 导出原版烘焙方块，供几何生成和逐像素 UV 回归使用。 */
public final class VanillaModelExport {
    private static final int TEXTURE_WIDTH = 64;
    private static final int LEGACY_HEIGHT = 32;
    private static final int MODERN_HEIGHT = 64;
    private static final float OVERLAY_DILATION = 0.25f;
    private static final float PIXELS_PER_BLOCK = 16;

    private VanillaModelExport() { }

    public static void main(String[] args) throws Exception {
        JsonObject document = export();
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(document) + "\n");
    }

    static JsonObject export() {
        // 直接执行 1.20.1 的模型工厂，镜像、dilation 和子部件默认变换均来自原版。
        Map<String, LayerDefinition> models = new LinkedHashMap<>();
        models.put("zombie", LayerDefinition.create(HumanoidModel.createMesh(CubeDeformation.NONE, 0),
                TEXTURE_WIDTH, MODERN_HEIGHT));
        models.put("skeleton", SkeletonModel.createBodyLayer());
        models.put("piglin", LayerDefinition.create(PiglinModel.createMesh(CubeDeformation.NONE),
                TEXTURE_WIDTH, MODERN_HEIGHT));
        models.put("villager", LayerDefinition.create(VillagerModel.createBodyModel(),
                TEXTURE_WIDTH, MODERN_HEIGHT));
        models.put("illager", IllagerModel.createBodyLayer());
        models.put("witch", WitchModel.createBodyLayer());
        models.put("zombie_villager", ZombieVillagerModel.createBodyLayer());
        models.put("drowned", DrownedModel.createBodyLayer(CubeDeformation.NONE));
        models.put("drowned_overlay", DrownedModel.createBodyLayer(new CubeDeformation(OVERLAY_DILATION)));
        models.put("stray_overlay", LayerDefinition.create(
                HumanoidModel.createMesh(new CubeDeformation(OVERLAY_DILATION), 0), TEXTURE_WIDTH, LEGACY_HEIGHT));
        JsonObject document = new JsonObject();
        models.forEach((name, model) -> document.add(name, exportModel(model.bakeRoot())));
        return document;
    }

    private static JsonArray exportModel(ModelPart root) {
        JsonArray cubes = new JsonArray();
        root.visit(new PoseStack(), (entry, path, index, cuboid) -> {
            JsonObject cube = new JsonObject();
            cube.addProperty("part", path.substring(1));
            cube.addProperty("index", index);
            cube.add("min", vector(cuboid.minX, cuboid.minY, cuboid.minZ));
            cube.add("max", vector(cuboid.maxX, cuboid.maxY, cuboid.maxZ));
            Matrix4f pixels = new Matrix4f(entry.pose());
            pixels.m30(pixels.m30() * PIXELS_PER_BLOCK);
            pixels.m31(pixels.m31() * PIXELS_PER_BLOCK);
            pixels.m32(pixels.m32() * PIXELS_PER_BLOCK);
            float[] matrix = new float[16];
            pixels.get(matrix);
            JsonArray transform = new JsonArray();
            for (float value : matrix) {
                transform.add(value);
            }
            cube.add("transform", transform);
            Collector collector = new Collector();
            cuboid.compile(new PoseStack().last(), collector, 0, 0, 1, 1, 1, 1);
            cube.add("vertices", collector.vertices);
            cubes.add(cube);
        });
        return cubes;
    }

    private static JsonArray vector(float x, float y, float z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    private static final class Collector implements VertexConsumer {
        private final JsonArray vertices = new JsonArray();
        private final Vector3f point = new Vector3f();
        private float u;
        private float v;
        private final Vector3f normal = new Vector3f();

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            point.set((float) x * PIXELS_PER_BLOCK, (float) y * PIXELS_PER_BLOCK,
                    (float) z * PIXELS_PER_BLOCK);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            normal.set(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            JsonObject vertex = new JsonObject();
            vertex.add("position", vector(point.x, point.y, point.z));
            vertex.add("normal", vector(normal.x, normal.y, normal.z));
            vertex.add("uv", vector(u, v, 0));
            vertices.add(vertex);
        }

        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public void defaultColor(int r, int g, int b, int a) { }
        @Override public void unsetDefaultColor() { }
    }
}
