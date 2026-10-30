"""对原版每个方块逐面拼回切段 UV，拒绝缺面、重叠和越界。"""
import collections
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def pixels(rect):
    u,v,w,h = rect
    # 四分之一像素网格同时覆盖骷髅半像素指段和镜像面。
    left,right = sorted((round(u*4),round((u+w)*4)))
    top,bottom = sorted((round(v*4),round((v+h)*4)))
    return [(x,y) for x in range(left,right) for y in range(top,bottom)]


class VanillaGeometryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = json.loads((ROOT/"build/vanilla-models.json").read_text())
        cls.geometries = json.loads((ROOT/"src/main/resources/assets/kizuna_players_animation_mobs/geo/mob_skeletons.geo.json").read_text())["minecraft:geometry"]

    def test_every_split_uv_reassembles_original_cuboid(self):
        for geometry in self.geometries:
            description = geometry["description"]
            width,height = description["texture_width"],description["texture_height"]
            actual = collections.defaultdict(collections.Counter)
            for bone in geometry["bones"]:
                for cube in bone.get("cubes",[]):
                    for face in cube["uv"].values():
                        actual[cube["source"]].update(pixels(face["uv"]+face["uv_size"]))
            for source_id,uv in actual.items():
                model,part,index = source_id.split(":")
                source = next(c for c in self.sources[model] if c["part"] == part and c["index"] == int(index))
                expected = collections.Counter()
                vertices = source["vertices"]
                for i in range(0,len(vertices),4):
                    quad = vertices[i:i+4]
                    us,vs = [p["uv"][0]*width for p in quad],[p["uv"][1]*height for p in quad]
                    expected.update(pixels([min(us),min(vs),max(us)-min(us),max(vs)-min(vs)]))
                with self.subTest(geometry=description["identifier"],source=source_id):
                    self.assertEqual(expected,uv)

    def test_geometry_covers_required_bones_and_exact_limb_volume(self):
        names = {"root","pelvis","waist","chest","head"}
        names |= {side+"_"+part for side in ("r","l") for part in
                  ("upper_arm","forearm","hand","thumb","fingers_a","fingers_b","item","thigh","shin","foot","toe")}
        for geometry in self.geometries:
            with self.subTest(geometry=geometry["description"]["identifier"]):
                self.assertEqual(names,{b["name"] for b in geometry["bones"]})
                volumes = collections.defaultdict(float)
                for bone in geometry["bones"]:
                    for cube in bone.get("cubes",[]):
                        x,y,z = cube["size"]
                        self.assertGreater(min(x,y,z),0)
                        volumes[cube["source"]] += x*y*z
                for source_id,volume in volumes.items():
                    model,part,index = source_id.split(":")
                    source = next(c for c in self.sources[model] if c["part"] == part and c["index"] == int(index))
                    size = [source["max"][i]-source["min"][i] for i in range(3)]
                    inflation = source["min"][0] - min(v["position"][0] for v in source["vertices"])
                    if inflation < 0:
                        # 女巫鼻痣的收缩已烘焙，体积应等于原版实际顶点围成的体积。
                        size = [value + 2 * inflation for value in size]
                    self.assertAlmostEqual(size[0]*size[1]*size[2],volume,places=4,msg=source_id)

    def test_distinct_head_details_and_overlay_dilation(self):
        geometries = {g["description"]["identifier"].split(".")[-1]:g for g in self.geometries}
        def head(name):
            return next(b for b in geometries[name]["bones"] if b["name"] == "head")["cubes"]
        self.assertTrue(any(c["part"] == "head/nose" for c in head("villager")))
        self.assertTrue(any(c["part"] == "head/nose/mole" for c in head("witch")))
        self.assertTrue(any(c["part"].startswith("head/hat") for c in head("witch")))
        ears = [c for c in head("piglin") if c["part"].endswith("_ear")]
        self.assertEqual(2,len(ears))
        self.assertEqual({-30,30},{round(c["rotation"][2]) for c in ears})
        for name in ("drowned_overlay","stray_overlay"):
            self.assertTrue(all(abs(c["inflate"]-0.25)<1e-5 for b in geometries[name]["bones"] for c in b.get("cubes",[])))


if __name__ == "__main__":
    unittest.main()
