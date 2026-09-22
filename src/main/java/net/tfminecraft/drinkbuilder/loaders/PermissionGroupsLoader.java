package net.tfminecraft.drinkbuilder.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.objects.PermissionGroupDefinition;

public final class PermissionGroupsLoader {

	public void load(File file) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(file);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
			Cache.defaultNameColourStops = 0;
			Cache.defaultAllowDrinkTexture = false;
			Cache.permissionGroups = List.of();
			return;
		}

		ConfigurationSection defaults = config.getConfigurationSection("defaults");
		Cache.defaultNameColourStops = defaults != null
			? Math.max(0, defaults.getInt("name-colour-stops", 0))
			: 0;
		Cache.defaultAllowDrinkTexture = defaults != null
			&& defaults.getBoolean("allow-drink-texture", false);

		List<PermissionGroupDefinition> groups = new ArrayList<>();
		ConfigurationSection groupsSection = config.getConfigurationSection("groups");
		if (groupsSection != null) {
			int autoTier = 0;
			for (String id : groupsSection.getKeys(false)) {
				ConfigurationSection row = groupsSection.getConfigurationSection(id);
				if (row == null) {
					continue;
				}
				String permission = row.getString("permission", "");
				if (permission == null || permission.isBlank()) {
					continue;
				}
				int tier = row.contains("tier") ? row.getInt("tier") : autoTier++;
				boolean hasStops = row.contains("name-colour-stops");
				boolean hasTexture = row.contains("allow-drink-texture");
				int stops = hasStops
					? Math.max(0, row.getInt("name-colour-stops"))
					: Cache.defaultNameColourStops;
				boolean texture = hasTexture
					? row.getBoolean("allow-drink-texture")
					: Cache.defaultAllowDrinkTexture;
				groups.add(new PermissionGroupDefinition(
					id,
					permission.trim(),
					tier,
					stops,
					texture,
					hasStops,
					hasTexture
				));
			}
			groups.sort(Comparator.comparingInt(PermissionGroupDefinition::getTier));
		}
		Cache.permissionGroups = List.copyOf(groups);
	}
}
