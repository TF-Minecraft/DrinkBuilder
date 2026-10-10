package net.tfminecraft.drinkbuilder.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Potion model overrides that give BreweryX bottles their drink textures.
 *
 * <p>BreweryX marks a bottle with the drink's custom model data. A {@code graphics}
 * ItemsAdder item renders through its own item model, so ItemsAdder adds no potion
 * override for that number and the bottle stays a vanilla potion. Each drink gets a
 * model with the texture on layer1, because the potion colour tint only applies to
 * layer0, which stays transparent. ItemsAdder merges this namespace's potion.json
 * with every other pack's potion overrides.
 */
public final class DrinkBottleModels {

	static final String FOLDER = "bottle";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** Fully transparent 16x16 RGBA PNG for layer0. */
	private static final byte[] EMPTY_PNG = Base64.getDecoder().decode(
		"iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAEklEQVR4nGNgGAWjYBSMAggAAAQQAAFVN1rQAAAAAElFTkSuQmCC");

	private DrinkBottleModels() {}

	/** Sync, logging instead of throwing, so a bottle model problem never blocks a drink change. */
	public static boolean syncQuietly(JavaPlugin plugin, Logger log) {
		try {
			return sync(plugin);
		} catch (IOException | RuntimeException e) {
			if (log != null) {
				log.warning("[ia] bottle models not updated: " + e.getMessage());
			}
			return false;
		}
	}

	public static boolean sync(JavaPlugin plugin) throws IOException {
		Path root = IaDrinksWriter.resolveDrinksRoot(plugin).toPath();
		return sync(root, DrinksNamespace.current(), IaDrinksWriter.cmdCache(root.toFile()).toPath());
	}

	/**
	 * Rewrite the bottle models and potion overrides for every textured drink.
	 *
	 * @return true if any file changed, so the pack needs a rebuild
	 */
	static boolean sync(Path root, String ns, Path cache) throws IOException {
		synchronized (IaDrinksWriter.class) {
			Path itemsYml = root.resolve("configs/items.yml");
			if (!Files.isRegularFile(itemsYml)) {
				// A missing items.yml is not "no drinks"; leave every bottle model in place.
				return false;
			}
			Map<Integer, String> drinks = texturedDrinks(root, itemsYml, ns, cache);
			Path resources = root.resolve("resourcepack");
			Path models = resources.resolve(ns + "/models/item/" + FOLDER);
			Path empty = resources.resolve(ns + "/textures/item/" + FOLDER + "/empty.png");
			Path potion = resources.resolve("minecraft/models/item/potion.json");

			boolean changed = false;
			Set<String> kept = new HashSet<>();
			JsonArray overrides = new JsonArray();
			for (var drink : drinks.entrySet()) {
				String sid = drink.getValue();
				kept.add(sid + ".json");
				changed |= writeIfChanged(models.resolve(sid + ".json"), bottleModel(ns, sid));
				JsonObject override = new JsonObject();
				JsonObject predicate = new JsonObject();
				predicate.addProperty("custom_model_data", drink.getKey());
				override.add("predicate", predicate);
				override.addProperty("model", ns + ":item/" + FOLDER + "/" + sid);
				overrides.add(override);
			}
			if (Files.isDirectory(models)) {
				try (DirectoryStream<Path> stale = Files.newDirectoryStream(models, "*.json")) {
					for (Path model : stale) {
						if (!kept.contains(model.getFileName().toString())) {
							changed |= Files.deleteIfExists(model);
						}
					}
				}
			}
			if (drinks.isEmpty()) {
				changed |= Files.deleteIfExists(potion);
				changed |= Files.deleteIfExists(empty);
				return changed;
			}
			changed |= writeIfChanged(empty, EMPTY_PNG);
			changed |= writeIfChanged(potion, potionModel(overrides));
			return changed;
		}
	}

	/** Drinks with an items.yml entry, a texture and a pinned CMD, ordered by CMD. */
	private static Map<Integer, String> texturedDrinks(Path root, Path itemsYml, String ns, Path cache)
		throws IOException {
		Map<String, Integer> cmds = potionCmds(cache, ns);
		Map<Integer, String> drinks = new TreeMap<>();
		ConfigurationSection items = IaDrinksWriter.loadYaml(itemsYml).getConfigurationSection("items");
		if (items == null) {
			return drinks;
		}
		for (String sid : items.getKeys(false)) {
			Integer cmd = cmds.get(sid);
			if (cmd != null && Files.isRegularFile(root.resolve("resourcepack/" + ns + "/textures/item/" + sid + ".png"))) {
				drinks.put(cmd, sid);
			}
		}
		return drinks;
	}

	/** CMDs ItemsAdder pinned for this namespace's potions, keyed by item id without the namespace. */
	static Map<String, Integer> potionCmds(Path cache, String ns) throws IOException {
		if (!Files.isRegularFile(cache)) {
			throw new IOException("ItemsAdder CMD cache missing: " + cache);
		}
		Map<String, Integer> cmds = new HashMap<>();
		List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
		boolean inPotion = false;
		String prefix = ns + ":";
		for (String line : lines) {
			if (line.isBlank()) {
				continue;
			}
			if (!line.startsWith(" ") && !line.startsWith("\t")) {
				inPotion = "POTION:".equals(line.trim());
				continue;
			}
			String entry = line.trim();
			int split = entry.lastIndexOf(": ");
			if (!inPotion || !entry.startsWith(prefix) || split < 0) {
				continue;
			}
			try {
				cmds.put(entry.substring(prefix.length(), split), Integer.parseInt(entry.substring(split + 2).trim()));
			} catch (NumberFormatException ignored) {
				// Not a CMD entry; ItemsAdder only writes integers here.
			}
		}
		return cmds;
	}

	private static byte[] bottleModel(String ns, String sid) {
		JsonObject textures = new JsonObject();
		textures.addProperty("layer0", ns + ":item/" + FOLDER + "/empty");
		textures.addProperty("layer1", ns + ":item/" + sid);
		textures.addProperty("particle", ns + ":item/" + sid);
		JsonObject model = new JsonObject();
		model.addProperty("parent", "minecraft:item/generated");
		model.add("textures", textures);
		return json(model);
	}

	private static byte[] potionModel(JsonArray overrides) {
		// Same base as the vanilla potion model, so bottles without a drink CMD are unchanged.
		JsonObject textures = new JsonObject();
		textures.addProperty("layer0", "minecraft:item/potion_overlay");
		textures.addProperty("layer1", "minecraft:item/potion");
		JsonObject model = new JsonObject();
		model.addProperty("parent", "minecraft:item/generated");
		model.add("textures", textures);
		model.add("overrides", overrides);
		return json(model);
	}

	private static byte[] json(JsonObject value) {
		return (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
	}

	private static boolean writeIfChanged(Path path, byte[] data) throws IOException {
		if (Files.isRegularFile(path) && Arrays.equals(Files.readAllBytes(path), data)) {
			return false;
		}
		Files.createDirectories(path.getParent());
		Files.write(path, data);
		return true;
	}
}
