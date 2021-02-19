package ussr.razar.android.dount.launcher

import android.content.SharedPreferences
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceManager

//TODO()!!!!
class ScreenPrefActivity : PreferenceActivity(), Preference.OnPreferenceChangeListener {
    private var mDefaultPrefs: SharedPreferences? = null
    private var mScreenNumberPref: ListPreference? = null
    private var mDefaultScreenPref: ListPreference? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.screens_prefs)
        mDefaultPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        mScreenNumberPref = findPreference(getString(R.string.key_screen_number)) as ListPreference?
        mScreenNumberPref!!.onPreferenceChangeListener = this
        val total: String = mDefaultPrefs!!.getString(getString(R.string.key_screen_number), "4")!!
        mScreenNumberPref!!.summary = PREFIX + total
        val screens: Array<CharSequence?> = newSeq(total)
        mDefaultScreenPref = findPreference(getString(R.string.key_default_screen)) as ListPreference?
        mDefaultScreenPref!!.onPreferenceChangeListener = this
        mDefaultScreenPref!!.entries = screens
        mDefaultScreenPref!!.entryValues = screens
        mDefaultScreenPref!!.summary = PREFIX + mDefaultScreenPref!!.value
    }

    /**
     *
     */
    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        preference.summary = PREFIX + newValue as CharSequence
        if (preference === mScreenNumberPref) {
            try {
                val v: String = newValue as String
                val total: Int = v.toInt()
                val defaults: Int = mDefaultScreenPref!!.value.toInt()
                if (total < defaults) {
                    mDefaultScreenPref!!.value = v
                    mDefaultScreenPref!!.summary = PREFIX + v
                }
                val screens: Array<CharSequence?> = newSeq(v)
                mDefaultScreenPref!!.entries = screens
                mDefaultScreenPref!!.entryValues = screens
            } catch (e: Exception) {
            }
        }
        return true
    }

    /**
     *
     * @param v
     * @return
     */
    private fun newSeq(v: String): Array<CharSequence?> {
        val total: Int = v.toInt()
        val screens: Array<CharSequence?> = arrayOfNulls(total)
        for (i in 1..total) {
            screens[i - 1] = i.toString() + ""
        }
        return screens
    }

    companion object {
        const val PREFIX: String = "Current: "
    }
}