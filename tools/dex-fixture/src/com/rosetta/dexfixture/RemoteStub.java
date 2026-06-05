package com.rosetta.dexfixture;

/**
 * Strategy: find-class-by-aidl-descriptor.
 * The DESCRIPTOR string literal survives R8 because it is read by Main.
 */
public class RemoteStub {
    public static final String DESCRIPTOR = "com.rosetta.dexfixture.IRemote";

    public String getDescriptor() {
        // Live code reference so R8 keeps the field and string.
        return DESCRIPTOR;
    }
}
