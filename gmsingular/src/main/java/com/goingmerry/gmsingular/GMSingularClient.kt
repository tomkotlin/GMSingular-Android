package com.goingmerry.gmsingular

import android.content.Context
import android.content.Intent
import android.os.Build
import com.singular.sdk.Singular
import com.singular.sdk.SingularConfig
import com.singular.sdk.SingularDeviceAttributionHandler
import com.singular.sdk.SingularLinkHandler
import com.singular.sdk.SingularLinkParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

class GMSingularClient internal constructor(
    private val appContext: Context?,
    private val baseUrl: String,
    private val requestPath: String,
    private val store: KeyValueStore,
    private val network: NetworkClient,
    private val singularUserSetter: SingularUserSetting,
    private val identifiers: DeviceIdentifierProvider,
    private val ioDispatcher: CoroutineDispatcher,
    private val osVersionProvider: () -> String?,
    private val customUserIdGenerator: () -> String,
) {

    constructor(
        context: Context,
        baseUrl: String = DEFAULT_BASE_URL,
        requestPath: String = DEFAULT_REQUEST_PATH,
        storeName: String = DEFAULT_STORE_NAME,
        network: NetworkClient = HttpUrlConnectionNetworkClient(),
        singularUserSetter: SingularUserSetting = LiveSingularUserSetter(),
        store: KeyValueStore? = null,
        identifiers: DeviceIdentifierProvider? = null,
    ) : this(
        appContext = context.applicationContext,
        baseUrl = baseUrl,
        requestPath = requestPath,
        store = store ?: SharedPreferencesKeyValueStore(context.applicationContext, storeName),
        network = network,
        singularUserSetter = singularUserSetter,
        identifiers = identifiers ?: PlayServicesDeviceIdentifierProvider(context.applicationContext),
        ioDispatcher = Dispatchers.IO,
        osVersionProvider = { Build.VERSION.RELEASE },
        customUserIdGenerator = { UUID.randomUUID().toString() },
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val initializeMutex = Mutex()

    @Volatile
    private var cachedAppUser: AppUserResponse? = null

    @Volatile
    private var cachedCustomUserId: String? = null

    private val customUserIdLock = Any()

    @Volatile
    private var sessionSettings: SessionSettings? = null

    private val requestUrl: String
        get() = "${baseUrl.trimEnd('/')}/${requestPath.trimStart('/')}"

    @JvmOverloads
    fun startSingularSession(
        singularApiKey: String,
        singularSecret: String,
        intent: Intent?,
        customerUserId: String?,
        enableLogging: Boolean = false,
        brandedDomains: List<String>? = null,
        singularLinksHandler: ((SingularLinkParams) -> Unit)? = null,
        deviceAttributionCallback: ((Map<String, Any?>) -> Unit)? = null,
    ): Boolean {
        val context = appContext ?: return false
        val settings = SessionSettings(
            apiKey = singularApiKey.trim(),
            secret = singularSecret.trim(),
            customerUserId = customerUserId,
            enableLogging = enableLogging,
            brandedDomains = brandedDomains,
            singularLinksHandler = singularLinksHandler,
            deviceAttributionCallback = deviceAttributionCallback,
        )
        val config = buildSingularConfig(settings, intent) ?: run {
            sessionSettings = null
            return false
        }

        sessionSettings = settings
        val started = Singular.init(context, config)
        if (started) {
            Singular.event(APP_OPEN_EVENT)
        }
        return started
    }

    fun handleNewIntent(intent: Intent?): Boolean {
        val context = appContext ?: return false
        val settings = sessionSettings ?: return false
        if (intent?.data == null || settings.singularLinksHandler == null) return false

        val config = buildSingularConfig(settings, intent) ?: return false
        return Singular.init(context, config)
    }

    @JvmOverloads
    fun makeSingularConfig(
        singularApiKey: String,
        singularSecret: String,
        intent: Intent?,
        customerUserId: String?,
        enableLogging: Boolean = false,
        brandedDomains: List<String>? = null,
        singularLinksHandler: ((SingularLinkParams) -> Unit)? = null,
        deviceAttributionCallback: ((Map<String, Any?>) -> Unit)? = null,
    ): SingularConfig? = buildSingularConfig(
        SessionSettings(
            apiKey = singularApiKey.trim(),
            secret = singularSecret.trim(),
            customerUserId = customerUserId,
            enableLogging = enableLogging,
            brandedDomains = brandedDomains,
            singularLinksHandler = singularLinksHandler,
            deviceAttributionCallback = deviceAttributionCallback,
        ),
        intent,
    )

    suspend fun initialize(
        applicationId: String,
        customUserId: String? = null,
        adaptyId: String? = null,
        deviceId: String? = null,
        singularDeviceId: String? = null,
        advertisingId: String? = null,
        appStoreCountry: String? = null,
        appVersion: String? = null,
        androidVersion: String? = null,
        forceRefresh: Boolean = false,
    ): AppUserResponse = initializeMutex.withLock {
        val trimmedApplicationId = applicationId.trim()
        if (trimmedApplicationId.isEmpty()) throw GMSingularException.MissingRequestData()

        if (!forceRefresh) {
            val stored = try {
                loadStoredAppUser()
            } catch (_: GMSingularException) {
                null
            }
            if (stored != null) {
                applySingularCustomUserId(stored.customUserId.nonBlank() ?: getOrCreateCustomUserId())
                return@withLock stored
            }
        }

        val resolvedCustomUserId = customUserId.nonBlank() ?: getOrCreateCustomUserId()
        val resolvedDeviceId = deviceId.nonBlank()
            ?: withContext(ioDispatcher) { identifiers.appSetId() }.nonBlank()
            ?: throw GMSingularException.MissingRequestData()
        val resolvedSingularDeviceId = singularDeviceId.nonBlank() ?: resolvedDeviceId
        val resolvedAdvertisingId = usableAdvertisingId(
            advertisingId.nonBlank() ?: withContext(ioDispatcher) { identifiers.advertisingId() }
        )

        val request = CreateAppUserRequest(
            customUserId = resolvedCustomUserId,
            adaptyId = adaptyId.nonBlank() ?: resolvedCustomUserId,
            deviceId = resolvedDeviceId,
            singularDeviceId = resolvedSingularDeviceId,
            advertisingId = resolvedAdvertisingId,
            appStoreCountry = appStoreCountry,
            appVersion = appVersion,
            androidVersion = androidVersion ?: osVersionProvider(),
        )

        val appUser = createAppUser(trimmedApplicationId, request)
        persist(appUser)
        cachedAppUser = appUser
        applySingularCustomUserId(appUser.customUserId.nonBlank() ?: resolvedCustomUserId)
        appUser
    }

    fun getOrCreateCustomUserId(): String {
        cachedCustomUserId?.let { return it }
        return synchronized(customUserIdLock) {
            cachedCustomUserId ?: (readStoredCustomUserId()
                ?: peekStoredAppUser()?.customUserId.nonBlank()
                ?: customUserIdGenerator()
            ).also { id ->
                writeStoredCustomUserId(id)
                cachedCustomUserId = id
            }
        }
    }

    suspend fun currentAppUser(): AppUserResponse? = loadStoredAppUser()

    suspend fun clearStoredAppUser() {
        cachedAppUser = null
        withContext(ioDispatcher) {
            try {
                store.remove(APP_USER_KEY)
            } catch (e: Exception) {
                throw GMSingularException.Storage(e)
            }
        }
    }

    fun savedCustomUserId(): String? =
        cachedCustomUserId ?: readStoredCustomUserId() ?: peekStoredAppUser()?.customUserId.nonBlank()

    fun savedSingularDeviceId(): String? = peekStoredAppUser()?.singularDeviceId.nonBlank()

    fun savedAdaptyId(): String? = peekStoredAppUser()?.adaptyId.nonBlank()

    private fun buildSingularConfig(settings: SessionSettings, intent: Intent?): SingularConfig? {
        if (settings.apiKey.isEmpty() || settings.secret.isEmpty()) return null

        val config = SingularConfig(settings.apiKey, settings.secret)

        if (settings.enableLogging) {
            config.withLoggingEnabled()
        }

        config.withCustomUserId(resolveCustomerUserId(settings.customerUserId))

        settings.brandedDomains?.takeIf { it.isNotEmpty() }?.let { config.withBrandedDomains(it) }

        val linksHandler = settings.singularLinksHandler
        if (linksHandler != null && intent != null) {
            config.withSingularLink(intent, SingularLinkHandler { params -> linksHandler(params) })
        }

        settings.deviceAttributionCallback?.let { callback ->
            config.withSingularDeviceAttribution(
                SingularDeviceAttributionHandler { attribution -> callback(attribution.orEmpty()) }
            )
        }

        return config
    }

    private fun resolveCustomerUserId(customerUserId: String?): String =
        customerUserId.nonBlank() ?: getOrCreateCustomUserId()

    private fun readStoredCustomUserId(): String? = try {
        store.getString(CUSTOM_USER_ID_KEY).nonBlank()
    } catch (_: Exception) {
        null
    }

    private fun writeStoredCustomUserId(id: String) {
        try {
            store.putString(CUSTOM_USER_ID_KEY, id)
        } catch (_: Exception) {
        }
    }

    private fun peekStoredAppUser(): AppUserResponse? {
        cachedAppUser?.let { return it }
        val stored = try {
            readStoredAppUser()
        } catch (_: GMSingularException) {
            null
        } ?: return null
        cachedAppUser = stored
        return stored
    }

    private fun usableAdvertisingId(raw: String?): String? =
        raw.nonBlank()?.takeUnless { id -> id.all { it == '0' || it == '-' } }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun createAppUser(applicationId: String, request: CreateAppUserRequest): AppUserResponse {
        val body = try {
            json.encodeToString(CreateAppUserRequest.serializer(), request)
        } catch (e: SerializationException) {
            throw GMSingularException.EncodingFailed(e)
        }

        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer $applicationId",
        )
        val response = withContext(ioDispatcher) { network.post(requestUrl, headers, body) }

        if (response.statusCode !in 200..299) {
            throw GMSingularException.InvalidStatusCode(response.statusCode)
        }

        return try {
            json.decodeFromString(AppUserResponse.serializer(), response.body)
        } catch (e: IllegalArgumentException) {
            throw GMSingularException.DecodingFailed(e)
        }
    }

    private suspend fun persist(appUser: AppUserResponse) {
        val encoded = try {
            json.encodeToString(AppUserResponse.serializer(), appUser)
        } catch (e: SerializationException) {
            throw GMSingularException.EncodingFailed(e)
        }

        withContext(ioDispatcher) {
            try {
                store.putString(APP_USER_KEY, encoded)
            } catch (e: Exception) {
                throw GMSingularException.Storage(e)
            }
        }
    }

    private suspend fun loadStoredAppUser(): AppUserResponse? {
        cachedAppUser?.let { return it }
        val stored = withContext(ioDispatcher) { readStoredAppUser() } ?: return null
        cachedAppUser = stored
        return stored
    }

    private fun readStoredAppUser(): AppUserResponse? {
        val raw = try {
            store.getString(APP_USER_KEY)
        } catch (e: Exception) {
            throw GMSingularException.Storage(e)
        } ?: return null

        return try {
            json.decodeFromString(AppUserResponse.serializer(), raw)
        } catch (e: IllegalArgumentException) {
            throw GMSingularException.DecodingFailed(e)
        }
    }

    private fun applySingularCustomUserId(customUserId: String) {
        val trimmed = customUserId.trim()
        if (trimmed.isEmpty()) return
        singularUserSetter.setCustomUserId(trimmed)
    }

    private data class SessionSettings(
        val apiKey: String,
        val secret: String,
        val customerUserId: String?,
        val enableLogging: Boolean,
        val brandedDomains: List<String>?,
        val singularLinksHandler: ((SingularLinkParams) -> Unit)?,
        val deviceAttributionCallback: ((Map<String, Any?>) -> Unit)?,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://events.goingmerry.xyz"
        const val DEFAULT_REQUEST_PATH = "v1/app-users"
        const val DEFAULT_STORE_NAME = "com.goingmerry.gmsingular"
        const val APP_OPEN_EVENT = "app_open"

        internal const val APP_USER_KEY = "gm.singular.app_user"
        internal const val CUSTOM_USER_ID_KEY = "gm.singular.custom_user_id"

        @Volatile
        private var instance: GMSingularClient? = null

        @JvmStatic
        fun shared(context: Context): GMSingularClient =
            instance ?: synchronized(this) {
                instance ?: GMSingularClient(context.applicationContext).also { instance = it }
            }
    }
}
