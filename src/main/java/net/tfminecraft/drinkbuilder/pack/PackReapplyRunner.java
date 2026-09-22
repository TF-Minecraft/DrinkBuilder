package net.tfminecraft.drinkbuilder.pack;

import java.util.Collections;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.DrinkBuilder;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.DrinkGetResult;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.PendingDrink;

/**
 * Re-write Brewery (and IA when needed) for one drink without changing PS status.
 */
public final class PackReapplyRunner {

	private PackReapplyRunner() {}

	public static final class ReapplyResult {
		public final boolean ok;
		public final String message;

		private ReapplyResult(boolean ok, String message) {
			this.ok = ok;
			this.message = message;
		}

		public static ReapplyResult success(String message) {
			return new ReapplyResult(true, message);
		}

		public static ReapplyResult fail(String message) {
			return new ReapplyResult(false, message);
		}
	}

	public static void run(String submissionId, Consumer<ReapplyResult> onDone) {
		DrinkBuilder plugin = JavaPlugin.getPlugin(DrinkBuilder.class);
		String sid = submissionId == null ? "" : submissionId.trim();
		if (sid.isEmpty()) {
			finish(plugin, onDone, ReapplyResult.fail("submission id required"));
			return;
		}
		if (PackPullRunner.isRunning()) {
			finish(plugin, onDone, ReapplyResult.fail("pack pull already running"));
			return;
		}

		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			DrinkGetResult fetched = ProvinceSystemClient.getDrink(sid);
			if (!fetched.ok || fetched.drink == null) {
				String err = fetched.error == null ? "drink not found" : fetched.error;
				finish(plugin, onDone, ReapplyResult.fail(err));
				return;
			}
			PendingDrink drink = fetched.drink;
			String status = drink.status == null ? "" : drink.status.trim().toLowerCase(Locale.ROOT);
			if (!status.equals("approved")
				&& !status.equals("pending_pack")
				&& !status.equals("applied")) {
				finish(
					plugin,
					onDone,
					ReapplyResult.fail("drink status is " + drink.status + " (need approved/applied)")
				);
				return;
			}

			Bukkit.getScheduler().runTask(plugin, () -> {
				Logger log = plugin.getLogger();
				try {
					boolean wroteIa = PackPullRunner.applyDrink(
						plugin,
						drink,
						plugin.getCmdAllocator(),
						log
					);
					boolean brewOk = Bukkit.dispatchCommand(
						Bukkit.getConsoleSender(),
						"brew reload"
					);
					if (!brewOk) {
						log.warning("[pack] reapply: brew reload dispatch failed for " + sid);
					}
					if (wroteIa) {
						DeferredDrinkIaReload reload = plugin.getDeferredIaReload();
						if (reload != null) {
							reload.queue().enqueue(Collections.singletonList(sid));
							reload.requestFlush(true);
						}
					}
					finish(
						plugin,
						onDone,
						ReapplyResult.success(
							"Reapplied " + sid
								+ (wroteIa ? " (IA refresh queued)" : " (brewery only)")
						)
					);
				} catch (Exception e) {
					log.warning("[pack] reapply failed for " + sid + ": " + e.getMessage());
					finish(plugin, onDone, ReapplyResult.fail(e.getMessage()));
				}
			});
		});
	}

	private static void finish(
		DrinkBuilder plugin,
		Consumer<ReapplyResult> onDone,
		ReapplyResult result
	) {
		if (onDone == null) {
			return;
		}
		Bukkit.getScheduler().runTask(plugin, () -> onDone.accept(result));
	}
}
