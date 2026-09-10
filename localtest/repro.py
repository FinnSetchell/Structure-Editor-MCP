"""Local cold-chunk entity-save repro against the scratchpad server.

usage: python repro.py [--setup] [--mob] [--rounds N] [--wait-cold S] [--log-grep RE]

  --setup      place the structure block + test entity under a temporary forceload
               (idempotent: kills previous test entities first), then release.
  --mob        use a persistent, named wither skeleton (matches the production case)
               instead of an armour stand.
  --rounds N   cold-save rounds (default 3). each round waits until /chunk-state says the
               chunk is genuinely cold (entity sections not LOADED, visibility ABSENT/HIDDEN,
               not block-ticking), then saves via the mod and inspects the generated NBT.
"""
import argparse, json, sys, time, urllib.request, urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import rcon  # noqa: E402

BASE = "http://127.0.0.1:25580"
KEY = "localtest"
SRV = Path(__file__).parent / "localsrv"
GEN = SRV / "world" / "generated" / "test" / "structures" / "anchor.nbt"

SBX, SBY, SBZ = 3000, -60, 3000
OFF = (1, 0, -1)
SIZE = (1, 4, 1)
BX, BY, BZ = SBX + OFF[0], SBY + OFF[1], SBZ + OFF[2]
ENT = (BX + 0.5, BY, BZ + 0.5)
NAME = "ReproEntity"


def http(method, path, body=None, query=None):
    url = BASE + path + ("?" + urllib.parse.urlencode(query) if query else "")
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {"error": str(e)}


def chunk_state():
    st, d = http("GET", "/chunk-state", query=dict(x=SBX, z=SBZ))
    return d


def is_cold(cs):
    return (cs.get("entity_load") != "LOADED"
            and cs.get("entity_visibility") in ("ABSENT", "HIDDEN")
            and not cs.get("block_ticking"))


def wait_cold(limit):
    t0 = time.time()
    last = None
    while time.time() - t0 < limit:
        cs = chunk_state()
        last = cs
        if is_cold(cs):
            return True, round(time.time() - t0, 1), cs
        time.sleep(2)
    return False, round(time.time() - t0, 1), last


def setup(mob):
    sb_nbt = ('{mode:"SAVE",name:"test:anchor",author:"repro",'
              f'posX:{OFF[0]},posY:{OFF[1]},posZ:{OFF[2]},sizeX:{SIZE[0]},sizeY:{SIZE[1]},sizeZ:{SIZE[2]},'
              'ignoreEntities:0b,showboundingbox:1b}')
    if mob:
        summon = (f"summon wither_skeleton {ENT[0]} {ENT[1]} {ENT[2]} "
                  "{PersistenceRequired:1b,NoAI:1b,Invulnerable:1b,"
                  "CustomName:'\"" + NAME + "\"',CustomNameVisible:1b}")
    else:
        summon = (f"summon armor_stand {ENT[0]} {ENT[1]} {ENT[2]} "
                  "{NoGravity:1b,Invulnerable:1b,CustomName:'\"" + NAME + "\"',CustomNameVisible:1b}")
    cmds = [
        f"forceload add {SBX} {SBZ}",
        f"kill @e[name={NAME},x={SBX},y={SBY},z={SBZ},distance=..64]",
        f"setblock {SBX} {SBY} {SBZ} structure_block[mode=save]{sb_nbt}",
        summon,
        f"forceload remove {SBX} {SBZ}",
        "forceload query",
    ]
    for cmd, resp in rcon.run(cmds):
        print(f"  > {cmd}\n    {resp.strip() or '(no response)'}")


def inspect_nbt():
    import nbtlib
    if not GEN.exists():
        return {"exists": False}
    n = nbtlib.load(str(GEN))
    ents = n["entities"]
    return {
        "exists": True,
        "bytes": GEN.stat().st_size,
        "size": [int(v) for v in n["size"]],
        "blocks": len(n["blocks"]),
        "entities": len(ents),
        "entity_ids": [str(e["nbt"]["id"]) for e in ents],
    }


def one_round(i, wait_limit):
    print(f"\n===== round {i} =====")
    cold, secs, cs = wait_cold(wait_limit)
    print(f"cold: {cold} after {secs}s  state={json.dumps(cs)}")
    if GEN.exists():
        GEN.unlink()
    st, d = http("POST", "/save", body={"x": SBX, "y": SBY, "z": SBZ})
    r = (d.get("results") or [{}])[0]
    print(f"save HTTP {st}: wait={d.get('entity_wait_ms')}ms loaded={d.get('entity_sections_loaded')} "
          f"captured={r.get('entities_captured')} states={json.dumps(r.get('chunk_states'))}")
    time.sleep(1.0)
    nb = inspect_nbt()
    verdict = "PASS" if nb.get("entities", 0) >= 1 else "FAIL"
    print(f"generated nbt: {json.dumps(nb)}  -> {verdict}")
    return verdict


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--setup", action="store_true")
    ap.add_argument("--mob", action="store_true")
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--wait-cold", type=int, default=180)
    ap.add_argument("--log-grep", default=None)
    a = ap.parse_args()

    st, d = http("GET", "/health")
    print("health:", st, d)
    if a.setup:
        print("== setup ==")
        setup(a.mob)
    verdicts = [one_round(i, a.wait_cold) for i in range(1, a.rounds + 1)]
    print("\n== verdicts ==", verdicts)
    if a.log_grep:
        st, d = http("GET", "/log/tail", query={"lines": 60, "grep": a.log_grep})
        print("\n== log tail ==")
        for l in d.get("lines", []):
            print(l)


if __name__ == "__main__":
    main()
