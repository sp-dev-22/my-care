package com.spdev22.mycare.setup

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.engine.UsageTracker
import com.spdev22.mycare.receiver.CareDeviceAdminReceiver
import com.spdev22.mycare.ui.UiKit

/**
 * Setup Wizard (D11) — saari permissions ek-ek karke,
 * har ek ki WAJAH ke saath. JARVIS sp3 ke wizard wala pattern.
 *
 * Phase 1: screen aur saare raaste (intents) taiyar hain.
 * 0.2.4: Usage Access ki LIVE sthiti bhi dikhti hai — mili hai
 * ya nahi — kyunki bina iske Cache Safai jaise checks chup-chaap
 * band rehte the aur user ko wajah pata hi nahi chalti thi.
 */
class SetupWizardActivity : Activity() {

    private data class PermItem(
        val title: String,
        val why: String,
        val statusText: (() -> String)? = null,
        val open: (Activity) -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // Settings se permission dekar lautne par sthiti taaza dikhe
        buildUi()
    }

    private fun buildUi() {

        val root = UiKit.root(this)
        setContentView(UiKit.scroll(this, root))

        root.addView(UiKit.title(this, "Setup Wizard"))
        root.addView(
            UiKit.subtitle(
                this,
                "Boss, My Care ko aapka khayal rakhne ke liye kuch permissions " +
                    "chahiye hongi. Ek-ek karke dete jaiye — har ek ki wajah saath me " +
                    "likhi hai. Bina inke aadhe workflows sirf kagaz par rahenge."
            )
        )

        for (item in items()) {
            val card = UiKit.card(this)
            card.addView(UiKit.body(this, item.title, 16f).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            card.addView(UiKit.small(this, item.why))
            item.statusText?.let { status ->
                card.addView(UiKit.body(this, status(), 14f).apply {
                    setPadding(0, UiKit.dp(this@SetupWizardActivity, 4), 0, 0)
                })
            }
            card.addView(UiKit.gap(this, 6))
            val btn = UiKit.primaryButton(this, "Setup karo")
            btn.setOnClickListener {
                try {
                    item.open(this@SetupWizardActivity)
                } catch (e: Exception) {
                    Toast.makeText(
                        this@SetupWizardActivity,
                        "Yeh setting is phone par nahi khuli — Settings se manually de dijiye",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            card.addView(btn)
            root.addView(card)
        }

        root.addView(UiKit.small(this, "Note: Koi bhi permission zabardasti nahi hai. Jo nahi denge, " +
            "us se jude workflows band rahenge — baaki app chalta rahega."))
    }

    private fun items(): List<PermItem> = listOf(
        PermItem(
            "1. Usage Access",
            "Kaun sa app kitni der chala — App Groups, boredom detection aur " +
                "30-minute wale triggers ki jaan yahi permission hai.",
            statusText = {
                if (UsageTracker.hasUsageAccess(this@SetupWizardActivity)) {
                    "Sthiti: MILI HAI ✓ — Cache Safai, Unused Apps aur usage " +
                        "wale saare checks chal sakte hain."
                } else {
                    "Sthiti: NAHI MILI ✗ — bina iske Cache Safai, Unused Apps " +
                        "aur usage wale triggers BAND rahenge. Neeche wala " +
                        "button dabakar My Care ko Usage Access de dein."
                }
            }
        ) { a ->
            a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
        PermItem(
            "2. Notifications",
            "Saare care suggestions notification se hi aayenge — yeh band to " +
                "My Care goonga ho jayega."
        ) { a ->
            if (Build.VERSION.SDK_INT >= 33) {
                a.requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            } else {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                i.putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
                a.startActivity(i)
            }
        },
        PermItem(
            "3. Popup (Doosri apps ke upar dikhna)",
            "3 se zyada buttons wale flows Popup me khulenge — uske liye app ko " +
                "screen ke upar dikhne ki ijaazat chahiye."
        ) { a ->
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + a.packageName)
            )
            a.startActivity(i)
        },
        PermItem(
            "4. Device Admin",
            "Lock Phone button aur Ghuspaithiya Alert (galat password attempts) " +
                "ke liye. Note: aapka PIN hum kabhi padh hi nahi sakte — Android " +
                "woh kisi ko deta hi nahi."
        ) { a ->
            val admin = ComponentName(a, CareDeviceAdminReceiver::class.java)
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            i.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lock Phone aur galat-password alert ke liye My Care ko device admin chahiye."
            )
            a.startActivity(i)
        },
        PermItem(
            "5. Accessibility",
            "Close App button ke liye — is se My Care seedha Home screen " +
                "par bhej pata hai. Settings me 'Installed services' ke " +
                "andar 'My Care' naam se service milegi — use ON karna hoga. " +
                "Note: yeh service screen ka content nahi padhti."
        ) { a ->
            a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        PermItem(
            "6. Contacts",
            "Naya contact save ho to 'WhatsApp par kholo' wala suggestion dene " +
                "ke liye — sirf naye contact ki khabar, pura data nahi uthate."
        ) { a ->
            a.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 102)
        },
        PermItem(
            "7. Photos / Media",
            "Screenshot Safai flow ke liye — 0.2.5 se My Care Screenshots " +
                "folder par nazar rakhkar naye screenshot ki khabar deta hai " +
                "aur, aapke kehne par, use delete bhi karta hai."
        ) { a ->
            val perm = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            a.requestPermissions(arrayOf(perm), 103)
        },
        PermItem(
            "8. Battery bandish hatayein",
            "Infinix ka system battery bachane ke chakkar me app ko sula deta " +
                "hai — to notifications ruk jayengi. My Care ko is bandish se " +
                "bahar rakhna zaroori hai."
        ) { a ->
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + a.packageName)
            a.startActivity(i)
        }
    )
}
