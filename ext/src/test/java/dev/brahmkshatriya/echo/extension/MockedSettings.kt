package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings

class MockedSettings : Settings {
    private val booleans = mutableMapOf<String, Boolean>()
    private val ints = mutableMapOf<String, Int>()
    private val strings = mutableMapOf<String, String>()
    private val stringSets = mutableMapOf<String, Set<String>>()

    override fun getBoolean(key: String): Boolean? = booleans[key]

    override fun getInt(key: String): Int? = ints[key]

    override fun getString(key: String): String? = strings[key]

    override fun getStringSet(key: String): Set<String>? = stringSets[key]

    override fun putBoolean(key: String, value: Boolean?) {
        if (value != null) booleans[key] = value else booleans.remove(key)
    }

    override fun putInt(key: String, value: Int?) {
        if (value != null) ints[key] = value else ints.remove(key)
    }

    override fun putString(key: String, value: String?) {
        if (value != null) strings[key] = value else strings.remove(key)
    }

    override fun putStringSet(key: String, value: Set<String>?) {
        if (value != null) stringSets[key] = value else stringSets.remove(key)
    }

}