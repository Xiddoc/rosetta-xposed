package com.example.victim;

/**
 * The victim's real (unobfuscated) source. R8 renames this to
 * {@code com.example.victim.a.b} and {@code formatTicket} to {@code c} per
 * victim/seed-v100.txt, so the committed map (maps/100.json) resolves the
 * human names to exactly what R8 emits. For v101, seed-v101.txt renames it
 * to {@code com.example.victim.x.y} and {@code q} (maps/101.json).
 */
public class TicketService {
    public String formatTicket(String input) {
        return "ticket:" + input;
    }
}
