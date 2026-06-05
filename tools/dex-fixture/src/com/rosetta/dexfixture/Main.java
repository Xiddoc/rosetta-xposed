package com.rosetta.dexfixture;

/**
 * Entry point. Kept un-obfuscated by proguard keep rule.
 * Touches every class so R8's shrinker keeps them and their string literals.
 */
public class Main {
    public static void main(String[] args) {
        // Touch AnchoredWidget and its anchor string.
        AnchoredWidget widget = new AnchoredWidget();
        System.out.println("anchor=" + widget.getAnchor());

        // Touch BaseHandler.
        BaseHandler base = new BaseHandler();
        base.handle();

        // Touch NetworkHandler (extends BaseHandler) and call process().
        NetworkHandler net = new NetworkHandler();
        net.handle();
        String result = net.process("test");
        System.out.println("process=" + result);

        // Touch RemoteStub and its DESCRIPTOR string.
        RemoteStub stub = new RemoteStub();
        System.out.println("descriptor=" + stub.getDescriptor());
        System.out.println("descriptor-direct=" + RemoteStub.DESCRIPTOR);
    }
}
