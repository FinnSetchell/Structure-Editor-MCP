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

//////////////////////////////
// MCP Server
//////////////////////////////

const server = new McpServer({
    name: "structure-editor",
    version: "1.0.0",
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
    {},
    async () => {
        const data = await modGet("/selection");
        return textResult(data);
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
    },
    async ({ pos1, pos2 }) => {
        const body: Record<string, unknown> = {};
        if (pos1) body.pos1 = pos1;
        if (pos2) body.pos2 = pos2;
        const data = await modPost("/selection", body);
        return textResult(data);
    }
);

// --- scan ---

server.tool(
    "scan_region",
    `Scan the currently selected region and return every jigsaw block and structure block found within it.
Optional 'format' parameter: 'compact' (default, highly compressed line-by-line format to save tokens) or 'json' (full JSON structure).`,
    {
        format: z.enum(["compact", "json"]).optional().default("compact").describe("Output format. Use 'compact' (default) to save context tokens, or 'json' for raw structured data."),
    },
    async ({ format }) => {
        const data = await modGet("/scan") as { count: number; blocks: Array<Record<string, unknown>> };
        
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
                lines.push(`[Jigsaw] at (${x}, ${y}, ${z}) | ${jp.join(" | ")}`);
            } else if (block.type === "structure_block") {
                const sp = [];
                if (block.name) sp.push(`name: ${block.name}`);
                if (block.author) sp.push(`author: ${block.author}`);
                if (block.mode) sp.push(`mode: ${block.mode}`);
                if (block.metadata) sp.push(`meta: ${block.metadata}`);
                sp.push(`offset: [${block.posX}, ${block.posY}, ${block.posZ}]`);
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
    `Scan the selected region and replace a substring across all jigsaw/structure block string fields.
For example, replace "mymod:old_dir/" with "mymod:new_dir/" across all name/target/pool fields.
Returns a summary of what changed.`,
    {
        find: z.string().describe("Substring to search for in all string fields"),
        replace: z.string().describe("Replacement string"),
        fields_to_check: z.array(z.string())
            .default(["name", "target", "pool", "final_state"])
            .describe("Which fields to apply the replacement to (default: name, target, pool, final_state)"),
        dry_run: z.boolean().default(false)
            .describe("If true, returns what WOULD change without actually applying edits"),
    },
    async ({ find, replace, fields_to_check, dry_run }) => {
        // First scan
        const scanData = await modGet("/scan") as { count: number; blocks: Array<Record<string, unknown>> };

        if (!scanData.blocks || scanData.blocks.length === 0) {
            return textResult({ message: "No jigsaw/structure blocks in selection", changed: 0 });
        }

        const edits: Array<{ x: number; y: number; z: number; fields: Record<string, string> }> = [];
        const changes: Array<{ pos: string; field: string; from: string; to: string }> = [];

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

        if (dry_run || edits.length === 0) {
            return textResult({
                dry_run,
                would_change: changes.length,
                changes,
            });
        }

        const result = await modPost("/batch", edits);
        return textResult({
            changed: changes.length,
            changes,
            batch_result: result,
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
    },
    async ({ x, y, z: zCoord }) => {
        const body: Record<string, number> = {};
        if (x !== undefined && y !== undefined && zCoord !== undefined) {
            body.x = x;
            body.y = y;
            body.z = zCoord;
        }
        const data = await modPost("/save", body);
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
        x: z.number().int().describe("X coordinate of the container"),
        y: z.number().int().describe("Y coordinate of the container"),
        z: z.number().int().describe("Z coordinate of the container"),
    },
    async ({ x, y, z: zCoord }) => {
        const data = await modGet(`/container?x=${x}&y=${y}&z=${zCoord}`);
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
        x: z.number().int().describe("X coordinate of the container"),
        y: z.number().int().describe("Y coordinate of the container"),
        z: z.number().int().describe("Z coordinate of the container"),
        slots: z.array(z.object({
            slot: z.number().int().describe("Slot index (0-based)"),
            id: z.string().describe("Item identifier, e.g. 'minecraft:iron_sword'"),
            count: z.number().int().min(1).max(64).default(1).describe("Stack size"),
            nbt: z.record(z.unknown()).optional().describe("Optional full NBT data for the item (for enchantments, custom names, etc.)"),
        })).optional().describe("Items to place in the container. Omit when setting a loot table."),
        loot_table: z.string().optional().describe("Loot table identifier to assign, e.g. 'minecraft:chests/simple_dungeon'. Mutually exclusive with slots."),
        loot_table_seed: z.number().optional().describe("Seed for loot table generation. Use 0 for random."),
    },
    async ({ x, y, z: zCoord, slots, loot_table, loot_table_seed }) => {
        const body: Record<string, unknown> = { x, y, z: zCoord };
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
