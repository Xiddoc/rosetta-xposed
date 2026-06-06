package com.example.victim;

/**
 * The victim's real (unobfuscated) source. R8 renames this to
 * {@code com.example.victim.a.b} and {@code formatTicket} to {@code c} per
 * victim/seed.txt, so the committed map (maps/100.json) resolves the human
 * names to exactly what R8 emits.
 */
public class TicketService {
    public String formatTicket(String input) {
        return "ticket:" + input;
    }
}
