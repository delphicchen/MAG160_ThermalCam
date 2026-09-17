package com.magnity.viewer.ui

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Compose state that survives app restarts: starts from the value in [prefs] and writes
 * every change back. `apply()` commits to disk asynchronously, so setting it from the
 * processing thread is fine.
 *
 * [skipWrite] keeps a value in memory only while it returns true (the auto display range
 * changes every frame and must not hit disk 15×/s). [onSet] runs after each real change.
 */
class Persisted<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    default: T,
    read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
    private val skipWrite: () -> Boolean = { false },
    private val onSet: (T) -> Unit = {},
) : ReadWriteProperty<Any?, T> {

    private val state = mutableStateOf(
        runCatching { prefs.read(key, default) }.getOrDefault(default))

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (state.value == value) return
        state.value = value
        if (!skipWrite()) prefs.edit().apply { write(key, value) }.apply()
        onSet(value)
    }
}
