/*
 * One button. Tapping it calls the "obfuscated" TicketService (`a.b#c`) and
 * shows the result. With the LSPosed module disabled you see `ticket:T-123`;
 * with it enabled you see the hooked result (e.g. `HOOKED(ticket:T-123)`),
 * which is the visible proof the Rosetta-resolved hook fired.
 */
package com.example.victim

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.victim.a.b

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
}
