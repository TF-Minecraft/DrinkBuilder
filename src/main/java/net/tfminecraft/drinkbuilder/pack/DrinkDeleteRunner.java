package net.tfminecraft.drinkbuilder.pack;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.DrinkBuilder;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.DrinkGetResult;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.PendingDrink;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.RevokeResult;

/**
 * Staff drink delete: remove Brewery recipe, revoke on PS, free IA if last ref.
 */
public final class DrinkDeleteRunner {

	private DrinkDeleteRunner() {}

	public static String run(String submissionId) {
		DrinkBuilder plugin = JavaPlugin.getPlugin(DrinkBuilder.class);
		Logger log = plugin.getLogger();
		String id = submissionId == null ? "" : submissionId.trim();
		if (id.isEmpty()) {
			return "Drink id is required.";
		}

		DrinkGetResult fetched = ProvinceSystemClient.getDrink(id);
		if (!fetched.ok || fetched.drink == null) {
			return fetched.error != null ? fetched.error : "Could not load drink.";
		}
		PendingDrink drink = fetched.drink;
		String status = drink.status == null ? "" : drink.status.trim().toLowerCase();
		if (!status.equals("approved")
			&& !status.equals("pending_pack")
			&& !status.equals("applied")) {
			return "Drink " + id + " is not deletable (status=" + drink.status + ").";
		}

		try {
			RecipesYmlMerger.remove(plugin, id, log);
		} catch (Exception e) {
			log.warning("[drink-delete] recipe remove failed (continuing): " + e.getMessage());
		}

		boolean brewOk = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "brew reload");
		if (!brewOk) {
			log.warning("[drink-delete] failed to dispatch brew reload");
		}

		RevokeResult revoked = ProvinceSystemClient.revokeDrink(id);
		if (!revoked.ok) {
			return "Local recipe cleanup done but API revoke failed: "
				+ (revoked.error != null ? revoked.error : "unknown");
		}

		boolean iaChanged = false;
		if (revoked.textureFreed) {
			try {
				iaChanged = IaDrinksRemover.remove(plugin, revoked.iaItemId, log);
			} catch (Exception e) {
				log.warning("[drink-delete] IA remove failed: " + e.getMessage());
			}
			if (revoked.cmd != null) {
				plugin.getCmdAllocator().free(revoked.cmd);
			}
		}

		if (iaChanged) {
			DeferredDrinkIaReload reload = plugin.getDeferredIaReload();
			if (reload != null) {
				Bukkit.getScheduler().runTask(plugin, () -> reload.requestFlush(true));
			}
		}

		DeletableDrinkCache.invalidate();

		String label = drink.displayName != null && !drink.displayName.isBlank()
			? drink.displayName
			: id;
		String extra = revoked.textureFreed
			? " Texture + CMD freed."
			: (drink.textureId != null && !drink.textureId.isBlank()
				? " Shared texture kept."
				: "");
		return "Deleted drink " + id + " (" + label + ")." + extra;
	}
}
