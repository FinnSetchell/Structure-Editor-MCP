"""Exercise every structure-editor endpoint against a running server.

Not a unit test suite - it drives the real HTTP bridge against a real world and
asserts on what comes back, so it catches version drift the compiler cannot.

usage: python fullsuite.py [--port 25680] [--rcon 25698] [--srv localsrv-263]
"""
import argparse, json, sys, time, urllib.request, urllib.parse, importlib
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=25680)
ap.add_argument("--rcon", type=int, default=25698)
ap.add_argument("--srv", default="localsrv-263")
A = ap.parse_args()

BASE = f"http://127.0.0.1:{A.port}"
KEY = "localtest"
SRV = HERE / A.srv

rcon = importlib.import_module("rcon263" if A.rcon == 25698 else "rcon")

# a quiet corner of the flat world, far from spawn
X, Y, Z = 2000, -60, 2000
results = []


def http(method, path, body=None, raw=False):
    url = BASE + path
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            b = r.read()
            return r.status, (b if raw else json.loads(b.decode()))
    except urllib.error.HTTPError as e:
        b = e.read()
        try:
            return e.code, json.loads(b.decode())
        except Exception:
            return e.code, {"_raw": b[:200].decode("utf-8", "replace")}


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"   {detail}" if detail else ""))


def cmds(lines):
    return {c: r.strip() for c, r in rcon.run(lines)}


def setup():
    print("\n-- setup --")
    cmds([
        f"forceload add {X} {Z}",
        f"fill {X} {Y} {Z} {X+5} {Y+3} {Z+5} minecraft:air",
        f"setblock {X} {Y} {Z} minecraft:chest",
        f"setblock {X+2} {Y} {Z} minecraft:stone",
        f"setblock {X+3} {Y} {Z} minecraft:stone",
        f'setblock {X} {Y+2} {Z} minecraft:jigsaw{{name:"test:old_name",target:"test:old_target",pool:"test:old_pool",final_state:"minecraft:air",joint:"rollable"}}',
        f'setblock {X+1} {Y+2} {Z} structure_block[mode=save]{{mode:"SAVE",name:"test:suite",author:"suite",posX:-1,posY:-2,posZ:0,sizeX:5,sizeY:3,sizeZ:1,ignoreEntities:0b}}',
        f"summon armor_stand {X+0.5} {Y+1} {Z+0.5} {{NoGravity:1b,CustomName:'\"SuiteStand\"'}}",
        f"forceload remove {X} {Z}",
    ])


BOUNDS = f"?x1={X-2}&y1={Y-2}&z1={Z-2}&x2={X+6}&y2={Y+4}&z2={Z+6}"


def main():
    st, d = http("GET", "/health")
    check("health serverReady", st == 200 and d.get("serverReady") is True, str(d))
    setup()

    print("\n-- read endpoints --")
    st, d = http("GET", "/scan" + BOUNDS)
    kinds = {b.get("type") for b in d.get("blocks", [])}
    check("scan finds jigsaw + structure block", st == 200 and {"jigsaw", "structure_block"} <= kinds, f"kinds={sorted(kinds)}")

    st, d = http("POST", "/scan/blocks" + BOUNDS, ["minecraft:stone"])
    check("scan_blocks finds 2 stone", st == 200 and d.get("count") == 2, f"count={d.get('count')}")

    st, d = http("POST", "/scan/entities" + BOUNDS, [])
    check("scan_entities finds armour stand", st == 200 and any(e["type"] == "minecraft:armor_stand" for e in d.get("entities", [])),
          f"count={d.get('count')} loaded={d.get('entity_sections_loaded')}")

    st, d = http("GET", "/scan/containers" + BOUNDS)
    check("scan_containers finds chest", st == 200 and d.get("count", 0) >= 1, f"count={d.get('count')}")

    st, d = http("POST", "/block/get", {"positions": [{"x": X + 2, "y": Y, "z": Z}]})
    check("get_blocks reads stone", st == 200 and "stone" in json.dumps(d), json.dumps(d)[:70])

    st, d = http("GET", f"/chunk-state?x={X}&z={Z}")
    check("chunk-state answers", st == 200 and "entity_load" in d, f"{d.get('entity_load')}/{d.get('entity_visibility')}")

    st, d = http("GET", "/log/tail?lines=5")
    check("log tail", st == 200 and d.get("returned", 0) > 0, f"lines={d.get('total_lines')}")

    print("\n-- container read/write --")
    st, d = http("POST", "/container", {"x": X, "y": Y, "z": Z,
                                        "slots": [{"slot": 0, "id": "minecraft:diamond", "count": 3}]})
    check("write_container items", st == 200 and not d.get("error"), json.dumps(d)[:70])
    st, d = http("GET", f"/container?x={X}&y={Y}&z={Z}")
    check("read_container sees item", st == 200 and "diamond" in json.dumps(d), json.dumps(d)[:90])

    st, d = http("POST", "/container", {"x": X, "y": Y, "z": Z, "loot_table": "minecraft:chests/simple_dungeon"})
    check("write_container loot table", st == 200 and not d.get("error"), json.dumps(d)[:70])
    st, d = http("GET", f"/container?x={X}&y={Y}&z={Z}")
    check("read_container sees loot table", st == 200 and "simple_dungeon" in json.dumps(d), json.dumps(d)[:90])

    print("\n-- block entity nbt --")
    st, d = http("GET", f"/nbt/block?x={X}&y={Y+2}&z={Z}")
    check("get_block_nbt on jigsaw", st == 200 and "old_pool" in json.dumps(d), json.dumps(d)[:80])

    print("\n-- edits --")
    st, d = http("POST", "/edit", {"x": X, "y": Y + 2, "z": Z, "fields": {"pool": "test:new_pool"}})
    check("edit_block jigsaw pool", st == 200 and not d.get("error"), json.dumps(d)[:70])
    st, d = http("GET", "/scan" + BOUNDS)
    jig = next((b for b in d.get("blocks", []) if b.get("type") == "jigsaw"), {})
    check("edit persisted", jig.get("pool") == "test:new_pool", f"pool={jig.get('pool')}")

    st, d = http("POST", "/batch", [{"x": X, "y": Y + 2, "z": Z, "fields": {"target": "test:new_target"}}])
    check("edit_blocks_batch", st == 200 and not d.get("error"), json.dumps(d)[:70])

    print("\n-- block set / replace / undo --")
    st, d = http("POST", "/block/set", {"blocks": [{"x": X + 4, "y": Y, "z": Z, "block": "minecraft:gold_block"}],
                                        "allow_outside_selection": True})
    tok = d.get("undo_token")
    check("set_blocks", st == 200 and not d.get("error"), f"token={'yes' if tok else 'no'}")
    st, d = http("POST", "/block/get", {"positions": [{"x": X + 4, "y": Y, "z": Z}]})
    check("set_blocks applied", "gold_block" in json.dumps(d), json.dumps(d)[:70])
    if tok:
        st, d = http("POST", "/block/undo", {"undo_token": tok})
        check("undo_last_write", st == 200 and not d.get("error"), json.dumps(d)[:70])
        st, d = http("POST", "/block/get", {"positions": [{"x": X + 4, "y": Y, "z": Z}]})
        check("undo restored", "gold_block" not in json.dumps(d), json.dumps(d)[:70])

    st, d = http("POST", "/block/replace", {"find": ["minecraft:stone"], "replace": "minecraft:cobblestone",
                                            "dry_run": False,
                                            "pos1": {"x": X - 2, "y": Y - 2, "z": Z - 2},
                                            "pos2": {"x": X + 6, "y": Y + 4, "z": Z + 6}})
    check("replace_blocks", st == 200 and not d.get("error"), json.dumps(d)[:90])
    st, d = http("POST", "/scan/blocks" + BOUNDS, ["minecraft:cobblestone"])
    check("replace applied", d.get("count") == 2, f"count={d.get('count')}")

    print("\n-- selections --")
    st, d = http("POST", "/selection?region=suite", {"pos1": {"x": X, "y": Y, "z": Z}, "pos2": {"x": X + 2, "y": Y + 2, "z": Z + 2}})
    check("set_selection", st == 200 and d.get("complete") is True, json.dumps(d)[:70])
    st, d = http("GET", "/selection?region=suite")
    check("get_selection", st == 200 and d.get("complete") is True, json.dumps(d)[:80])
    st, d = http("POST", "/selection/remove", {"name": "suite"})
    check("remove_region", st == 200 and d.get("removed") == 1, json.dumps(d)[:70])

    print("\n-- save / palette / download --")
    st, d = http("POST", "/save", {"x": X + 1, "y": Y + 2, "z": Z})
    r = (d.get("results") or [{}])[0]
    check("save_structures (targeted) captures entity", st == 200 and r.get("success") is True and r.get("entities_captured", 0) >= 1, f"captured={r.get('entities_captured')}")

    st, d = http("GET", "/structure/palette?name=" + urllib.parse.quote("test:suite"))
    check("get_structure_palette non-empty", st == 200 and not d.get("error") and len(d.get("palette", [])) >= 2, f"palette={d.get('palette')}")

    st, raw = http("GET", "/download?name=" + urllib.parse.quote("test:suite"), raw=True)
    check("download_structure", st == 200 and isinstance(raw, bytes) and len(raw) > 300, f"bytes={len(raw) if isinstance(raw,bytes) else raw}")

    st, d = http("POST", "/save", {"pos1": {"x": X - 2, "y": Y - 2, "z": Z - 2}, "pos2": {"x": X + 6, "y": Y + 4, "z": Z + 6}})
    check("save_structures (bounds form)", st == 200 and d.get("count", 0) >= 1, f"count={d.get('count')}")

    print("\n-- file write --")
    st, d = http("POST", "/file/write", {"path": "world/datapacks/suite/pack.mcmeta",
                                         "content": '{"pack":{"description":"suite","pack_format":81}}'})
    check("write_server_file", st == 200 and d.get("success") is True, json.dumps(d)[:80])

    # SBS itself is built for another MC version and will not load here, so the reader is
    # exercised against a synthesised tracker file when present, and against its absence
    # otherwise. Both are real behaviours worth asserting.
    tracker = SRV / "world" / "data" / "sbs_structure_tracker.dat"
    present = tracker.exists()
    print("")
    print("-- sbs reader (tracker " + ("present" if present else "absent") + ") --")
    st, d = http("GET", "/structure-blocks")
    if present:
        check("structure-blocks reads tracker", st == 200 and d.get("source") == "sbs" and d.get("count", 0) >= 1, "count=" + str(d.get("count")))
        st, d = http("GET", "/structure-bounds?name=" + urllib.parse.quote("test:suite"))
        check("find_structure_bounds resolves", st == 200 and d.get("success") is True and "pos1" in d, str(d.get("pos1")) + " -> " + str(d.get("pos2")))
        st, d = http("POST", "/selection/from-structure", {"name": "test:suite", "region": "fromsb"})
        check("set_selection_to_structure", st == 200 and d.get("success") is True, "region=" + str(d.get("region")))
        http("POST", "/selection/remove", {"name": "fromsb"})
    else:
        check("structure-blocks reports missing tracker", st == 404 and d.get("error") == "sbs_tracker_missing", json.dumps(d)[:70])

    n = sum(1 for _, ok, _ in results if ok)
    print(f"\n==== {n}/{len(results)} passed ====")
    bad = [r[0] for r in results if not r[1]]
    if bad:
        print("FAILED:", ", ".join(bad))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
