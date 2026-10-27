"""从 Minecraft 1.20.1 模型工厂的导出顶点裁切几何，保留原版逐面 UV。"""
import argparse
import copy
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/kizuna_players_animation_mobs/geo/mob_skeletons.geo.json"
FACES = {
    "north": lambda u, v: (1-u, 1-v, 0),
    "south": lambda u, v: (u, 1-v, 1),
    "west": lambda u, v: (0, 1-v, u),
    "east": lambda u, v: (1, 1-v, 1-u),
    "up": lambda u, v: (u, 1, v),
    "down": lambda u, v: (u, 0, 1-v),
}
NORMALS = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0),
           "east": (1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}


def clean(values):
    return [round(x, 6) for x in values]


def model_point(point):
    return (point[0], 24-point[1], point[2])


def json_origin(low, high):
    return clean((-high[0], low[1], low[2]))


def boxes(part, low, high):
    """PA classic 的切口按原版部件的实际宽度落位；指头只切分现有体积。"""
    if part.startswith("head") or part in ("hat", "hat/hat_rim"):
        return [("head", low, high)]
    if part.startswith("body") or part == "jacket":
        cuts = [low[1]] + [v for v in (16, 20) if low[1] < v < high[1]] + [high[1]]
        result = []
        for a, b in zip(cuts, cuts[1:]):
            bone = "pelvis" if b <= 16 else "waist" if b <= 20 else "chest"
            result.append((bone, (low[0], a, low[2]), (high[0], b, high[2])))
        return result
    side = "r" if part.startswith("right") else "l"
    if any(word in part for word in ("arm", "sleeve")):
        result = []
        for bone, a, b in (("upper_arm", 19, 24), ("forearm", 15, 19), ("hand", 14, 15)):
            a, b = max(a, low[1]), min(b, high[1])
            if a < b:
                result.append((side+"_"+bone, (low[0], a, low[2]), (high[0], b, high[2])))
        if low[1] < 14:
            depth = high[2]-low[2]
            for bone, a, b in (("thumb", 0, 0.25), ("fingers_a", 0.25, 0.75), ("fingers_b", 0.75, 1)):
                result.append((side+"_"+bone, (low[0], low[1], low[2]+a*depth),
                               (high[0], min(14, high[1]), low[2]+b*depth)))
        return result
    result = []
    for bone, a, b in (("thigh", 6, 12), ("shin", 1, 6)):
        a, b = max(a, low[1]), min(b, high[1])
        if a < b:
            result.append((side+"_"+bone, (low[0], a, low[2]), (high[0], b, high[2])))
    if low[1] < 1:
        middle = (low[2]+high[2])/2
        result += [(side+"_foot", (low[0], low[1], middle), (high[0], 1, high[2])),
                   (side+"_toe", low, (high[0], 1, middle))]
    return result


def interpolate_uv(quad, point):
    # UV 对原版平面坐标是仿射函数，镜像由原始四个顶点决定。
    p, q, _, s = [v["position"] for v in quad]
    e, f = [q[i]-p[i] for i in range(3)], [s[i]-p[i] for i in range(3)]
    a = sum((point[i]-p[i])*e[i] for i in range(3))/sum(x*x for x in e)
    b = sum((point[i]-p[i])*f[i] for i in range(3))/sum(x*x for x in f)
    return [quad[0]["uv"][i] + a*(quad[1]["uv"][i]-quad[0]["uv"][i])
            + b*(quad[3]["uv"][i]-quad[0]["uv"][i]) for i in range(2)]


def encode_uv(corners, width, height, face):
    # 四种旋转覆盖原版面朝向；顶底遵循 PA 的 Bedrock 导出约定。
    points = ((0, 0), (1, 0), (1, 1), (0, 1))
    for rotation in range(4):
        rotated = []
        for u, v in points:
            for _ in range(rotation):
                u, v = v, 1-u
            rotated.append((u, v))
        start, span = [], []
        for axis, scale in enumerate((width, height)):
            zero = next(i for i,p in enumerate(rotated) if p[axis] == 0)
            one = next(i for i,p in enumerate(rotated) if p[axis] == 1)
            start.append(corners[zero][axis]*scale)
            span.append((corners[one][axis]-corners[zero][axis])*scale)
        if all(abs((start[j]+span[j]*rotated[i][j])/(width,height)[j]-corners[i][j]) < 1e-5
               for i in range(4) for j in range(2)):
            if face in ("up", "down"):
                start = [start[i]+span[i] for i in range(2)]
                span = [-x for x in span]
            return {"uv": clean(start), "uv_size": clean(span), "uv_rotation": rotation*90}
    raise ValueError("无法表示原版 UV 四边形")


def append_cube(bones, source, model_name, width, height, part=None, mapping=None, source_clip=None):
    part = part or source["part"]
    translation = source["transform"][12:15]
    source_low, source_high = source["min"], source["max"]
    # 默认映射只改变原点和 Y 方向；旋转作为静态 cube 变换单独保存。
    def default_map(p):
        return model_point([p[i]+translation[i] for i in range(3)])
    project = mapping or default_map
    raw_low, raw_high = source_clip or (source_low, source_high)
    corners = [project((x,y,z)) for x in (raw_low[0],raw_high[0])
               for y in (raw_low[1],raw_high[1]) for z in (raw_low[2],raw_high[2])]
    low = tuple(min(p[i] for p in corners) for i in range(3))
    high = tuple(max(p[i] for p in corners) for i in range(3))
    # 从三条轴向边恢复逆映射，分臂展开只含轴交换和正负号。
    origin = project((0,0,0))
    columns = [[project(tuple(1 if j == axis else 0 for j in range(3)))[i]-origin[i]
                for i in range(3)] for axis in range(3)]
    def unproject(p):
        return [sum((p[i]-origin[i])*column[i] for i in range(3)) for column in columns]
    quads = [source["vertices"][i:i+4] for i in range(0,len(source["vertices"]),4)]
    inflation = max(abs(min(v["position"][0] for v in source["vertices"])-source_low[0]), 0)
    for bone, seg_low, seg_high in boxes(part, low, high):
        faces = {}
        for face, unit in FACES.items():
            normal = NORMALS[face]
            axis = next(i for i,x in enumerate(normal) if x)
            plane = seg_high[axis] if normal[axis] > 0 else seg_low[axis]
            original_plane = high[axis] if normal[axis] > 0 else low[axis]
            if abs(plane-original_plane) > 1e-5:
                continue
            raw_points = [unproject([seg_low[i]+(seg_high[i]-seg_low[i])*unit(u,v)[i] for i in range(3)])
                          for u,v in ((0,0),(1,0),(1,1),(0,1))]
            raw_axis = next(i for i in range(3) if max(p[i] for p in raw_points)-min(p[i] for p in raw_points) < 1e-5)
            raw_plane = raw_points[0][raw_axis]
            if abs(raw_plane-source_low[raw_axis]) > 1e-5 and abs(raw_plane-source_high[raw_axis]) > 1e-5:
                continue
            sign = -1 if abs(raw_plane-source_low[raw_axis]) < 1e-5 else 1
            quad = next(q for q in quads if q[0]["normal"][raw_axis]*sign > 0.9)
            def dilate(p):
                return [p[i]+inflation*(2*(p[i]-source_low[i])/(source_high[i]-source_low[i])-1)
                        for i in range(3)]
            faces[face] = encode_uv([interpolate_uv(quad, dilate(p)) for p in raw_points],width,height,face)
        if not faces:
            continue
        cube = {"origin": json_origin(seg_low,seg_high), "size": clean([seg_high[i]-seg_low[i] for i in range(3)]),
                "inflate": round(inflation,6), "inflate_origin": json_origin(low,high),
                "inflate_size": clean([high[i]-low[i] for i in range(3)]), "part": part,
                "source": f'{model_name}:{source["part"]}:{source["index"]}', "uv": faces}
        m = source["transform"]
        if mapping is None and any(abs(m[i]-v)>1e-5 for i,v in ((0,1),(5,1),(10,1),(1,0),(2,0),(4,0),(6,0),(8,0),(9,0))):
            ry = math.atan2(-m[2], math.hypot(m[0],m[1]))
            rx = math.atan2(m[6],m[10])
            rz = math.atan2(m[1],m[0])
            cube["rotation"] = clean([math.degrees(rx),math.degrees(ry),math.degrees(rz)])
            pivot = model_point(translation)
            cube["pivot"] = clean([-pivot[0],pivot[1],pivot[2]])
        bones[bone].setdefault("cubes",[]).append(cube)


def geometry(name, sources, template):
    bones = {b["name"]: {k:copy.deepcopy(v) for k,v in b.items() if k != "cubes"} for b in template["bones"]}
    model_name = name
    if name == "wither_skeleton":
        model_name = "skeleton"
    cubes = sources[model_name]
    legacy = name in ("skeleton","wither_skeleton","stray_overlay")
    height = 32 if legacy else 64
    for source in cubes:
        part = source["part"]
        if part in ("cloak","ear") or part.startswith("arms"):
            continue
        if part == "hat" and name in ("zombie","drowned","drowned_overlay","skeleton","wither_skeleton","stray_overlay"):
            continue  # 原版僵尸和骷髅关闭 hat，只有本体头盒可见。
        if part.startswith("head/hat") and name == "illager":
            continue  # 原版灾厄村民关闭帽子。
        if part in ("right_leg","left_leg"):
            side = "r" if part.startswith("right") else "l"
            x = -source["transform"][12]
            for suffix in ("thigh","shin","foot","toe"):
                bones[side+"_"+suffix]["pivot"][0] = x
        if "arm" in part or "sleeve" in part:
            side = "r" if part.startswith("right") else "l"
            mid = (source["min"][0]+source["max"][0])/2+source["transform"][12]
            for suffix in ("forearm","hand","thumb","fingers_a","fingers_b","item"):
                bones[side+"_"+suffix]["pivot"][0] = -mid
        append_cube(bones,source,model_name,64,height)
    if name == "villager":
        arms = [s for s in cubes if s["part"] == "arms"]
        for source in arms:
            if source["index"] < 2:
                part = "right_arm" if source["index"] == 0 else "left_arm"
                append_cube(bones,source,model_name,64,height,part,
                            lambda p: (p[0],22-p[1],p[2]))
            else:
                for side in ("right","left"):
                    lo,hi = list(source["min"]),list(source["max"])
                    if side == "right":
                        hi[0] = 0
                        transform = lambda p: (-2-p[1],12-p[0],p[2])
                    else:
                        lo[0] = 0
                        transform = lambda p: (2+p[1],12+p[0],p[2])
                    append_cube(bones,source,model_name,64,height,side+"_arm",transform,(lo,hi))
    if name == "stray_overlay":
        base = geometry("skeleton", sources, template)
        for bone in base["bones"]:
            bones[bone["name"]]["pivot"] = bone["pivot"]
    return {"description":{"identifier":"geometry.mob."+name,"texture_width":64,"texture_height":height},
            "bones":list(bones.values())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kpa-dir",type=Path,default=ROOT.parent/"Kizuna-Players-Animation")
    parser.add_argument("--vanilla-models",type=Path,default=ROOT/"build/vanilla-models.json")
    parser.add_argument("--check",action="store_true")
    args = parser.parse_args()
    template_doc = json.loads((args.kpa_dir/"src/main/resources/assets/kizuna_players_animation/geo/player_skeleton.geo.json").read_text())
    template = next(g for g in template_doc["minecraft:geometry"] if g["description"]["identifier"] == "geometry.player.classic")
    sources = json.loads(args.vanilla_models.read_text())
    names = ("zombie","skeleton","wither_skeleton","piglin","villager","illager","zombie_villager",
             "drowned","drowned_overlay","stray_overlay")
    document = {"format_version":"1.12.0","minecraft:geometry":[geometry(n,sources,template) for n in names]}
    content = json.dumps(document,ensure_ascii=False,indent=2)+"\n"
    if args.check:
        if OUTPUT.read_text() != content:
            raise SystemExit("几何资源与原版导出不一致，请重新生成")
    else:
        OUTPUT.write_text(content)


if __name__ == "__main__":
    main()
