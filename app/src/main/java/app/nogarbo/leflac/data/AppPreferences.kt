package app.nogarbo.leflac.data

import android.content.Context

/**
 * Visual language used for app-owned artwork shown by Android Auto.
 *
 * Android Auto owns the driving-safe screen chrome, so this setting controls
 * LE FLAC's browse and now-playing artwork rather than the host's theme.
 */
enum class AndroidAutoVisualScheme(val storedValue: String) {
    POCKET("pocket"),
    FIELD("field");

    companion object {
        fun fromStored(value: String?): AndroidAutoVisualScheme =
            entries.firstOrNull { it.storedValue == value } ?: POCKET

        fun read(context: Context): AndroidAutoVisualScheme = fromStored(
            context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
                .getString(KEY_ANDROID_AUTO_VISUAL_SCHEME, null)
        )

        fun write(context: Context, scheme: AndroidAutoVisualScheme) {
            context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ANDROID_AUTO_VISUAL_SCHEME, scheme.storedValue)
                .apply()
        }
    }
}

const val PREFERENCES_FILE = "flac_prefs"
const val KEY_ANDROID_AUTO_VISUAL_SCHEME = "android_auto_visual_scheme"
