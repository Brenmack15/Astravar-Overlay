package com.yazsras.astravar.target

import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.view.Gravity

class TargetActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var taps=0
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.BOTTOM;setPadding(32,100,32,100)}
        layout.addView(TextView(this).apply {text="CONTROLLED SECOND APP\nNo D&D Beyond integration";textSize=24f})
        layout.addView(EditText(this).apply {hint="Keyboard focus test";contentDescription="Keyboard focus test"})
        layout.addView(Button(this).apply {text="Outside taps: 0";contentDescription="Outside tap counter";setOnClickListener {taps++;text="Outside taps: $taps"}})
        if(android.os.Build.VERSION.SDK_INT>=31) layout.addView(Button(this).apply {text="Toggle protected screen";var protected=false;setOnClickListener {protected=!protected;window.setHideOverlayWindows(protected);text=if(protected) "Protected screen ON" else "Toggle protected screen"}})
        setContentView(layout)
    }
}
