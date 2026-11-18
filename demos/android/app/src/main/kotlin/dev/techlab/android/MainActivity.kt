package dev.techlab.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.techlab.android.databinding.ActivityMainBinding
import dev.techlab.android.gecko.EXTRA_DEFER_RENDER_INPUT
import dev.techlab.android.gecko.GeckoDemoActivity
import dev.techlab.android.gecko.RenderWarmup
import dev.techlab.android.handwriting.HandwritingActivity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.openHandwriting.setOnClickListener {
            startActivity(Intent(this, HandwritingActivity::class.java))
        }
        binding.openRenderer.setOnClickListener {
            startActivity(Intent(this, GeckoDemoActivity::class.java)
                .putExtra(EXTRA_DEFER_RENDER_INPUT, intent.getBooleanExtra(EXTRA_DEFER_RENDER_INPUT, false)))
        }
        if (intent.getBooleanExtra(RenderWarmup.EXTRA_ENABLED, true)) {
            binding.root.post { RenderWarmup.start(applicationContext) }
        }
    }
}
