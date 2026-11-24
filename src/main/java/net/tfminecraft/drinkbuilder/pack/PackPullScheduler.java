package net.tfminecraft.drinkbuilder.pack;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.drinkbuilder.Cache;

/**
 * Passive pack apply: periodic pull from ProvinceSystem + optional daily force pull.
 */
public final class PackPullScheduler {

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
	private static final long MIN_POLL_SECONDS = 30;
	private static final long MAX_POLL_SECONDS = 600;
	/** The forced pull may start up to this long after force-reload-time. */
	private static final int FORCE_WINDOW_SECONDS = 30 * 60;

	private final JavaPlugin plugin;
	private BukkitTask pollTask;
	private BukkitTask dailyTask;
	private LocalDate lastForcedDate;

	public PackPullScheduler(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	public void start() {
		stop();
		Logger log = plugin.getLogger();

		long pollSec = Cache.packPollIntervalSeconds;
		if (pollSec > 0) {
			long clamped = Math.max(MIN_POLL_SECONDS, Math.min(MAX_POLL_SECONDS, pollSec));
			long ticks = clamped * 20L;
			// Initial delay 30s after enable, then every pollSec.
			pollTask = Bukkit.getScheduler().runTaskTimer(
				plugin,
				() -> PackPullRunner.run(false, null),
				20L * 30,
				ticks
			);
			log.info("[pack] passive poll every " + clamped + "s");
		} else {
			log.info("[pack] passive poll disabled (pack-apply.poll-interval-seconds=0)");
		}

		// Offset from the poll's first run so a poll interval that divides 60 s cannot land on every
		// daily check and keep the forced pull from starting ("pull already running").
		dailyTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickDaily, 20L * 30 + 10, 20L * 60);
		String configured = Cache.forceReloadTime;
		if (configured == null || configured.isBlank()) {
			log.info("[pack] force-reload-time disabled");
		} else {
			log.info("[pack] force-reload-time scheduled at " + configured.trim()
				+ " (server local)");
		}
	}

	public void stop() {
		if (pollTask != null) {
			pollTask.cancel();
			pollTask = null;
		}
		if (dailyTask != null) {
			dailyTask.cancel();
			dailyTask = null;
		}
	}

	private void tickDaily() {
		String raw = Cache.forceReloadTime;
		if (raw == null || raw.isBlank()) {
			return;
		}
		LocalTime target;
		try {
			target = LocalTime.parse(raw.trim(), HH_MM);
		} catch (DateTimeParseException e) {
			plugin.getLogger().warning("[pack] invalid force-reload-time '" + raw
				+ "' (expected HH:mm)");
			return;
		}

		LocalDate today = LocalDate.now();
		LocalTime now = LocalTime.now();
		// Due inside [target, target + 30 min). A window can start before midnight.
		int intoWindow = Math.floorMod(now.toSecondOfDay() - target.toSecondOfDay(), 24 * 60 * 60);
		if (intoWindow >= FORCE_WINDOW_SECONDS) {
			return;
		}
		LocalDate windowDate = now.isBefore(target) ? today.minusDays(1) : today;
		if (windowDate.equals(lastForcedDate)) {
			return;
		}

		plugin.getLogger().info("[pack] force-reload-time due — running pull (force=true)");
		// A pull already running (e.g. the passive poll on the same tick) skips this one;
		// the next daily tick inside the window tries again.
		if (PackPullRunner.run(true, null)) {
			lastForcedDate = windowDate;
		}
	}
}
