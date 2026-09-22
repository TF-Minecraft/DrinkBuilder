package net.tfminecraft.drinkbuilder.pack;

/**
 * ItemsAdder drinks namespace for this box's realm.
 * {@code main} → {@code tfmc_drinks}; otherwise {@code tfmc_drinks_<realm>}.
 */
public final class DrinksNamespace {

	public static final String MAIN = "tfmc_drinks";

	private DrinksNamespace() {}

	public static String current() {
		String realm = currentRealm();
		if (realm == null || realm.isBlank() || "main".equals(realm)) {
			return MAIN;
		}
		return MAIN + "_" + realm;
	}

	/** Resolve contents folder for the current drinks namespace. */
	public static java.io.File contentsRoot(java.io.File itemsAdderContentsParent) {
		return new java.io.File(itemsAdderContentsParent, current());
	}

	private static String currentRealm() {
		try {
			Class<?> cls = Class.forName("net.tfminecraft.tfmcweb.TFMCWeb");
			Object realm = cls.getMethod("getRealmId").invoke(null);
			if (realm == null) {
				return "main";
			}
			String text = String.valueOf(realm).trim().toLowerCase();
			return text.isEmpty() ? "main" : text;
		} catch (Throwable ignored) {
			return "main";
		}
	}
}
