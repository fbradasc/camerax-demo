package com.fbradasc.exposurefinder.fragments

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceFragmentCompat
import com.fbradasc.exposurefinder.R
import com.fbradasc.exposurefinder.fragments.CameraFragment.Companion.invalidateExposure


class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Load the preferences from an XML resource
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<ListPreference>("film_speed")
            ?.setOnPreferenceChangeListener { _, _ ->
                invalidateExposure()
                true
            }

        findPreference<ListPreference>("filter")
            ?.setOnPreferenceChangeListener { _, _ ->
                invalidateExposure()
                true
            }

        val tvPref = findPreference<MultiSelectListPreference>("shutter_allowed_tv")
        // Set initial summary
        tvPref?.summary = getSortedDescendingList(tvPref.values)

        tvPref?.setOnPreferenceChangeListener { preference, newValue ->
            invalidateExposure()
            preference.summary = getSortedDescendingList(newValue as Set<String>)
            true
        }

        val avPref = findPreference<MultiSelectListPreference>("shutter_allowed_av")
        // Set initial summary
        avPref?.summary = getSortedList(avPref.values)

        avPref?.setOnPreferenceChangeListener { preference, newValue ->
            invalidateExposure()
            preference.summary = getSortedList(newValue as Set<String>)
            true
        }
    }

    fun getSortedList(s: Set<String>): String {
        val a: Array<String> = s.toTypedArray<String>()
        var l: MutableList<Double> = mutableListOf<Double>()

        for (v in a) {
            l.add(v.toDouble())
        }

        l.sort()

        return l.sorted().joinToString(", ").replace(".0", "")
    }

    fun getSortedDescendingList(s: Set<String>): String {
        val a: Array<String> = s.toTypedArray<String>()
        var l: MutableList<Double> = mutableListOf<Double>()

        for (v in a) {
            l.add(v.toDouble())
        }

        l.sort()

        return l.sortedDescending().joinToString(", ").replace(".0", "")
    }
}
