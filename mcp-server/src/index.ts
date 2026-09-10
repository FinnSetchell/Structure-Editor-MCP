#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import * as fs from "fs";
import * as path from "path";

// The mod URL and optional API key are read from environment variables.
const MOD_URL = process.env.MC_SERVER_URL || "http://127.0.0.1:25580";
const API_KEY = process.env.MC_API_KEY || "";

//////////////////////////////
// HTTP helpers
//////////////////////////////

async function modGet(path: string): Promise<unknown> {
    const headers: Record<string, string> = {};
    if (API_KEY) {
        headers["Authorization"] = `Bearer ${API_KEY}`;
    }
    const res = await fetch(`${MOD_URL}${path}`, { headers });
    if (!res.ok) {
        throw new Error(`HTTP ${res.status} from mod: ${await res.text()}`);
    }
    return res.json();
}

async function modPost(path: string, body: unknown): Promise<unknown> {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (API_KEY) {
        headers["Authorization"] = `Bearer ${API_KEY}`;
    }
    const res = await fetch(`${MOD_URL}${path}`, {
        method: "POST",
        headers,
        body: JSON.stringify(body),
    });
    if (!res.ok) {
        throw new Error(`HTTP ${res.status} from mod: ${await res.text()}`);
    }
    return res.json();
}

function textResult(data: unknown) {
    return {
        content: [{ type: "text" as const, text: JSON.stringify(data, null, 2) }],
    };
}

// Shape for inline pos1/pos2 params on every scan/edit tool. If both are provided,
// the request bypasses the stored 'region' selection entirely — safe for parallel
// agents where each request is independent.
const posSchema = z.object({
    x: z.number().int(),
    y: z.number().int(),
    z: z.number().int(),
}).describe("A block position.");

type Pos = { x: number; y: number; z: number };

// Append x1/y1/z1 & x2/y2/z2 to a URLSearchParams when both bounds are provided.
// Used by GET endpoints where inline bounds have to travel in the query string.
function appendBoundsToQuery(params: URLSearchParams, pos1?: Pos, pos2?: Pos) {
    if (!pos1 || !pos2) return;
    params.append("x1", String(pos1.x)); params.append("y1", String(pos1.y)); params.append("z1", String(pos1.z));
    params.append("x2", String(pos2.x)); params.append("y2", String(pos2.y)); params.append("z2", String(pos2.z));
}

// Merge pos1/pos2 into a request body when both bounds are provided.
// Used by POST endpoints. Ignored if either is missing.
function mergeBoundsIntoBody(body: Record<string, unknown>, pos1?: Pos, pos2?: Pos) {
    if (!pos1 || !pos2) return;
    body.pos1 = pos1;
    body.pos2 = pos2;
}

//////////////////////////////
// MCP Server
//////////////////////////////

const server = new McpServer({
    name: "structure-editor",
    version: "1.4.1",
});

// --- health ---

server.tool(
    "check_mod_status",
    "Check whether the Structure Editor mod is running and the Minecraft server is ready.",
    {},
    async () => {
        try {
            const data = await modGet("/health");
            return textResult(data);
        } catch (e) {
            return textResult({ error: String(e), hint: "Make sure Minecraft is running with the Structure Editor mod installed." });
        }
    }
);

// --- selection ---

server.tool(
    "get_selection",
    "Get the current in-world selection (pos1 and pos2). The player sets this with the stick wand in-game, or you can set it programmatically with set_selection.",
    {
        region: z.string().optional().describe("Optional region name to get the selection for. Defaults to 'default'."),
        name_filter: z.string().optional().describe("Optional filter for results."),
    },
    async ({ region, name_filter }) => {
        let url = "/scan?format=json";
        if (region) url += `&region=${encodeURIComponent(region)}`;
        if (name_filter) url += `&name_filter=${encodeURIComponent(name_filter)}`;
        const data = await modGet(url);
        return textResult(JSON.stringify(data, null, 2));
    }
);

server.tool(
    "set_selection",
    "Set the selection region programmatically (instead of using the in-game stick wand). Both pos1 and pos2 are optional — omit one to only update that corner.",
    {
        pos1: z.object({
            x: z.number().int(),
            y: z.number().int(),
            z: z.number().int(),
        }).optional().describe("First corner of the selection"),
        pos2: z.object({
            x: z.number().int(),
            y: z.number().int(),
            z: z.number().int(),
        }).optional().describe("Second corner of the selection"),
        region: z.string().optional().describe("Optional region name to set the selection for. Defaults to 'default'."),
    },
    async ({ pos1, pos2, region }) => {
        const body: Record<string, unknown> = {};
        if (pos1) body.pos1 = pos1;
        if (pos2) body.pos2 = pos2;
        let url = "/selection";
        if (region) url += `?region=${encodeURIComponent(region)}`;
        const data = await modPost(url, body);
        return textResult(data);
    }
);

// --- scan ---

server.tool(
    "scan_region",
    `Scan a bounded region and return every jigsaw block and structure block found within it.
Bounds are picked in this order: inline pos1+pos2 (stateless, use this for parallel agents), else the named stored region, else the 'default' stored region.
Optional 'format' parameter: 'compact' (default, highly compressed line-by-line format to save tokens) or 'json' (full JSON structure).
Optional 'name_filter' parameter: only return blocks whose name, pool, or target contains this string.`,
    {
        format: z.enum(["compact", "json"]).optional().default("compact").describe("Output format. Use 'compact' (default) to save context tokens, or 'json' for raw structured data."),
        name_filter: z.string().optional().describe("Only return blocks containing this string in their name, pool, or target properties."),
        region: z.string().optional().describe("Named stored region to scan. Ignored when pos1+pos2 are provided. Defaults to 'default'."),
        pos1: posSchema.optional().describe("First corner of an inline bbox. Provide both pos1 and pos2 to bypass the stored region entirely."),
        pos2: posSchema.optional().describe("Second corner of an inline bbox. Provide both pos1 and pos2 to bypass the stored region entirely."),
    },
    async ({ format, name_filter, region, pos1, pos2 }) => {
        let url = "/scan";
        const params = new URLSearchParams();
        if (name_filter) params.append("name", name_filter);
        if (region) params.append("region", region);
        appendBoundsToQuery(params, pos1, pos2);
        if (params.toString()) url += "?" + params.toString();
        const data = await modGet(url) as { count: number; blocks: Array<Record<string, unknown>> };
        
        if (format === "json") {
            return textResult(data);
        }

        if (!data.blocks || data.blocks.length === 0) {
            return textResult({ count: 0, message: "No jigsaw or structure blocks found in the selection region." });
        }

        const lines: string[] = [];
        lines.push(`Scanned ${data.count} block(s) in selection:`);
        
        for (const block of data.blocks) {
            const x = block.x;
            const y = block.y;
            const z = block.z;
            
            if (block.type === "jigsaw") {
                const jp = [];
                if (block.name) jp.push(`name: ${block.name}`);
                if (block.target) jp.push(`target: ${block.target}`);
                if (block.pool) jp.push(`pool: ${block.pool}`);
                if (block.final_state) jp.push(`final_state: ${block.final_state}`);
                if (block.joint) jp.push(`joint: ${block.joint}`);
                if (block.selection_priority !== undefined) jp.push(`sel_pri: ${block.selection_priority}`);
                if (block.placement_priority !== undefined) jp.push(`pl_pri: ${block.placement_priority}`);
                if (block.origin_x !== undefined) jp.push(`origin: [${block.origin_x}, ${block.origin_y}, ${block.origin_z}]`);
                lines.push(`[Jigsaw] at (${x}, ${y}, ${z}) | ${jp.join(" | ")}`);
            } else if (block.type === "structure_block") {
                const sp = [];
                if (block.name) sp.push(`name: ${block.name}`);
                if (block.author) sp.push(`author: ${block.author}`);
                if (block.mode) sp.push(`mode: ${block.mode}`);
                if (block.metadata) sp.push(`meta: ${block.metadata}`);
                sp.push(`offset: [${block.posX}, ${block.posY}, ${block.posZ}]`);
                if (block.origin_x !== undefined) sp.push(`origin: [${block.origin_x}, ${block.origin_y}, ${block.origin_z}]`);
                sp.push(`size: [${block.sizeX}, ${block.sizeY}, ${block.sizeZ}]`);
                if (block.rotation) sp.push(`rot: ${block.rotation}`);
                if (block.mirror) sp.push(`mirror: ${block.mirror}`);
                if (block.integrity !== undefined) sp.push(`integrity: ${block.integrity}`);
                lines.push(`[Structure] at (${x}, ${y}, ${z}) | ${sp.join(" | ")}`);
            }
        }

        return {
            content: [{ type: "text" as const, text: lines.join("\n") }],
        };
    }
);

server.tool(
    "scan_containers",
    `Scan a bounded region for containers (chests, barrels, etc) and return their loot tables. Highly compressed output to save tokens.
Bounds are picked in this order: inline pos1+pos2 (stateless), else the named stored region, else 'default'.`,
    {
        region: z.string().optional().describe("Named stored region to scan. Ignored when pos1+pos2 are provided. Defaults to 'default'."),
        pos1: posSchema.optional().describe("First corner of an inline bbox."),
        pos2: posSchema.optional().describe("Second corner of an inline bbox."),
    },
    async ({ region, pos1, pos2 }) => {
        let url = "/scan/containers";
        const params = new URLSearchParams();
        if (region) params.append("region", region);
        appendBoundsToQuery(params, pos1, pos2);
        if (params.toString()) url += "?" + params.toString();
        const data = await modGet(url) as { count: number; containers?: Array<Record<string, unknown>>, error?: string };
        if (data.error) {
            return textResult(data);
        }
        if (!data.containers || data.containers.length === 0) {
            return textResult({ count: 0, message: "No containers found in the selection region." });
        }
        return textResult(data);
    }
);

// --- edit single block ---

server.tool(
    "edit_block",
    `Edit the fields of a jigsaw block or structure block at a specific position.
Only the fields you include in 'fields' are changed — everything else is left as-is.

For jigsaw blocks the editable fields are:
  name        - the jigsaw block's own identifier (e.g. "mymod:top")
  target      - the target name to connect to (e.g. "mymod:bottom")
  pool        - the template pool (e.g. "mymod:pools/main")
  final_state - the block it turns into after generation (e.g. "minecraft:stone")
  joint       - "rollable" or "aligned"
  selection_priority - integer
  placement_priority - integer

For structure blocks the editable fields are:
  name        - structure file path (e.g. "mymod:path/to/structure")
  author      - author string
  mode        - "SAVE", "LOAD", "CORNER", or "DATA"
  metadata    - data tag string
  posX/posY/posZ - offset integers
  sizeX/sizeY/sizeZ - size integers
  rotation    - "NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"
  mirror      - "NONE", "LEFT_RIGHT", "FRONT_BACK"
  integrity   - float 0.0 to 1.0`,
    {
        x: z.number().int().describe("X coordinate of the block"),
        y: z.number().int().describe("Y coordinate of the block"),
        z: z.number().int().describe("Z coordinate of the block"),
        fields: z.record(z.union([z.string(), z.number(), z.boolean()]))
            .describe("Map of field names to new values. Only included fields are changed."),
    },
    async ({ x, y, z: zCoord, fields }) => {
        const data = await modPost("/edit", { x, y, z: zCoord, fields });
        return textResult(data);
    }
);

// --- batch edit ---

server.tool(
    "edit_blocks_batch",
    `Edit multiple jigsaw or structure blocks in a single call. More efficient than calling edit_block repeatedly.
Each entry must have x, y, z, and fields. Edits are applied sequentially.`,
    {
        edits: z.array(z.object({
            x: z.number().int(),
            y: z.number().int(),
            z: z.number().int(),
            fields: z.record(z.union([z.string(), z.number(), z.boolean()])),
        })).describe("Array of edit operations to perform"),
    },
    async ({ edits }) => {
        const data = await modPost("/batch", edits);
        return textResult(data);
    }
);

// --- rename helper ---

server.tool(
    "rename_structure",
    `Convenience tool: rename a jigsaw block's name, target, and/or pool fields in one call.
Useful for quickly changing structure namespacing/directory paths across many blocks after a scan.`,
    {
        x: z.number().int(),
        y: z.number().int(),
        z: z.number().int(),
        name: z.string().optional().describe("New value for the 'name' field"),
        target: z.string().optional().describe("New value for the 'target' field"),
        pool: z.string().optional().describe("New value for the 'pool' field"),
    },
    async ({ x, y, z: zCoord, name, target, pool }) => {
        const fields: Record<string, string> = {};
        if (name !== undefined) fields.name = name;
        if (target !== undefined) fields.target = target;
        if (pool !== undefined) fields.pool = pool;
        if (Object.keys(fields).length === 0) {
            return textResult({ error: "Provide at least one of: name, target, pool" });
        }
        const data = await modPost("/edit", { x, y, z: zCoord, fields });
        return textResult(data);
    }
);

// --- bulk rename ---

server.tool(
    "bulk_replace_string",
    `Scan the selected region and replace a substring across all jigsaw/structure block string fields AND container loot tables.
For example, replace "mymod:old_dir/" with "mymod:new_dir/" across all name/target/pool fields and container LootTables.
Returns a summary of what changed.`,
    {
        find: z.string().describe("Substring to search for in all string fields"),
        replace: z.string().describe("Replacement string"),
        fields_to_check: z.array(z.string())
            .default(["name", "target", "pool", "final_state"])
            .describe("Which fields to apply the replacement to (default: name, target, pool, final_state)"),
        dry_run: z.boolean().default(false)
            .describe("If true, returns what WOULD change without actually applying edits"),
        region: z.string().optional().describe("Named stored region to scan. Ignored when pos1+pos2 are provided. Defaults to 'default'."),
        pos1: posSchema.optional().describe("First corner of an inline bbox."),
        pos2: posSchema.optional().describe("Second corner of an inline bbox."),
    },
    async ({ find, replace, fields_to_check, dry_run, region, pos1, pos2 }) => {
        const scanParams = new URLSearchParams();
        if (region) scanParams.append("region", region);
        appendBoundsToQuery(scanParams, pos1, pos2);
        const suffix = scanParams.toString() ? "?" + scanParams.toString() : "";
        const scanData = await modGet("/scan" + suffix) as { count: number; blocks: Array<Record<string, unknown>> };
        const containerScanData = await modGet("/scan/containers" + suffix) as { count: number; containers?: Array<Record<string, unknown>> };

        if ((!scanData.blocks || scanData.blocks.length === 0) && (!containerScanData.containers || containerScanData.containers.length === 0)) {
            return textResult({ message: "No jigsaw/structure blocks or containers in selection", changed: 0 });
        }

        const edits: Array<{ x: number; y: number; z: number; fields: Record<string, string> }> = [];
        const containerEdits: Array<{ x: number; y: number; z: number; loot_table: string; loot_table_seed?: number }> = [];
        const changes: Array<{ pos: string; field: string; from: string; to: string }> = [];

        // Process Jigsaw/Structure blocks
        if (scanData.blocks) {
            for (const block of scanData.blocks) {
                const blockEdits: Record<string, string> = {};

                for (const field of fields_to_check) {
                    const val = block[field];
                    if (typeof val === "string" && val.includes(find)) {
                        const newVal = val.replaceAll(find, replace);
                        blockEdits[field] = newVal;
                        changes.push({
                            pos: `${block.x},${block.y},${block.z}`,
                            field,
                            from: val,
                            to: newVal,
                        });
                    }
                }

                if (Object.keys(blockEdits).length > 0) {
                    edits.push({
                        x: block.x as number,
                        y: block.y as number,
                        z: block.z as number,
                        fields: blockEdits,
                    });
                }
            }
        }

        // Process Containers
        if (containerScanData && containerScanData.containers) {
            for (const container of containerScanData.containers) {
                const lt = container.loot_table;
                if (typeof lt === "string" && lt.includes(find)) {
                    const newLt = lt.replaceAll(find, replace);
                    const seed = typeof container.loot_table_seed === "number" ? container.loot_table_seed : 0;
                    containerEdits.push({
                        x: container.x as number,
                        y: container.y as number,
                        z: container.z as number,
                        loot_table: newLt,
                        loot_table_seed: seed
                    });
                    changes.push({
                        pos: `${container.x},${container.y},${container.z}`,
                        field: "loot_table",
                        from: lt,
                        to: newLt,
                    });
                }
            }
        }

        if (dry_run || (edits.length === 0 && containerEdits.length === 0)) {
            return textResult({
                dry_run,
                would_change: changes.length,
                changes,
            });
        }

        let batch_result = null;
        if (edits.length > 0) {
            batch_result = await modPost("/batch", edits);
        }
        
        let container_batch_result = null;
        if (containerEdits.length > 0) {
            container_batch_result = await modPost("/container/batch", containerEdits);
        }

        return textResult({
            changed: changes.length,
            changes,
            batch_result,
            container_batch_result
        });
    }
);

// --- save structures ---

server.tool(
    "save_structures",
    `Trigger the 'Save' operation on all structure blocks in the selection region (or a specific structure block at x,y,z coordinates).
This writes the structure block's contents to the server's template files (.nbt).
Provide x, y, and z to only trigger a specific structure block. Leave them out to save all structure blocks inside your selection region.`,
    {
        x: z.number().int().optional().describe("Optional X coordinate of the structure block to save"),
        y: z.number().int().optional().describe("Optional Y coordinate of the structure block to save"),
        z: z.number().int().optional().describe("Optional Z coordinate of the structure block to save"),
        region: z.string().optional().describe("Named stored region to save from. Ignored when pos1+pos2 are provided or when x/y/z is set."),
        pos1: posSchema.optional().describe("First corner of an inline bbox to save from."),
        pos2: posSchema.optional().describe("Second corner of an inline bbox to save from."),
    },
    async ({ x, y, z: zCoord, region, pos1, pos2 }) => {
        const body: Record<string, unknown> = {};
        if (x !== undefined && y !== undefined && zCoord !== undefined) {
            body.x = x;
            body.y = y;
            body.z = zCoord;
        }
        mergeBoundsIntoBody(body, pos1, pos2);
        let url = "/save";
        if (region) url += `?region=${encodeURIComponent(region)}`;
        const data = await modPost(url, body);
        return textResult(data);
    }
);

// --- file writing ---

server.tool(
    "write_server_file",
    `Write a file directly to the server's filesystem.
Highly recommended for creating and saving Loot Tables, Advancements, Predicates, or any custom datapack JSONs directly to the server so they can be immediately assigned to a container and tested.
The path is relative to the Minecraft server's root directory. Example: 'world/datapacks/my_pack/data/mvs/loot_table/chests/custom.json'`,
    {
        file_path: z.string().describe("The file path relative to the server root, e.g. 'world/datapacks/mod_id/data/namespace/loot_table/chests/new_loot.json'"),
        content: z.string().describe("The string content to write to the file (e.g. stringified JSON)."),
    },
    async ({ file_path, content }) => {
        const body = { path: file_path, content };
        const data = await modPost("/file/write", body);
        return textResult(data);
    }
);

// --- container read/write ---

server.tool(
    "read_container",
    `Read the contents of a container block (chest, barrel, hopper, dispenser, etc.) at a specific position.
Returns all non-empty slots with item id and count, plus loot_table and loot_table_seed if set.
If a loot table is active, items won't appear until a player opens the container for the first time.`,
    {
        x: z.number().int().optional().describe("X coordinate of the container (if targeting by block pos)"),
        y: z.number().int().optional().describe("Y coordinate of the container (if targeting by block pos)"),
        z: z.number().int().optional().describe("Z coordinate of the container (if targeting by block pos)"),
        uuid: z.string().optional().describe("UUID of the entity container (like Minecart with Chest). Mutually exclusive with x/y/z."),
    },
    async ({ x, y, z: zCoord, uuid }) => {
        let url = `/container?`;
        if (uuid !== undefined) url += `uuid=${uuid}`;
        else url += `x=${x}&y=${y}&z=${zCoord}`;
        const data = await modGet(url);
        return textResult(data);
    }
);

server.tool(
    "write_container",
    `Write items or a loot table to a container block (chest, barrel, hopper, dispenser, etc.) at a specific position.
The container is always cleared first. Loot table and items are mutually exclusive — providing loot_table ignores slots.

To write items: provide 'slots' array with {slot, id, count} entries.
To set a loot table: provide 'loot_table' string (e.g. 'minecraft:chests/simple_dungeon') and optionally 'loot_table_seed'.

Supported containers: chest, trapped_chest, barrel, hopper, dispenser, dropper, shulker_box.`,
    {
        x: z.number().int().optional().describe("X coordinate of the container (if targeting by block pos)"),
        y: z.number().int().optional().describe("Y coordinate of the container (if targeting by block pos)"),
        z: z.number().int().optional().describe("Z coordinate of the container (if targeting by block pos)"),
        uuid: z.string().optional().describe("UUID of the entity container (like Minecart with Chest). Mutually exclusive with x/y/z."),
        slots: z.array(z.object({
            slot: z.number().int().describe("Slot index (0-based)"),
            id: z.string().describe("Item identifier, e.g. 'minecraft:iron_sword'"),
            count: z.number().int().min(1).max(64).default(1).describe("Stack size"),
            nbt: z.record(z.unknown()).optional().describe("Optional full NBT data for the item (for enchantments, custom names, etc.)"),
        })).optional().describe("Items to place in the container. Omit when setting a loot table."),
        loot_table: z.string().optional().describe("Loot table identifier to assign, e.g. 'minecraft:chests/simple_dungeon'. Mutually exclusive with slots."),
        loot_table_seed: z.number().optional().describe("Seed for loot table generation. Use 0 for random."),
    },
    async ({ x, y, z: zCoord, uuid, slots, loot_table, loot_table_seed }) => {
        const body: Record<string, unknown> = {};
        if (uuid !== undefined) body.uuid = uuid;
        else { body.x = x; body.y = y; body.z = zCoord; }
        if (loot_table !== undefined) {
            body.loot_table = loot_table;
            if (loot_table_seed !== undefined) body.loot_table_seed = loot_table_seed;
        } else if (slots !== undefined) {
            body.slots = slots;
        }
        const data = await modPost("/container", body);
        return textResult(data);
    }
);

// --- block NBT ---

server.tool(
    "get_block_nbt",
    `Get the raw NBT data of a block entity (like suspicious sand, decorated pots, spawners, etc) at a given position.`,
    {
        x: z.number().int().describe("X coordinate of the block"),
        y: z.number().int().describe("Y coordinate of the block"),
        z: z.number().int().describe("Z coordinate of the block"),
    },
    async ({ x, y, z: zCoord }) => {
        const data = await modGet(`/nbt/block?x=${x}&y=${y}&z=${zCoord}`);
        return textResult(data);
    }
);

server.tool(
    "set_block_nbt",
    `Merge the given NBT data into a block entity at a given position.
This modifies the existing NBT by replacing or adding the provided fields. Existing fields not specified in the payload remain untouched.`,
    {
        x: z.number().int().describe("X coordinate of the block"),
        y: z.number().int().describe("Y coordinate of the block"),
        z: z.number().int().describe("Z coordinate of the block"),
        nbt: z.record(z.unknown()).describe("The NBT fields to merge into the block entity. Format as a flat or nested JSON object corresponding to the NBT structure."),
    },
    async ({ x, y, z: zCoord, nbt }) => {
        const data = await modPost(`/nbt/block`, { x, y, z: zCoord, nbt });
        return textResult(data);
    }
);

server.tool(
    "batch_write_containers",
    `Write items or assign loot tables to multiple containers in a single call. Much faster and uses fewer tokens than calling write_container multiple times.`,
    {
        containers: z.array(z.object({
            x: z.number().int().optional().describe("X coordinate of the container"),
            y: z.number().int().optional().describe("Y coordinate of the container"),
            z: z.number().int().optional().describe("Z coordinate of the container"),
            uuid: z.string().optional().describe("UUID of the entity container (like Minecart with Chest). Mutually exclusive with x/y/z."),
            slots: z.array(z.object({
                slot: z.number().int().describe("Slot index (0-based)"),
                id: z.string().describe("Item identifier, e.g. 'minecraft:iron_sword'"),
                count: z.number().int().min(1).max(64).default(1).describe("Stack size"),
                nbt: z.record(z.unknown()).optional().describe("Optional full NBT data for the item"),
            })).optional().describe("Items to place in the container. Omit when setting a loot table."),
            loot_table: z.string().optional().describe("Loot table identifier to assign, e.g. 'minecraft:chests/simple_dungeon'. Mutually exclusive with slots."),
            loot_table_seed: z.number().optional().describe("Seed for loot table generation. Use 0 for random."),
        })).describe("Array of container write operations"),
    },
    async ({ containers }) => {
        const data = await modPost("/container/batch", containers);
        return textResult(data);
    }
);

server.tool(
    "scan_blocks",
    `Scan a bounded region for specific blocks. Returns their coordinates.
Bounds are picked in this order: inline pos1+pos2 (stateless), else the named stored region, else 'default'.`,
    {
        blocks: z.array(z.string()).describe("Array of block IDs to search for, e.g. ['minecraft:diamond_ore']"),
        region: z.string().optional().describe("Named stored region to scan. Ignored when pos1+pos2 are provided. Defaults to 'default'."),
        pos1: posSchema.optional().describe("First corner of an inline bbox."),
        pos2: posSchema.optional().describe("Second corner of an inline bbox."),
    },
    async ({ blocks, region, pos1, pos2 }) => {
        let url = "/scan/blocks";
        const params = new URLSearchParams();
        if (region) params.append("region", region);
        appendBoundsToQuery(params, pos1, pos2);
        if (params.toString()) url += "?" + params.toString();
        const data = await modPost(url, blocks) as any;
        if (data.error) return textResult(data);
        if (!data.blocks || data.blocks.length === 0) return textResult({ count: 0, message: "No matching blocks found." });
        
        const lines: string[] = [];
        if (data.warning) lines.push(`WARNING: ${data.warning}`);
        lines.push(`Found ${data.count} matching block(s):`);
        for (const b of data.blocks) {
            lines.push(`[${b.id}] at (${b.x}, ${b.y}, ${b.z})`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
);

server.tool(
    "scan_entities",
    `Scan a bounded region for specific entities. Returns their coordinates and basic info.
Bounds are picked in this order: inline pos1+pos2 (stateless), else the named stored region, else 'default'.`,
    {
        entities: z.array(z.string()).optional().describe("Array of entity IDs to search for, e.g. ['minecraft:zombie']. Omit or pass empty array to return all entities."),
        region: z.string().optional().describe("Named stored region to scan. Ignored when pos1+pos2 are provided. Defaults to 'default'."),
        pos1: posSchema.optional().describe("First corner of an inline bbox."),
        pos2: posSchema.optional().describe("Second corner of an inline bbox."),
    },
    async ({ entities, region, pos1, pos2 }) => {
        let url = "/scan/entities";
        const params = new URLSearchParams();
        if (region) params.append("region", region);
        appendBoundsToQuery(params, pos1, pos2);
        if (params.toString()) url += "?" + params.toString();
        const data = await modPost(url, entities || []) as any;
        if (data.error) return textResult(data);
        if (!data.entities || data.entities.length === 0) return textResult({ count: 0, message: "No matching entities found." });
        
        const lines: string[] = [];
        if (data.warning) lines.push(`WARNING: ${data.warning}`);
        lines.push(`Found ${data.count} matching entit(ies):`);
        for (const e of data.entities) {
            const name = e.custom_name ? ` | name: ${e.custom_name}` : '';
            lines.push(`[${e.type}] at (${e.x}, ${e.y}, ${e.z})${name} | uuid: ${e.uuid}`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
);

server.tool(
    "get_structure_palette",
    `Read a structure's NBT and return a list of all unique block states used within it.`,
    {
        structure_name: z.string().describe("The structure name, including namespace (e.g. 'mns:mega_fortress/intact')"),
    },
    async ({ structure_name }) => {
        const data = await modGet(`/structure/palette?name=${encodeURIComponent(structure_name)}`) as any;
        return textResult(data);
    }
);

// --- download structure ---

server.tool(
    "download_structure",
    `Download a saved structure block's NBT template file from the remote Minecraft server to your local machine.
Provides seamless synchronization of your building templates without needing SFTP/BisectHosting panels.`,
    {
        structure_name: z.string().describe("The structure name, including namespace (e.g. 'mns:mega_fortress/intact/upper/small_junction_2')"),
        local_dir: z.string().describe("The local absolute directory path where the structure .nbt file should be saved"),
    },
    async ({ structure_name, local_dir }) => {
        try {
            if (!fs.existsSync(local_dir)) {
                return textResult({ error: `Local directory does not exist: ${local_dir}` });
            }

            const url = `${MOD_URL}/download?name=${encodeURIComponent(structure_name)}`;
            const headers: Record<string, string> = {};
            if (API_KEY) {
                headers["Authorization"] = `Bearer ${API_KEY}`;
            }

            const res = await fetch(url, { headers });
            if (!res.ok) {
                const errText = await res.text();
                throw new Error(`HTTP ${res.status} from mod: ${errText}`);
            }

            const arrayBuffer = await res.arrayBuffer();
            const buffer = Buffer.from(arrayBuffer);

            let namespace = "minecraft";
            let structurePath = structure_name;
            if (structure_name.includes(":")) {
                const parts = structure_name.split(":", 2);
                namespace = parts[0];
                structurePath = parts[1];
            }

            // In Minecraft 1.21+, the datapack folder structure has transitioned to singular folder names (e.g. "structure" instead of "structures").
            // Saving structures directly into <local_dir>/<namespace>/structure/<path>.nbt.
            const relativePath = path.join(namespace, "structure", `${structurePath}.nbt`);
            const fullLocalPath = path.join(local_dir, relativePath);

            const targetSubDir = path.dirname(fullLocalPath);
            if (!fs.existsSync(targetSubDir)) {
                fs.mkdirSync(targetSubDir, { recursive: true });
            }

            fs.writeFileSync(fullLocalPath, buffer);

            return textResult({
                success: true,
                message: `Structure '${structure_name}' successfully downloaded locally.`,
                saved_path: fullLocalPath,
                size_bytes: buffer.length,
            });
        } catch (e) {
            return textResult({ error: `Download failed: ${String(e)}` });
        }
    }
);

// --- blocks read/write ---

server.tool(
    "get_blocks",
    `Read blocks from the world. If you provide 'positions', it returns the blocks at those exact coordinates.
If you omit 'positions', it scans a bounded region. Bounds order: inline pos1+pos2 (stateless), else named stored region, else 'default'.`,
    {
        positions: z.array(z.object({
            x: z.number().int(),
            y: z.number().int(),
            z: z.number().int()
        })).optional().describe("Array of specific coordinates to read. If omitted, the entire region is scanned."),
        region: z.string().optional().describe("Named stored region to scan when positions is omitted. Ignored when pos1+pos2 are provided. Defaults to 'default'."),
        pos1: posSchema.optional().describe("First corner of an inline bbox."),
        pos2: posSchema.optional().describe("Second corner of an inline bbox."),
    },
    async ({ positions, region, pos1, pos2 }) => {
        const body: Record<string, unknown> = {};
        if (positions !== undefined) body.positions = positions;
        if (region !== undefined) body.region = region;
        mergeBoundsIntoBody(body, pos1, pos2);
        const data = await modPost("/block/get", body);
        return textResult(data);
    }
);

server.tool(
    "set_blocks",
    `Set blocks in the world. This will NOT trigger block updates or physics (e.g. water won't flow, torches won't pop off).
You can pass block NBT data. The previous block states are saved automatically and can be undone using undo_last_write.
By default, placing blocks outside the active bounds is blocked for safety. Bounds order: inline pos1+pos2 (stateless), else named stored region, else 'default'.`,
    {
        blocks: z.array(z.object({
            x: z.number().int(),
            y: z.number().int(),
            z: z.number().int(),
            block: z.string().describe("Block state string, e.g. 'minecraft:spruce_stairs[facing=north,half=bottom]'"),
            nbt: z.record(z.unknown()).optional().describe("Optional NBT data for block entities"),
        })).describe("Array of block updates. Max 10,000 blocks per call."),
        allow_outside_selection: z.boolean().optional().default(false).describe("If true, allows setting blocks outside the active bounds."),
        region: z.string().optional().describe("Named stored region to bound against. Ignored when pos1+pos2 are provided. Defaults to 'default'."),
        pos1: posSchema.optional().describe("First corner of an inline bbox."),
        pos2: posSchema.optional().describe("Second corner of an inline bbox."),
    },
    async ({ blocks, allow_outside_selection, region, pos1, pos2 }) => {
        const body: Record<string, unknown> = { blocks, allow_outside_selection };
        if (region !== undefined) body.region = region;
        mergeBoundsIntoBody(body, pos1, pos2);
        const data = await modPost("/block/set", body);
        return textResult(data);
    }
);

server.tool(
    "replace_blocks",
    `Bulk replace specific block IDs within a region with a new block ID.
Example: replace all 'minecraft:air' with 'minecraft:structure_void'.
No block updates/physics will occur. Previous block states are saved automatically.
Bounds order: inline pos1+pos2 (stateless), else named stored region, else 'default'.`,
    {
        find: z.array(z.string()).describe("Array of block IDs to replace, e.g. ['minecraft:air', 'minecraft:water']"),
        replace: z.string().describe("The new block state string to place, e.g. 'minecraft:structure_void'"),
        region: z.string().optional().describe("Named stored region to search in. Ignored when pos1+pos2 are provided. Defaults to 'default'."),
        pos1: posSchema.optional().describe("First corner of an inline bbox."),
        pos2: posSchema.optional().describe("Second corner of an inline bbox."),
        dry_run: z.boolean().optional().default(false).describe("If true, only counts the matches and returns a small sample, but does not modify the world."),
        max_blocks: z.number().int().optional().describe("Maximum number of blocks to replace. Defaults to all matches."),
    },
    async ({ find, replace, region, pos1, pos2, dry_run, max_blocks }) => {
        const body: Record<string, unknown> = { find, replace, dry_run };
        if (region !== undefined) body.region = region;
        if (max_blocks !== undefined) body.max_blocks = max_blocks;
        mergeBoundsIntoBody(body, pos1, pos2);
        const data = await modPost("/block/replace", body);
        return textResult(data);
    }
);

server.tool(
    "undo_last_write",
    `Undo a previous set_blocks or replace_blocks operation using the undo_token returned by that operation.
Can only be used once per token.`,
    {
        undo_token: z.string().describe("The token returned by a previous set_blocks or replace_blocks call"),
    },
    async ({ undo_token }) => {
        const data = await modPost("/block/undo", { undo_token });
        return textResult(data);
    }
);

// --- structure block registry (reads StructureBlockSaver's tracker) ---

server.tool(
    "list_structure_blocks",
    `List every structure block tracked by StructureBlockSaver in the target dimension.
Reads SBS's persistent registry, so it sees blocks in unloaded chunks too.
Defaults to the overworld — pass 'dim' to look elsewhere (e.g. 'nether', 'end', 'minecraft:the_nether', or a custom dim id).
Returns { count, total, source, dim, blocks: [{x, y, z, name, mode, dim}] }.
Requires StructureBlockSaver to be installed on the target world.`,
    {
        name_filter: z.string().optional().describe("Only return blocks whose structure name contains this substring."),
        mode_filter: z.string().optional().describe("Only return blocks in this mode: SAVE, LOAD, CORNER, or DATA."),
        dim: z.string().optional().describe("Dimension to read. Defaults to overworld. Accepts 'overworld', 'nether', 'end', or a full namespace:path."),
    },
    async ({ name_filter, mode_filter, dim }) => {
        const params = new URLSearchParams();
        if (name_filter) params.append("name_filter", name_filter);
        if (mode_filter) params.append("mode_filter", mode_filter);
        if (dim) params.append("dim", dim);
        const url = "/structure-blocks" + (params.toString() ? "?" + params.toString() : "");
        const data = await modGet(url) as any;

        if (data.error) {
            return textResult(data);
        }

        if (!data.blocks || data.blocks.length === 0) {
            return textResult({ count: 0, total: data.total ?? 0, dim: data.dim, message: "No structure blocks match." });
        }

        const lines: string[] = [];
        lines.push(`Tracked ${data.count} structure block(s) in ${data.dim} (of ${data.total} total in this dim):`);
        for (const b of data.blocks) {
            const namePart = b.name ? b.name : "<unnamed>";
            lines.push(`[${b.mode}] at (${b.x}, ${b.y}, ${b.z}) | ${namePart}`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
);

server.tool(
    "set_selection_to_structure",
    `Find a saved structure by name via StructureBlockSaver's tracker, then set a stored selection region to a bounding box that covers both the structure block itself AND the region it saves.
Writes state to a stored region — for parallel/stateless agent use, prefer 'find_structure_bounds' and pass the returned pos1/pos2 inline to other tools.
Defaults to the overworld. Pass 'dim' to look elsewhere.
Errors with a candidates list if more than one SB shares the name.`,
    {
        name: z.string().describe("The structure name to look up (e.g. 'mns:mega_fortress/intact/upper/junction_1')."),
        dim: z.string().optional().describe("Dimension to search. Defaults to overworld."),
        region: z.string().optional().describe("Selection region to update. Defaults to 'default'. Pick a unique name if multiple agents use this tool in parallel."),
    },
    async ({ name, dim, region }) => {
        const body: Record<string, unknown> = { name };
        if (dim) body.dim = dim;
        if (region) body.region = region;
        const data = await modPost("/selection/from-structure", body);
        return textResult(data);
    }
);

server.tool(
    "find_structure_bounds",
    `Look up a saved structure by name via StructureBlockSaver's tracker and return the bounding box that covers both the structure block itself and the region it saves — WITHOUT touching any stored selection.
Read-only companion to set_selection_to_structure. Safe to call from parallel agents. Feed the returned pos1/pos2 straight into any scan/edit tool's inline pos1/pos2 params for fully stateless operation.
Errors with a candidates list if more than one SB shares the name.`,
    {
        name: z.string().describe("The structure name to look up."),
        dim: z.string().optional().describe("Dimension to search. Defaults to overworld."),
    },
    async ({ name, dim }) => {
        const params = new URLSearchParams();
        params.append("name", name);
        if (dim) params.append("dim", dim);
        const data = await modGet("/structure-bounds?" + params.toString());
        return textResult(data);
    }
);

server.tool(
    "remove_region",
    `Remove a stored selection region, or remove every stored region at once.
Pass 'name' to remove one region (the 'default' region is reset to empty rather than deleted so the wand always has something to bind).
Pass 'all: true' to remove every region.
Exactly one of 'name' or 'all' must be provided.`,
    {
        name: z.string().optional().describe("Region name to remove."),
        all: z.boolean().optional().describe("When true, remove every stored region."),
    },
    async ({ name, all }) => {
        if ((!name && !all) || (name && all)) {
            return textResult({ error: "Provide exactly one of 'name' or 'all: true'." });
        }
        const body: Record<string, unknown> = {};
        if (all) body.all = true;
        else if (name) body.name = name;
        const data = await modPost("/selection/remove", body);
        return textResult(data);
    }
);

//////////////////////////////
// Start
//////////////////////////////

async function main() {
    const transport = new StdioServerTransport();
    await server.connect(transport);
    // Only log to stderr — stdout is reserved for MCP JSON-RPC messages
    process.stderr.write("Structure Editor MCP server running on stdio\n");
}

main().catch((e) => {
    process.stderr.write(`Fatal error: ${e}\n`);
    process.exit(1);
});
