package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Tools that work in any world, single-player or multiplayer: decompiling a data-only mod's code when Claude needs it.
 * Decompiling runs Vineflower in a separate JVM, a few seconds for most mods; the call waits for it.
 */
public class ModTools {
	private final KnowledgeBase knowledge;

	public ModTools(KnowledgeBase knowledge) {
		this.knowledge = knowledge;
	}

	public JsonArray definitions() {
		JsonArray tools = new JsonArray();
		tools.add(WorldTools.tool("decompile_mod",
			"Decompile an installed mod's Java code so you can search it. Only for mods the reference file list marks as "
				+ "\"" + KnowledgeBase.NOT_DECOMPILED + "\"; their data and lang files are already there. Takes a few "
				+ "seconds, up to a minute for big mods. Returns the folder of the source, relative to the current directory.",
			"""
			{"type": "object", "properties": {
			  "mod": {"type": "string", "description": "The mod id in [brackets] in the reference file list, e.g. \\"veinminer\\"."}
			}, "required": ["mod"]}
			"""));
		return tools;
	}

	public boolean has(String name) {
		return name.equals("decompile_mod");
	}

	public JsonElement call(String name, JsonObject args) {
		if (!has(name)) {
			throw new IllegalArgumentException("Unknown tool: " + name);
		}

		String id = args.has("mod") ? args.get("mod").getAsString().trim() : "";
		KnowledgeBase.Source mod = knowledge.source(id).orElseThrow(() -> new IllegalArgumentException(
			"No mod with id \"" + id + "\". Use the id in [brackets] from the reference file list."));
		KnowledgeBase.Status before = knowledge.status(mod);

		if (!before.hasCode()) {
			decompile(mod, before);
		}

		JsonObject out = new JsonObject();
		out.addProperty("mod", mod.id());
		out.addProperty("name", mod.label());
		out.addProperty("source", knowledge.codeFolder(mod));
		out.addProperty("status", before.hasCode() ? "already decompiled" : "decompiled");
		return out;
	}

	private void decompile(KnowledgeBase.Source mod, KnowledgeBase.Status before) {
		if (!before.canDecompile()) {
			throw new IllegalArgumentException(mod.label() + " has no code to decompile (" + before.reason()
				+ "). Answer from its data and lang files.");
		}

		ClaudeChatClient.LOGGER.info("Decompiling {} for Claude", mod.label());
		knowledge.buildCodeNow(mod, message -> { });
		KnowledgeBase.Status after = knowledge.status(mod);

		if (!after.hasCode()) {
			String why = after.failure() != null ? " (" + after.failure() + ")" : "";
			throw new IllegalStateException("Decompiling " + mod.label() + " failed" + why + ". Answer from its data and lang files.");
		}
	}
}
