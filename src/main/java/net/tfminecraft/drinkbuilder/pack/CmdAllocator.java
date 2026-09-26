package net.tfminecraft.drinkbuilder.pack;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
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
			if (!save()) {
				freed.addFirst(recycled);
				throw new IllegalStateException("Could not persist drink CMD allocation");
			}
			return recycled;
		}
		if (next > Cache.cmdMax) {
			throw new IllegalStateException(
				"Drink CMD range exhausted (" + Cache.cmdMin + "-" + Cache.cmdMax + ")"
			);
		}
		int allocated = next;
		next++;
		if (!save()) {
			next--;
			throw new IllegalStateException("Could not persist drink CMD allocation");
		}
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
		if (Files.notExists(stateFile.toPath())) {
			save();
			return;
		}
		YamlConfiguration yaml = new YamlConfiguration();
		try {
			yaml.load(stateFile);
			if (!yaml.isInt("next")) {
				throw new IOException("missing or invalid next CMD counter");
			}
		} catch (IOException | InvalidConfigurationException e) {
			throw new IllegalStateException("Could not load drink CMD state: " + stateFile, e);
		}
		next = yaml.getInt("next");
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

	private boolean save() {
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.set("next", next);
		yaml.set("min", Cache.cmdMin);
		yaml.set("max", Cache.cmdMax);
		List<Integer> freedList = new ArrayList<>(freed);
		Collections.sort(freedList);
		yaml.set("freed", freedList);
		Path temp = null;
		try {
			Path target = stateFile.toPath();
			Files.createDirectories(target.toAbsolutePath().getParent());
			temp = Files.createTempFile(target.toAbsolutePath().getParent(), "cmd-state-", ".tmp");
			yaml.save(temp.toFile());
			try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
				channel.force(true);
			}
			try {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			try (var directory = FileChannel.open(target.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
				directory.force(true);
			} catch (IOException ignored) {
				// Directory flushing is not supported on every filesystem/platform.
			}
			return true;
		} catch (IOException e) {
			plugin.getLogger().warning("[cmd] could not save cmd-state.yml: " + e.getMessage());
			return false;
		} finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				} catch (IOException e) {
					plugin.getLogger().warning("[cmd] could not remove temporary CMD state: " + e.getMessage());
				}
			}
		}
	}
}
