package com.miaa.cas.financialreporting.export;

import java.util.ArrayList;
import java.util.List;

/**
 * The organisation's letterhead at the top of every exported report: its name, then the address and TIN when they are
 * recorded (Administration -> Organization). Nothing is printed for a detail that is not recorded.
 */
public record Letterhead(String name, String address, String tin) {

    public static final String DEFAULT_NAME = "Avtech Information Technology Solutions, Inc.";
    public static final Letterhead DEFAULT = new Letterhead(DEFAULT_NAME, null, null);

    public Letterhead {
        name = name == null || name.isBlank() ? DEFAULT_NAME : name.trim();
        address = address == null || address.isBlank() ? null : address.trim();
        tin = tin == null || tin.isBlank() ? null : tin.trim();
    }

    /** The lines under the name: the address, then the TIN. */
    public List<String> details() {
        List<String> out = new ArrayList<>();
        if (address != null) {
            out.add(address);
        }
        if (tin != null) {
            out.add("TIN " + tin);
        }
        return out;
    }
}
