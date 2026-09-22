package net.tfminecraft.drinkbuilder.objects;

/**
 * One rank row from permission-groups.yml (drink creator entitlements).
 */
public final class PermissionGroupDefinition {

	private final String id;
	private final String permission;
	private final int tier;
	private final int nameColourStops;
	private final boolean allowDrinkTexture;
	private final boolean hasNameColourStops;
	private final boolean hasAllowDrinkTexture;

	public PermissionGroupDefinition(
		String id,
		String permission,
		int tier,
		int nameColourStops,
		boolean allowDrinkTexture,
		boolean hasNameColourStops,
		boolean hasAllowDrinkTexture
	) {
		this.id = id == null ? "" : id;
		this.permission = permission == null ? "" : permission;
		this.tier = tier;
		this.nameColourStops = nameColourStops;
		this.allowDrinkTexture = allowDrinkTexture;
		this.hasNameColourStops = hasNameColourStops;
		this.hasAllowDrinkTexture = hasAllowDrinkTexture;
	}

	public String getId() {
		return id;
	}

	public String getPermission() {
		return permission;
	}

	public int getTier() {
		return tier;
	}

	public int getNameColourStops() {
		return nameColourStops;
	}

	public boolean isAllowDrinkTexture() {
		return allowDrinkTexture;
	}

	public boolean hasNameColourStops() {
		return hasNameColourStops;
	}

	public boolean hasAllowDrinkTexture() {
		return hasAllowDrinkTexture;
	}
}
