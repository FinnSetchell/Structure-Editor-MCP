# local test harness

A private Fabric 1.21.10 dedicated server for testing the mod without deploying to the
build server. Reproduces the cold-chunk entity save case end to end in one command.

## assemble `localsrv/` (once)

Everything comes from what is already on disk; nothing is downloaded.

```
localtest/localsrv/
  server.jar, fabric-server-launch.jar, fabric-installer-*.jar, libraries/
      <- copy from moogs-structure-tester/cache/loaders/fabric-1.21.10-0.19.2/
  fabric-server-launcher.properties   serverJar=server.jar
  mods/fabric-api-*.jar               <- moogs-structure-tester/cache/deps/ (0.138.4+1.21.10)
  mods/structure-block-saver-*.jar    <- StructureBlockSaver/build/libs/ (optional, matches prod)
  mods/structure-editor-<ver>.jar     <- fabric-mod/build/libs/ (exactly one copy)
  eula.txt                            eula=true
  server.properties                   <- server.properties.example
  config/structure_editor.json        <- structure_editor.json.example
```

`server.properties.example` is a flat world on port 25599 with RCON on 25598 (password
`localtest`), no online mode, and `pause-when-empty-seconds=5` so the vanilla pause kicks in
within seconds of an empty server instead of a minute. `structure_editor.json.example` binds
the bridge to 127.0.0.1:25580 with api key `localtest`.

## run

```
cd localtest/localsrv
"<jdk21>/bin/java" -Xms1G -Xmx2G -jar fabric-server-launch.jar nogui > console.log 2>&1 &
```

A JDK 21 lives at `moogs-structure-tester/cache/jdks/21/jdk-*/bin/java.exe`. The world is
ready when `GET http://127.0.0.1:25580/health` returns `"serverReady": true` (the bridge
starts during mod init, before the world, so `status: ok` alone is not enough).

## repro

```
python repro.py --setup --mob --rounds 3
```

`--setup` places a 1x4x1 SAVE structure block at 3000 -60 3000 (far outside spawn chunks)
under a temporary forceload, summons a persistent named wither skeleton inside its bounds
(`--mob`; armour stand without), and releases the forceload. Each round then waits until
`/chunk-state` reports the chunk genuinely cold (not resident, visibility HIDDEN, load FRESH),
saves through the mod, and inspects the generated NBT under
`localsrv/world/generated/test/structures/anchor.nbt`. PASS means the entity is in the file.

Expected with `keep_server_ticking: true`: every round PASS, and `grep pausing console.log`
empty. With it set to `false` the server logs `Server empty for 5 seconds, pausing` and the
save sits at `entity_load: PENDING` with zero entities - that is the production failure.

`rcon.py "<command>"` sends any console command; `rcon.py stop` shuts the server down and the
JVM exits on its own.

## a second harness for another MC version

To test a port without disturbing the existing one, build a sibling `localsrv-<ver>/`
and give it its own ports so both can exist side by side. For the 26.3 port that was:

| | 1.21.10 (`localsrv/`) | 26.3 (`localsrv-263/`) |
|---|---|---|
| mod HTTP bridge | 25580 | 25680 |
| RCON | 25598 | 25698 |
| MC server | 25599 | 25699 |
| JDK | 21 | 25 |

The launcher for a given version comes straight from Fabric meta, no installer needed:

```
curl -o fabric-server-launch.jar \
  "https://meta.fabricmc.net/v2/versions/loader/<mcver>/<loader>/1.1.0/server/jar"
```

Copy the matching `fabric-api` jar out of the Gradle cache
(`~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/<ver>/`), set
`port` in `config/structure_editor.json`, and point a copy of `repro.py` / `rcon.py` at the
new ports. Note the generated-structure folder is `world/generated/<ns>/structure/`
(singular) on 1.21+ — `repro.py` looks there.

StructureBlockSaver is built against a specific MC version, so it will not load on a
different one; `structure-blocks` correctly reports `sbs_tracker_missing` without it, and
the cold-save repro does not need it.

## iterating

Windows holds the mod jar open while the server runs: `rcon.py stop`, wait for port 25580 to
close, copy the new jar, relaunch. Never leave two `structure-editor-*.jar` in `mods/`.
