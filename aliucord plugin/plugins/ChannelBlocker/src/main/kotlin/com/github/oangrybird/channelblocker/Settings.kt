package com.github.oangrybird.channelblocker

import android.os.Bundle
import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.views.Button

class Settings : SettingsPage() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setActionBarTitle("Channel Blocker")

        Button(view.context).apply {
            text = "Block current channel"
            setOnClickListener {
                // This is a placeholder for future functionality
                Utils.showToast(view.context, "Functionality coming soon!")
            }
            addView(this)
        }
    }
}
