package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vi.pl.videoplayerdemo.R

class SubscriptionActivity : AppCompatActivity() {
    private lateinit var weekly: View
    private lateinit var monthly: View
    private lateinit var yearly: View
    private lateinit var weeklyCheck: View
    private lateinit var monthlyCheck: View
    private lateinit var yearlyCheck: View
    private var selectedSku = SKU_YEARLY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.subscription_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        applyTitleGradient(findViewById(R.id.subscription_title))
        weekly = findViewById(R.id.plan_weekly)
        monthly = findViewById(R.id.plan_monthly)
        yearly = findViewById(R.id.plan_yearly)
        weeklyCheck = findViewById(R.id.plan_weekly_check)
        monthlyCheck = findViewById(R.id.plan_monthly_check)
        yearlyCheck = findViewById(R.id.plan_yearly_check)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        weekly.setOnClickListener { selectPlan(SKU_WEEKLY) }
        monthly.setOnClickListener { selectPlan(SKU_MONTHLY) }
        yearly.setOnClickListener { selectPlan(SKU_YEARLY) }
        findViewById<View>(R.id.subscription_upgrade).setOnClickListener { startGooglePlayPurchase(selectedSku) }
        selectPlan(SKU_YEARLY)
    }

    private fun selectPlan(sku: String) {
        selectedSku = sku
        weekly.setBackgroundResource(if (sku == SKU_WEEKLY) R.drawable.bg_sub_plan_selected else R.drawable.bg_sub_card)
        monthly.setBackgroundResource(if (sku == SKU_MONTHLY) R.drawable.bg_sub_plan_selected else R.drawable.bg_sub_card)
        yearly.setBackgroundResource(if (sku == SKU_YEARLY) R.drawable.bg_sub_plan_selected else R.drawable.bg_sub_card)
        weeklyCheck.visibility = if (sku == SKU_WEEKLY) View.VISIBLE else View.GONE
        monthlyCheck.visibility = if (sku == SKU_MONTHLY) View.VISIBLE else View.GONE
        yearlyCheck.visibility = if (sku == SKU_YEARLY) View.VISIBLE else View.GONE
    }

    private fun startGooglePlayPurchase(sku: String) {
        // TODO: Launch Google Play Billing purchase flow for sku [$sku].
        // BillingClient.launchBillingFlow(this, BillingFlowParams.ProductDetailsParams)
    }

    private fun applyTitleGradient(title: TextView) {
        title.setTextColor(ContextCompat.getColor(this, R.color.white))
        title.post {
            val width = title.paint.measureText(title.text.toString()).coerceAtLeast(1f)
            title.paint.shader = LinearGradient(
                0f,
                0f,
                width,
                0f,
                ContextCompat.getColor(this, R.color.vip_gradient_start),
                ContextCompat.getColor(this, R.color.vip_gradient_end),
                Shader.TileMode.CLAMP,
            )
            title.invalidate()
        }
    }

    companion object {
        const val SKU_WEEKLY = "vip_weekly"
        const val SKU_MONTHLY = "vip_monthly"
        const val SKU_YEARLY = "vip_yearly"

        fun createIntent(context: Context): Intent =
            Intent(context, SubscriptionActivity::class.java)
    }
}
