package net.tfminecraft.drinkbuilder.loaders;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.Cache.Ingredient;

public final class IngredientsLoader {

	public void loadIngredients(File file) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(file);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
			Cache.ingredients = List.of();
			return;
		}

		List<Ingredient> out = Cache.newIngredientList();
		List<?> raw = config.getList("ingredients");
		if (raw != null) {
			for (Object item : raw) {
				Ingredient ingredient = parseIngredient(item);
				if (ingredient != null && !ingredient.id.isEmpty()) {
					out.add(ingredient);
				}
			}
		}
		Cache.ingredients = List.copyOf(out);
	}

	public void loadEffectsBlacklist(File file) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(file);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
			Cache.effectsBlacklist = List.of();
			return;
		}

		List<String> out = Cache.newStringList();
		List<?> raw = config.getList("effects");
		if (raw == null) {
			raw = config.getList("effects-blacklist");
		}
		if (raw != null) {
			for (Object item : raw) {
				if (item == null) {
					continue;
				}
				String name = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
				if (!name.isEmpty() && !out.contains(name)) {
					out.add(name);
				}
			}
		}
		Cache.effectsBlacklist = List.copyOf(out);
	}

	private Ingredient parseIngredient(Object item) {
		if (item instanceof ConfigurationSection section) {
			return new Ingredient(
				section.getString("id", ""),
				section.getString("type", ""),
				section.getString("brewery_token", ""),
				section.getString("label", ""),
				section.getString("category", "other")
			);
		}
		if (item instanceof Map<?, ?> map) {
			return new Ingredient(
				stringOf(map.get("id")),
				stringOf(map.get("type")),
				stringOf(map.get("brewery_token")),
				stringOf(map.get("label")),
				stringOf(map.get("category"))
			);
		}
		return null;
	}

	private static String stringOf(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
