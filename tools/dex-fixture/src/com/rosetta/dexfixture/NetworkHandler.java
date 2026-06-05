package com.rosetta.dexfixture;

/**
 * Extends BaseHandler — strategy: find-class-by-superclass.
 * Declares process(String) — strategy: find-method-by-signature (Ljava/lang/String;)Ljava/lang/String;
 */
public class NetworkHandler extends BaseHandler {
    @Override
    public void handle() {
        // network-specific handling
    }

    public String process(String s) {
        return "p:" + s;
    }
}
