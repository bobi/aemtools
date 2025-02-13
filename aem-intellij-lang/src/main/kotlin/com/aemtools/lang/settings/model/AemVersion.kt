package com.aemtools.lang.settings.model

/**
 * Represents supported AEM minor versions.
 *
 * @author Kostiantyn Diachenko
 */
enum class AemVersion(val version: String) {
  V_6_1("6.1"),
  V_6_2("6.2"),
  V_6_3("6.3"),
  V_6_4("6.4"),
  V_6_5("6.5");

  companion object {
    fun versions() = AemVersion.values().map { it.version }

    fun fromVersion(version: String): AemVersion? =
        AemVersion.values().firstOrNull { version.startsWith(it.version) }

    fun latest() = AemVersion.values().last()
  }
}
