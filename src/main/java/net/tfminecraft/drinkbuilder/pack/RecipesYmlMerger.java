package net.tfminecraft.drinkbuilder.pack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.PendingDrink;

/**
 * Upsert one BreweryX recipe under recipes.yml keyed by submission id.
 */
public final class RecipesYmlMerger {

	private RecipesYmlMerger() {}

	public static void merge(
		JavaPlugin plugin,
		PendingDrink drink,
		Integer customModelData,
		Logger log
	) throws IOException {
		if (drink == null || drink.id == null || drink.id.isBlank()) {
			throw new IOException("drink id required");
		}
		String key = drink.id.trim();
		File recipesFile = new File(
			resolvePath(plugin, Cache.breweryxFolder),
			"recipes.yml"
		);
		if (!recipesFile.getParentFile().exists()
			&& !recipesFile.getParentFile().mkdirs()) {
			throw new IOException("BreweryX folder missing: " + recipesFile.getParent());
		}

		FileConfiguration yaml = recipesFile.exists()
			? YamlConfiguration.loadConfiguration(recipesFile)
			: new YamlConfiguration();
		ConfigurationSection recipes = yaml.getConfigurationSection("recipes");
		if (recipes == null) {
			recipes = yaml.createSection("recipes");
		}

		Map<String, Object> recipe = drink.recipe;
		ConfigurationSection section = recipes.createSection(key);

		String display = drink.displayName == null ? key : drink.displayName.trim();
		String names = bakeQualityNames(recipe, display);
		section.set("name", names);
		section.set("enabled", true);

		List<String> ingredients = mapIngredients(recipe.get("ingredients"));
		if (ingredients.isEmpty()) {
			throw new IOException("recipe has no mappable ingredients for " + key);
		}
		section.set("ingredients", ingredients);

		setInt(section, "cookingtime", recipe.get("cooking_time"), 0);
		setInt(section, "distillruns", recipe.get("distill_runs"), 0);
		Object distillTime = recipe.get("distill_time");
		if (distillTime != null) {
			setInt(section, "distilltime", distillTime, 0);
		}
		Object wood = recipe.get("wood");
		if (wood != null && !String.valueOf(wood).isBlank()) {
			section.set("wood", String.valueOf(wood).trim().toLowerCase(Locale.ROOT));
		}
		setInt(section, "age", recipe.get("age"), 0);
		setInt(section, "difficulty", recipe.get("difficulty"), 1);
		setInt(section, "alcohol", recipe.get("alcohol"), 0);

		List<String> lore = bakeLore(recipe.get("lore"));
		if (!lore.isEmpty()) {
			section.set("lore", lore);
		}
		String drinkMessage = stringVal(recipe.get("drink_message"));
		if (drinkMessage != null && !drinkMessage.isBlank()) {
			section.set(
				"drinkmessage",
				bakeColourStops(drinkMessage, colourList(recipe.get("drink_message_colours")))
			);
		}
		String drinkTitle = stringVal(recipe.get("drink_title"));
		if (drinkTitle != null && !drinkTitle.isBlank()) {
			section.set(
				"drinktitle",
				bakeColourStops(drinkTitle, colourList(recipe.get("drink_title_colours")))
			);
		}
		if (Boolean.TRUE.equals(asBoolean(recipe.get("glint")))) {
			section.set("glint", true);
		}

		List<String> effects = mapEffects(recipe.get("effects"));
		if (!effects.isEmpty()) {
			section.set("effects", effects);
		}

		// Never write player/server commands from player submissions.

		if (customModelData != null) {
			section.set("customModelData", customModelData);
		} else {
			String color = stringVal(recipe.get("color"));
			if (color == null || color.isBlank()) {
				throw new IOException("color-only drink missing recipe.color");
			}
			color = color.trim();
			if (color.startsWith("#")) {
				color = color.substring(1);
			}
			section.set("color", color);
		}

		File tmp = new File(recipesFile.getParentFile(), "recipes.yml.tmp");
		yaml.save(tmp);
		try {
			Files.move(
				tmp.toPath(),
				recipesFile.toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE
			);
		} catch (IOException atomicFail) {
			Files.move(
				tmp.toPath(),
				recipesFile.toPath(),
				StandardCopyOption.REPLACE_EXISTING
			);
		}

		if (log != null) {
			log.info("[brewery] merged recipe key=" + key
				+ (customModelData != null
					? (" cmd=" + customModelData)
					: (" color=" + section.get("color"))));
		}
	}

	public static boolean remove(JavaPlugin plugin, String submissionId, Logger log)
		throws IOException {
		String key = submissionId == null ? "" : submissionId.trim();
		if (key.isEmpty()) {
			throw new IOException("drink id required");
		}
		File recipesFile = new File(
			resolvePath(plugin, Cache.breweryxFolder),
			"recipes.yml"
		);
		if (!recipesFile.isFile()) {
			if (log != null) {
				log.info("[brewery] recipes.yml missing; nothing to remove for " + key);
			}
			return false;
		}
		FileConfiguration yaml = YamlConfiguration.loadConfiguration(recipesFile);
		ConfigurationSection recipes = yaml.getConfigurationSection("recipes");
		if (recipes == null || !recipes.isConfigurationSection(key)) {
			if (log != null) {
				log.info("[brewery] no recipe key " + key);
			}
			return false;
		}
		recipes.set(key, null);
		File tmp = new File(recipesFile.getParentFile(), "recipes.yml.tmp");
		yaml.save(tmp);
		try {
			Files.move(
				tmp.toPath(),
				recipesFile.toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE
			);
		} catch (IOException atomicFail) {
			Files.move(
				tmp.toPath(),
				recipesFile.toPath(),
				StandardCopyOption.REPLACE_EXISTING
			);
		}
		if (log != null) {
			log.info("[brewery] removed recipe key=" + key);
		}
		return true;
	}

	private static List<String> mapIngredients(Object raw) throws IOException {
		List<String> out = new ArrayList<>();
		if (!(raw instanceof List<?> list)) {
			return out;
		}
		for (Object row : list) {
			if (!(row instanceof Map<?, ?> map)) {
				continue;
			}
			Object idObj = map.get("id");
			Object amountObj = map.get("amount");
			String id = idObj == null ? "" : String.valueOf(idObj).trim().toLowerCase(Locale.ROOT);
			if (id.isEmpty()) {
				continue;
			}
			int amount = 1;
			try {
				amount = Integer.parseInt(String.valueOf(amountObj));
			} catch (Exception ignored) {
				amount = 1;
			}
			if (amount < 1) {
				amount = 1;
			}
			String token = null;
			for (Cache.Ingredient ing : Cache.ingredients) {
				if (ing.id.equals(id)) {
					token = ing.breweryToken;
					break;
				}
			}
			if (token == null || token.isBlank()) {
				throw new IOException("no brewery_token for ingredient id=" + id);
			}
			out.add(token + "/" + amount);
		}
		return out;
	}

	private static List<String> mapEffects(Object raw) {
		List<String> out = new ArrayList<>();
		if (!(raw instanceof List<?> list)) {
			return out;
		}
		for (Object row : list) {
			if (row instanceof String s) {
				String t = s.trim();
				if (!t.isEmpty()) {
					out.add(t.toUpperCase(Locale.ROOT));
				}
				continue;
			}
			if (!(row instanceof Map<?, ?> map)) {
				continue;
			}
			String type = firstString(map, "type", "name");
			if (type == null || type.isBlank()) {
				continue;
			}
			type = type.trim().toUpperCase(Locale.ROOT);
			Object level = map.get("level");
			Object duration = map.get("duration");
			if (level != null && duration != null) {
				out.add(type + "/" + level + "/" + duration);
			} else if (duration != null) {
				out.add(type + "/" + duration);
			} else if (level != null) {
				out.add(type + "/" + level);
			} else {
				out.add(type);
			}
		}
		return out;
	}

	private static String bakeQualityNames(Map<String, Object> recipe, String display) {
		String names = stringVal(recipe.get("names"));
		String bad;
		String normal;
		String good;
		if (names == null || names.isBlank()) {
			bad = display;
			normal = display;
			good = display;
		} else {
			String[] parts = names.split("/", -1);
			if (parts.length == 3) {
				bad = parts[0].trim();
				normal = parts[1].trim();
				good = parts[2].trim();
			} else {
				bad = display;
				normal = display;
				good = display;
			}
		}
		if (bad.isEmpty()) {
			bad = display;
		}
		if (normal.isEmpty()) {
			normal = display;
		}
		if (good.isEmpty()) {
			good = display;
		}
		// One colour set for all qualities; optional per-quality lists are legacy fallbacks.
		List<String> normalColours = colourList(recipe.get("name_colours"));
		List<String> badColours = colourList(recipe.get("name_bad_colours"));
		List<String> goodColours = colourList(recipe.get("name_good_colours"));
		if (badColours.isEmpty()) {
			badColours = normalColours;
		}
		if (goodColours.isEmpty()) {
			goodColours = normalColours;
		}
		return bakeColourStops(bad, badColours)
			+ "/"
			+ bakeColourStops(normal, normalColours)
			+ "/"
			+ bakeColourStops(good, goodColours);
	}

	private static List<String> bakeLore(Object raw) {
		List<String> out = new ArrayList<>();
		if (!(raw instanceof List<?> list)) {
			return out;
		}
		for (Object row : list) {
			if (row instanceof Map<?, ?> map) {
				String text = firstString(map, "text");
				if (text == null || text.isBlank()) {
					continue;
				}
				out.add(bakeColourStops(text, colourList(map.get("colours"))));
				continue;
			}
			if (row == null) {
				continue;
			}
			String s = String.valueOf(row).trim();
			if (!s.isEmpty()) {
				out.add(s);
			}
		}
		return out;
	}

	private static List<String> colourList(Object raw) {
		List<String> out = new ArrayList<>();
		if (!(raw instanceof List<?> list)) {
			return out;
		}
		for (Object row : list) {
			if (row == null) {
				continue;
			}
			String s = String.valueOf(row).trim();
			if (!s.isEmpty()) {
				out.add(s);
			}
		}
		return out;
	}

	/**
	 * RGB-interpolated gradient across the string; emit &#rrggbb prefixes (TFMC style).
	 * Matches website previewSpans / TLibs applyColourGradient.
	 */
	static String bakeColourStops(String plain, List<String> colours) {
		String text = plain == null ? "" : plain;
		if (text.isEmpty() || colours == null || colours.isEmpty()) {
			return text;
		}
		List<int[]> rgbStops = new ArrayList<>();
		for (String token : colours) {
			String hex = normalizeHex(token);
			if (hex != null) {
				rgbStops.add(parseRgb(hex));
			}
		}
		if (rgbStops.isEmpty()) {
			return text;
		}
		int length = text.length();
		StringBuilder out = new StringBuilder(length * 10);
		if (rgbStops.size() == 1) {
			String hex = formatRgb(rgbStops.get(0));
			for (int i = 0; i < length; i++) {
				out.append("&#").append(hex).append(text.charAt(i));
			}
			return out.toString();
		}
		int stops = rgbStops.size();
		for (int i = 0; i < length; i++) {
			double t = length == 1 ? 0.0 : (double) i / (length - 1);
			int segment = (int) Math.floor(t * (stops - 1));
			if (segment >= stops - 1) {
				segment = stops - 2;
			}
			double localT = t * (stops - 1) - segment;
			String hex = formatRgb(lerpRgb(rgbStops.get(segment), rgbStops.get(segment + 1), localT));
			out.append("&#").append(hex).append(text.charAt(i));
		}
		return out.toString();
	}

	private static int[] parseRgb(String hex) {
		int v = Integer.parseInt(hex, 16);
		return new int[] {(v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff};
	}

	private static String formatRgb(int[] rgb) {
		return String.format(Locale.ROOT, "%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
	}

	private static int[] lerpRgb(int[] from, int[] to, double t) {
		double c = Math.max(0.0, Math.min(1.0, t));
		return new int[] {
			(int) Math.round(from[0] + (to[0] - from[0]) * c),
			(int) Math.round(from[1] + (to[1] - from[1]) * c),
			(int) Math.round(from[2] + (to[2] - from[2]) * c),
		};
	}

	private static String normalizeHex(String token) {
		if (token == null) {
			return null;
		}
		String t = token.trim();
		if (t.startsWith("#")) {
			t = t.substring(1);
		}
		if (t.length() != 6) {
			return null;
		}
		for (int i = 0; i < 6; i++) {
			char c = t.charAt(i);
			boolean ok = (c >= '0' && c <= '9')
				|| (c >= 'a' && c <= 'f')
				|| (c >= 'A' && c <= 'F');
			if (!ok) {
				return null;
			}
		}
		return t.toLowerCase(Locale.ROOT);
	}

	private static String firstString(Map<?, ?> map, String... keys) {
		for (String key : keys) {
			Object v = map.get(key);
			if (v != null) {
				String s = String.valueOf(v).trim();
				if (!s.isEmpty()) {
					return s;
				}
			}
		}
		return null;
	}

	private static String stringVal(Object raw) {
		return raw == null ? null : String.valueOf(raw);
	}

	private static Boolean asBoolean(Object raw) {
		if (raw instanceof Boolean b) {
			return b;
		}
		if (raw == null) {
			return null;
		}
		String s = String.valueOf(raw).trim();
		if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
			return true;
		}
		if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
			return false;
		}
		return null;
	}

	private static void setInt(
		ConfigurationSection section,
		String key,
		Object raw,
		int defaultVal
	) {
		if (raw == null) {
			section.set(key, defaultVal);
			return;
		}
		try {
			section.set(key, (int) Double.parseDouble(String.valueOf(raw)));
		} catch (Exception e) {
			section.set(key, defaultVal);
		}
	}

	static File resolvePath(JavaPlugin plugin, String configured) {
		File asIs = new File(configured == null ? "" : configured);
		if (asIs.isAbsolute()) {
			return asIs;
		}
		File serverRoot = plugin.getDataFolder().getParentFile();
		if (serverRoot != null) {
			serverRoot = serverRoot.getParentFile();
		}
		if (serverRoot == null) {
			return asIs;
		}
		return new File(serverRoot, configured);
	}
}
