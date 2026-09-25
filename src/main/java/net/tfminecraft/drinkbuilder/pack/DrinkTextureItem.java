package net.tfminecraft.drinkbuilder.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

/**
 * ItemsAdder item definition for a custom drink texture.
 *
 * <p>A legacy {@code resource} potion copies the vanilla potion item model, including its
 * potion colour tint, so the custom texture is recolored. The graphics colour {@code WHITE}
 * replaces that tint with a fixed white multiply, which leaves the texture unchanged.
 */
public final class DrinkTextureItem {

	static final String NEUTRAL_TINT = "WHITE";

	private DrinkTextureItem() {}

	public static void apply(ConfigurationSection item, String displayName, String texturePath) {
		item.set("display_name", null);
		item.set("resource", null);
		item.set("specific_properties", null);
		item.set("name", displayName);
		item.set("material", "POTION");
		item.set("graphics.texture", texturePath);
		item.set("graphics.color", NEUTRAL_TINT);
	}

	/**
	 * Keep a forced custom model data id in ItemsAdder's cache.
	 * Graphics items do not accept {@code resource.model_id}; the cache is what
	 * stops a later reload from assigning a different id.
	 */
	public static void pinPotionCmd(Path cacheFile, String namespacedId, int cmd) throws IOException {
		if (namespacedId == null || namespacedId.isBlank() || namespacedId.indexOf(':') < 0) {
			throw new IOException("namespaced id required to pin drink CMD");
		}
		if (!Files.isRegularFile(cacheFile)) {
			throw new IOException("ItemsAdder CMD cache missing: " + cacheFile);
		}
		List<String> lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
		String entry = "  " + namespacedId.trim() + ": " + cmd;
		int potion = -1;
		for (int i = 0; i < lines.size(); i++) {
			if ("POTION:".equals(lines.get(i).trim())) {
				potion = i;
				break;
			}
		}
		if (potion < 0) {
			throw new IOException("POTION section missing from " + cacheFile);
		}
		int insertAt = potion + 1;
		String key = namespacedId.trim();
		for (int i = potion + 1; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.isEmpty()) {
				continue;
			}
			if (!line.startsWith(" ") && !line.startsWith("\t")) {
				break;
			}
			insertAt = i + 1;
			String trimmed = line.trim();
			if (trimmed.startsWith(key + ":")) {
				if (!trimmed.equals(key + ": " + cmd)) {
					lines.set(i, entry);
					Files.write(cacheFile, lines, StandardCharsets.UTF_8);
				}
				return;
			}
		}
		List<String> updated = new ArrayList<>(lines);
		updated.add(insertAt, entry);
		Files.write(cacheFile, updated, StandardCharsets.UTF_8);
	}
}
