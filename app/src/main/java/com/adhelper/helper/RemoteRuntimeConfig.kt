package com.adhelper.helper

import java.util.UUID

enum class TransportMode(val wireValue: String) {
    LOCAL("local"),
    REMOTE("remote"),
    ;

    companion object {
        fun fromWireValue(value: String?): TransportMode = entries.firstOrNull {
            it.wireValue.equals(value, ignoreCase = true)
        } ?: LOCAL
    }
}

data class RemoteRuntimeConfig(
    val transportMode: TransportMode = TransportMode.LOCAL,
    val serverUrl: String? = null,
    val deviceId: String = UUID.randomUUID().toString(),
    val sharedToken: String? = null,
) {
    fun hasRemoteCredentials(): Boolean = !serverUrl.isNullOrBlank() && !sharedToken.isNullOrBlank()
}
