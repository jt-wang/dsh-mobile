package dev.dshmobile.app.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Nonsecret appearance choice, independent of pairing, drafts and encrypted caches. */
internal enum class LanguagePreference(val tag: String) {
    SYSTEM(""), RUSSIAN("ru"), ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): LanguagePreference? = when (tag?.substringBefore('-')?.lowercase(Locale.ROOT)) {
            null, "" -> SYSTEM
            "ru" -> RUSSIAN
            "en" -> ENGLISH
            else -> null // A foreign pin is not the system default: no picker option is selected.
        }
    }
}

internal const val LANGUAGE_PREFERENCES = "mobile_language"
internal const val LANGUAGE_KEY = "language"

internal fun languagePreference(context: Context): LanguagePreference? =
    if (Build.VERSION.SDK_INT >= 33) {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        LanguagePreference.fromTag(if (locales.isEmpty) null else locales[0].toLanguageTag())
    } else LanguagePreference.fromTag(context.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE_KEY, null))

/** Resolve afresh for background work too: a running Service must not retain its old language. */
internal fun appLanguageContext(context: Context): Context {
    val locales = if (Build.VERSION.SDK_INT >= 33) {
        val manager = context.getSystemService(LocaleManager::class.java)
        val app = manager.applicationLocales
        val system = manager.systemLocales
        val merged = ((0 until app.size()).map { app[it] } + (0 until system.size()).map { system[it] }).distinct()
        LocaleList(*merged.toTypedArray())
    } else {
        val tag = context.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE_KEY, null)
        if (tag.isNullOrEmpty()) Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(tag)
    }
    return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(locales) })
}

internal fun setAppLanguage(activity: Activity, choice: LanguagePreference) {
    if (Build.VERSION.SDK_INT >= 33) {
        // Compare the actual list, not its lossy picker mapping (foreign and regional pins included).
        val manager = activity.getSystemService(LocaleManager::class.java)
        val requested = LocaleList.forLanguageTags(choice.tag)
        if (manager.applicationLocales == requested) return
        manager.applicationLocales = requested // Manifest locale receiver refreshes visible notifications.
    } else {
        val preferences = activity.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.getString(LANGUAGE_KEY, null)
        if (choice != LanguagePreference.SYSTEM && previous == choice.tag) return
        if (choice == LanguagePreference.SYSTEM && !preferences.contains(LANGUAGE_KEY)) return
        // System means absence of a pin, not a persisted copy of the current system language.
        val editor = preferences.edit()
        if (choice == LanguagePreference.SYSTEM) editor.remove(LANGUAGE_KEY) else editor.putString(LANGUAGE_KEY, choice.tag)
        if (editor.commit()) {
            dev.dshmobile.app.data.refreshNotificationLanguage(activity)
            activity.recreate()
        }
    }
}
