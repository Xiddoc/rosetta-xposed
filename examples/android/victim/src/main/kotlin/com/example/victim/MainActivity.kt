/*
 * One button. Tapping it calls the "obfuscated" TicketService (`a.b#c`) and
 * shows the result. With the LSPosed module disabled you see `ticket:T-123`;
 * with it enabled you see the hooked result (e.g. `HOOKED(ticket:T-123)`),
 * which is the visible proof the Rosetta-resolved hook fired.
 *
 * onCreate exercises TWO paths so headless CI can assert both:
 *   - STATIC  — `a.b#c` (TicketService) IS in the bundled map; logged under
 *     `RosettaVictim`, hooked → `HOOKED(ticket:T-123)`.
 *   - DYNAMIC — `c.d#e` (AuditService, rosetta-xposed#22) is DELIBERATELY
 *     absent from the bundled map, so the module resolves it by live DexKit
 *     discovery; logged under `RosettaVictimDyn`, hooked → `DHOOKED(...)`.
 *
 * The e2e job greps logcat for each marker; no UI automation needed.
 */
package com.example.victim

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.victim.a.b
import com.example.victim.c.d

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STATIC path: `a.b#c` IS in the bundled map. With the module active
        // this logs `HOOKED(ticket:T-123)`, without it `ticket:T-123`.
        Log.i(TAG, b().c("T-123"))

        // DYNAMIC path (#22): `c.d#e` (AuditService) is absent from the map, so
        // the module resolves it by live DexKit discovery. With discovery wired
        // this logs `DHOOKED(...)`, otherwise the raw `audit[...]:T-123`.
        Log.i(DYN_TAG, d().e("T-123"))

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
        const val DYN_TAG = "RosettaVictimDyn"
    }
}
