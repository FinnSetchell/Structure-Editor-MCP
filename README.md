# Structure Editor

A Fabric 1.21.10 mod that exposes an MCP (Model Context Protocol) server so Claude Code can read and edit **jigsaw blocks** and **structure blocks** in your Minecraft world.

---

## How it works

```
Claude Code (MCP client)
    ↓  stdio
mcp-server/  (TypeScript MCP server)
    ↓  HTTP  localhost:25580
fabric-mod/  (Fabric mod embedded HTTP server)
    ↓  server.execute()
Minecraft main thread  →  JigsawBlockEntity / StructureBlockBlockEntity
```

1. The Fabric mod starts an HTTP server on `localhost:25580` when the world loads.
2. The TypeScript MCP server connects to it and wraps it in MCP tools.
3. Claude Code calls those tools to scan and edit blocks.

---

## Building the Fabric mod

Requires Java 21.

```powershell
cd fabric-mod
.\gradlew build
```

The built jar ends up in `fabric-mod/build/libs/`. Drop it in your server's `mods/` folder.

---

## In-game usage

### Get the wand
```
/sedit wand
```
This gives you a **stick**. While holding it:
- **Left-click** a block → sets **pos1**
- **Right-click** a block → sets **pos2**

### Check selection
```
/sedit status
```

### Clear selection
```
/sedit clear
```

---

## Setting up the MCP server

Requires Node 18+.

```powershell
cd mcp-server
npm install
npm run build
```

### Configure Claude Code (or Claude Desktop)

Add to your MCP config (usually `~/.config/Claude/claude_desktop_config.json` on Windows: `%APPDATA%\Claude\claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "structure-editor": {
      "command": "node",
      "args": ["C:/Users/finn/Documents/antigravity/brave-hertz/mcp-server/dist/index.js"]
    }
  }
}
```

Or, if you prefer to run it without building (requires `tsx` installed):

```json
{
  "mcpServers": {
    "structure-editor": {
      "command": "npx",
      "args": ["tsx", "C:/Users/finn/Documents/antigravity/brave-hertz/mcp-server/src/index.ts"]
    }
  }
}
```

---

## MCP Tools available to Claude

| Tool | Description |
|---|---|
| `check_mod_status` | Check if the mod is running and the server is ready |
| `get_selection` | Get the current pos1/pos2 selection |
| `set_selection` | Set pos1/pos2 programmatically |
| `scan_region` | Scan selection → returns all jigsaw + structure blocks with their fields |
| `edit_block` | Edit fields of a single block at x/y/z |
| `edit_blocks_batch` | Edit many blocks in one call |
| `rename_structure` | Convenience: update name/target/pool on a jigsaw block |
| `bulk_replace_string` | Find-and-replace a substring across all string fields in the selection |

---

## Jigsaw block fields

| Field | Type | Description |
|---|---|---|
| `name` | string | This block's identifier (e.g. `mymod:top`) |
| `target` | string | Target name to connect to (e.g. `mymod:bottom`) |
| `pool` | string | Template pool (e.g. `mymod:pools/main`) |
| `final_state` | string | Block it becomes after generation |
| `joint` | string | `"rollable"` or `"aligned"` |
| `selection_priority` | int | Selection priority |
| `placement_priority` | int | Placement priority |

## Structure block fields

| Field | Type | Description |
|---|---|---|
| `name` | string | Structure file path (e.g. `mymod:path/to/file`) |
| `author` | string | Author |
| `mode` | string | `SAVE`, `LOAD`, `CORNER`, `DATA` |
| `posX/Y/Z` | int | Offset from structure block |
| `sizeX/Y/Z` | int | Structure size |
| `rotation` | string | `NONE`, `CLOCKWISE_90`, etc. |
| `mirror` | string | `NONE`, `LEFT_RIGHT`, `FRONT_BACK` |
| `integrity` | float | 0.0–1.0 |
| `metadata` | string | Data tag |

---

## Example Claude workflow

```
You: Scan my selection for jigsaw blocks.

Claude: [calls scan_region]
→ Found 12 jigsaw blocks. Block at 120,64,305 has pool="mymod:old_pools/hall".

You: Rename all pools from "mymod:old_pools/" to "mymod:new_pools/" across the whole selection.

Claude: [calls bulk_replace_string with find="mymod:old_pools/" replace="mymod:new_pools/"]
→ Changed 12 blocks.
```
