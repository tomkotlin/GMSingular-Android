package com.goingmerry.gmsingular

import android.content.Context
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.appset.AppSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

interface DeviceIdentifierProvider {
    suspend fun appSetId(): String?

    suspend fun advertisingId(): String?
}

class PlayServicesDeviceIdentifierProvider(context: Context) : DeviceIdentifierProvider {
    private val appContext = context.applicationContext

    override suspend fun appSetId(): String? = try {
        AppSet.getClient(appContext).appSetIdInfo.await().id
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun advertisingId(): String? = try {
        val info = AdvertisingIdClient.getAdvertisingIdInfo(appContext)
        if (info.isLimitAdTrackingEnabled) null else info.id
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
