package com.fernando.ahora.domain.model

/** Ajustes › Apariencia. SYSTEM is the default (PRODUCT_SPEC §5.7). */
enum class ThemeMode(val code: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromCode(code: String?): ThemeMode = entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}
