package com.goingmerry.gmsingular

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateAppUserRequest(
    @SerialName("custom_user_id") val customUserId: String? = null,
    @SerialName("adapty_id") val adaptyId: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("singular_device_id") val singularDeviceId: String,
    @SerialName("advertising_id") val advertisingId: String? = null,
    @SerialName("app_store_country") val appStoreCountry: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("android_version") val androidVersion: String? = null,
)

@Serializable
data class AppUserResponse(
    @SerialName("application_id") val applicationId: String,
    @SerialName("custom_user_id") val customUserId: String? = null,
    @SerialName("adapty_id") val adaptyId: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("singular_device_id") val singularDeviceId: String,
    @SerialName("advertising_id") val advertisingId: String? = null,
    @SerialName("app_store_country") val appStoreCountry: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("android_version") val androidVersion: String? = null,
)
