package com.dre.brewery.integration;

/** Test-only boundary fixture for BreweryX's reflective hook API. */
public class Hook {
    public static final Hook MMOITEMS = new Hook();
    public static final Hook ITEMSADDER = new Hook();
    public boolean enabled;
    public boolean checked;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setChecked(boolean value) { checked = value; }
}
