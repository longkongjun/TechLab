package dev.techlab.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.techlab.android.gecko.GeckoDemoActivity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (resources.displayMetrics.density * 16).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@MainActivity).apply {
                text = "TechLab Android"
                textSize = 24f
            })
            // The catalog links to a demo's entry point. Its screen and runtime stay in the demo module.
            addView(Button(this@MainActivity).apply {
                text = "GeckoView 题目渲染"
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, GeckoDemoActivity::class.java))
                }
            })
        })
    }
}
