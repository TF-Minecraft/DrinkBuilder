package net.tfminecraft.drinkbuilder.loaders;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.drinkbuilder.Cache;

public final class CategoriesLoader {

	public void load(File file) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(file);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
			Cache.categories = Map.of();
			return;
		}

		Map<String, String> out = new LinkedHashMap<>();
		ConfigurationSection section = config.getConfigurationSection("categories");
		if (section != null) {
			for (String key : section.getKeys(false)) {
				if (key == null || key.isBlank()) {
					continue;
				}
				String id = key.trim().toLowerCase(Locale.ROOT);
				String label = section.getString(key, id);
				if (label == null || label.isBlank()) {
					label = id;
				}
				out.put(id, label.trim());
			}
		}
		Cache.categories = Map.copyOf(out);
	}
}
