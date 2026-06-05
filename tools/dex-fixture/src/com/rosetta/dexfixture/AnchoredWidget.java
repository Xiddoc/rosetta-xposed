package com.rosetta.dexfixture;

/**
 * Strategy: find-class-by-anchors.
 * References a unique stable string literal so R8 preserves it.
 */
public class AnchoredWidget {
    public static final String ANCHOR = "rosetta-dexfixture-anchor-AnchoredWidget";

    public String getAnchor() {
        // Live code that references the anchor string, so R8 keeps it.
        return ANCHOR;
    }
}
