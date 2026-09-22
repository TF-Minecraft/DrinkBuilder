package net.tfminecraft.drinkbuilder.pack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.Cache;

/**
 * Allocate custom model data IDs within the configured drink CMD range.
 * Freed CMDs are reused before advancing {@code next}.
 */
public final class CmdAllocator {

	private final JavaPlugin plugin;
	private final File stateFile;
	private int next;
	private final LinkedHashSet<Integer> freed = new LinkedHashSet<>();

	public CmdAllocator(JavaPlugin plugin) {
		this.plugin = plugin;
		this.stateFile = new File(plugin.getDataFolder(), "cmd-state.yml");
		load();
	}

	public synchronized int peekNext() {
		clampToRange();
		if (!freed.isEmpty()) {
			return freed.iterator().next();
		}
		return next;
	}

	public synchronized int allocate() {
		clampToRange();
		if (!freed.isEmpty()) {
			int recycled = freed.iterator().next();
			freed.remove(recycled);
			save();
			return recycled;
		}
		if (next > Cache.cmdMax) {
			throw new IllegalStateException(
				"Drink CMD range exhausted (" + Cache.cmdMin + "-" + Cache.cmdMax + ")"
			);
		}
		int allocated = next;
		next++;
		save();
		return allocated;
	}

	public synchronized void free(int cmd) {
		if (cmd < Cache.cmdMin || cmd > Cache.cmdMax) {
			return;
		}
		if (cmd >= next) {
			return;
		}
		freed.add(cmd);
		save();
	}

	public synchronized void reloadBounds() {
		clampToRange();
		freed.removeIf(c -> c < Cache.cmdMin || c > Cache.cmdMax);
		save();
	}

	private void clampToRange() {
		if (next < Cache.cmdMin) {
			next = Cache.cmdMin;
		}
		if (next > Cache.cmdMax + 1) {
			next = Cache.cmdMax + 1;
		}
	}

	private void load() {
		next = Cache.cmdMin;
		freed.clear();
		if (!stateFile.exists()) {
			save();
			return;
		}
		FileConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
		next = yaml.getInt("next", Cache.cmdMin);
		List<?> raw = yaml.getList("freed");
		if (raw != null) {
			for (Object item : raw) {
				if (item instanceof Number n) {
					freed.add(n.intValue());
				} else if (item != null) {
					try {
						freed.add(Integer.parseInt(String.valueOf(item).trim()));
					} catch (NumberFormatException ignored) {
						// skip
					}
				}
			}
		}
		clampToRange();
		freed.removeIf(c -> c < Cache.cmdMin || c > Cache.cmdMax || c >= next);
	}

	private void save() {
		FileConfiguration yaml = new YamlConfiguration();
		yaml.set("next", next);
		yaml.set("min", Cache.cmdMin);
		yaml.set("max", Cache.cmdMax);
		List<Integer> freedList = new ArrayList<>(freed);
		Collections.sort(freedList);
		yaml.set("freed", freedList);
		try {
			yaml.save(stateFile);
		} catch (IOException e) {
			plugin.getLogger().warning("[cmd] could not save cmd-state.yml: " + e.getMessage());
		}
	}
}
