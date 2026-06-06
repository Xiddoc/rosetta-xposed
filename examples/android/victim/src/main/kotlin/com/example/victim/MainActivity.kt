/*
 * One button. Tapping it calls the "obfuscated" TicketService (`a.b#c`) and
 * shows the result. With the LSPosed module disabled you see `ticket:T-123`;
 * with it enabled you see the hooked result (e.g. `HOOKED(ticket:T-123)`),
 * which is the visible proof the Rosetta-resolved hook fired.
 *
 * For headless CI (the emulator + LSPatch e2e workflow), onCreate also calls
 * formatTicket once and Log.i's the result under TAG. The e2e job greps logcat
 * for `HOOKED(ticket:` to assert the Rosetta-resolved hook fired — no UI
 * automation needed.
 */
package com.example.victim

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.victim.a.b

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Headless hook signal for CI: with the module active this logs
        // `HOOKED(ticket:T-123)`, without it `ticket:T-123`.
        Log.i(TAG, b().c("T-123"))

        val output =
            TextView(this).apply {
                text = "tap to call TicketService.formatTicket"
                textSize = 18f
            }
        val go =
            Button(this).apply {
                text = "Call formatTicket(\"T-123\")"
                setOnClickListener {
                    // Calls the obfuscated method the module hooks by real name.
                    output.text = b().c("T-123")
                }
            }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
                addView(go)
                addView(output)
            },
        )
    }

    private companion object {
        const val TAG = "RosettaVictim"
    }
}
