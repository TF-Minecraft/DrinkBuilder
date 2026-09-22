package net.tfminecraft.drinkbuilder.loaders;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.drinkbuilder.Cache;

public final class ConfigLoader {

	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
			return;
		}

		String brewery = config.getString("paths.breweryx-folder", "plugins/BreweryX");
		Cache.breweryxFolder = brewery == null ? "plugins/BreweryX" : brewery.trim();

		String ia = config.getString(
			"paths.itemsadder-tfmc-drinks",
			"plugins/ItemsAdder/contents/tfmc_drinks"
		);
		Cache.itemsAdderTfmcDrinks = ia == null
			? "plugins/ItemsAdder/contents/tfmc_drinks"
			: ia.trim();

		Cache.cmdMin = config.getInt("cmd.min", 20000);
		Cache.cmdMax = config.getInt("cmd.max", 29999);
		if (Cache.cmdMax < Cache.cmdMin) {
			int swap = Cache.cmdMin;
			Cache.cmdMin = Cache.cmdMax;
			Cache.cmdMax = swap;
		}

		Cache.iaReloadDelaySeconds = Math.max(0, config.getInt("ia-reload-delay-seconds", 8));

		Cache.packPollIntervalSeconds = config.getInt("pack-apply.poll-interval-seconds", 120);
		if (Cache.packPollIntervalSeconds < 0) {
			Cache.packPollIntervalSeconds = 0;
		}

		String forceTime = config.getString("pack-apply.force-reload-time", "06:00");
		Cache.forceReloadTime = forceTime == null ? "" : forceTime.trim();
	}
}
