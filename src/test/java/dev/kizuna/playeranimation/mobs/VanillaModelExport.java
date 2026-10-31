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
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.monster.illager.IllagerModel;
import net.minecraft.client.model.monster.piglin.AdultPiglinModel;
import net.minecraft.client.model.monster.skeleton.SkeletonModel;
import net.minecraft.client.model.monster.witch.WitchModel;
import net.minecraft.client.model.monster.zombie.DrownedModel;
import net.minecraft.client.model.monster.zombie.ZombieVillagerModel;
import net.minecraft.client.model.npc.VillagerModel;
import org.joml.Matrix4f;

/** 导出原版烘焙方块，供几何生成和逐像素 UV 回归使用。 */
public final class VanillaModelExport {
    private static final int TEXTURE_WIDTH = 64;
    private static final int LEGACY_HEIGHT = 32;
    private static final int MODERN_HEIGHT = 64;
    private static final float OVERLAY_DILATION = 0.25f;
    private static final float PIXELS_PER_BLOCK = 16;
    private static final int WHITE = 0xFFFFFFFF;
    // 1.20.1 类人网格上挂在根下的覆盖层；26.2 把它们移到了所属部件下。
    private static final Set<String> ROOT_OVERLAYS = Set.of(
            "hat", "jacket", "left_sleeve", "right_sleeve", "left_pants", "right_pants");
    private static final Set<String> HUMANOID_MESHES = Set.of(
            "zombie", "skeleton", "piglin", "zombie_villager", "drowned", "drowned_overlay", "stray_overlay");

    private VanillaModelExport() { }

    public static void main(String[] args) throws Exception {
        JsonObject document = export();
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(document) + "\n");
    }

    static JsonObject export() {
        // 直接执行原版模型工厂（与 LayerDefinitions 登记的相同），镜像、dilation 和子部件默认变换均来自原版。
        Map<String, LayerDefinition> models = new LinkedHashMap<>();
        models.put("zombie", LayerDefinition.create(HumanoidModel.createMesh(CubeDeformation.NONE, 0),
                TEXTURE_WIDTH, MODERN_HEIGHT));
        models.put("skeleton", SkeletonModel.createBodyLayer());
        models.put("piglin", AdultPiglinModel.createBodyLayer());
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
        models.forEach((name, model) -> document.add(name, exportModel(model.bakeRoot(), paths(name))));
        return document;
    }

    private static UnaryOperator<String> paths(String model) {
        // 资源里的来源编号与生成脚本的部件规则按 1.20.1 路径写；类人网格的覆盖层导出时还原到根下。
        if (HUMANOID_MESHES.contains(model)) {
            return VanillaModelExport::rootOverlayPath;
        }
        return UnaryOperator.identity();
    }

    static String rootOverlayPath(String path) {
        // 去掉 26.2 加上的父部件一段，覆盖层的子部件（如帽檐）随之上移；村民系的帽子在 1.20.1 就挂在头下，不经此处。
        String[] segments = path.split("/", 3);
        if (segments.length >= 2 && ROOT_OVERLAYS.contains(segments[1])) {
            return path.substring(segments[0].length() + 1);
        }
        return path;
    }

    private static JsonArray exportModel(ModelPart root, UnaryOperator<String> paths) {
        JsonArray cubes = new JsonArray();
        root.visit(new PoseStack(), (entry, path, index, cuboid) -> {
            JsonObject cube = new JsonObject();
            cube.addProperty("part", paths.apply(path.substring(1)));
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
            cuboid.compile(new PoseStack().last(), collector, 0, 0, WHITE);
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

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                              float normalX, float normalY, float normalZ) {
            // 26.2 没有 endVertex，方块编译按整顶点提交；只记录位置、法线与 UV。
            JsonObject vertex = new JsonObject();
            vertex.add("position", vector(x * PIXELS_PER_BLOCK, y * PIXELS_PER_BLOCK, z * PIXELS_PER_BLOCK));
            vertex.add("normal", vector(normalX, normalY, normalZ));
            vertex.add("uv", vector(u, v, 0));
            vertices.add(vertex);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            // 逐属性写入会漏掉顶点边界，导出只接受整顶点入口。
            throw new UnsupportedOperationException("导出只接受整顶点");
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
