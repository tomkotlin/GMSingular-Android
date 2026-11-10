package com.goingmerry.gmsingular

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GMSingularClientTest {

    private class FakeNetwork(var response: NetworkResponse) : NetworkClient {
        data class Request(val url: String, val headers: Map<String, String>, val body: String)

        val requests = mutableListOf<Request>()

        override fun post(url: String, headers: Map<String, String>, body: String): NetworkResponse {
            requests += Request(url, headers, body)
            return response
        }
    }

    private class InMemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class RecordingUserSetter : SingularUserSetting {
        val ids = mutableListOf<String>()

        override fun setCustomUserId(id: String) {
            ids += id
        }
    }

    private class FakeIdentifiers(
        var appSetId: String? = "appset-1",
        var advertisingId: String? = null,
    ) : DeviceIdentifierProvider {
        var lookups = 0

        override suspend fun appSetId(): String? {
            lookups++
            return appSetId
        }

        override suspend fun advertisingId(): String? {
            lookups++
            return advertisingId
        }
    }

    private val serverAppUser = """
        {
          "application_id": "app-1",
          "custom_user_id": "user-1",
          "adapty_id": "user-1",
          "device_id": "device-1",
          "singular_device_id": "device-1",
          "app_store_country": "US",
          "app_version": "1.2.0",
          "android_version": "14",
          "iOS_version": null,
          "created_at": "2026-11-08T00:00:00Z"
        }
    """.trimIndent()

    private val network = FakeNetwork(NetworkResponse(201, serverAppUser))
    private val store = InMemoryStore()
    private val userSetter = RecordingUserSetter()
    private val identifiers = FakeIdentifiers()

    private fun TestScope.newClient() = GMSingularClient(
        appContext = null,
        baseUrl = "https://events.example.com/",
        requestPath = "/v1/app-users",
        store = store,
        network = network,
        singularUserSetter = userSetter,
        identifiers = identifiers,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        osVersionProvider = { "14" },
    )

    private fun FakeNetwork.lastBody(): JsonObject = Json.parseToJsonElement(requests.last().body).jsonObject

    @Test
    fun initializePostsRequestAndPersistsResponse() = runTest {
        val client = newClient()

        val appUser = client.initialize(
            applicationId = " app-1 ",
            customUserId = "user-1",
            adaptyId = "user-1",
            deviceId = "device-1",
            appStoreCountry = "US",
            appVersion = "1.2.0",
        )

        val request = network.requests.single()
        assertEquals("https://events.example.com/v1/app-users", request.url)
        assertEquals("Bearer app-1", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])

        val body = network.lastBody()
        assertEquals(
            mapOf(
                "custom_user_id" to "user-1",
                "adapty_id" to "user-1",
                "device_id" to "device-1",
                "singular_device_id" to "device-1",
                "app_store_country" to "US",
                "app_version" to "1.2.0",
                "android_version" to "14",
            ),
            body.mapValues { it.value.jsonPrimitive.content },
        )

        assertEquals("app-1", appUser.applicationId)
        assertEquals("user-1", appUser.customUserId)
        assertTrue(store.values.containsKey(GMSingularClient.APP_USER_KEY))
        assertEquals(listOf("user-1"), userSetter.ids)
        assertEquals("device-1", client.savedSingularDeviceId())
        assertEquals("user-1", client.savedAdaptyId())
    }

    @Test
    fun requestOmitsMissingOptionalFields() = runTest {
        val client = newClient()

        client.initialize(applicationId = "app-1", deviceId = "device-1", singularDeviceId = "sdid-given")

        assertEquals(
            setOf("device_id", "singular_device_id", "android_version"),
            network.lastBody().keys,
        )
        assertEquals("sdid-given", network.lastBody()["singular_device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun returnsStoredAppUserWithoutCallingBackend() = runTest {
        store.values[GMSingularClient.APP_USER_KEY] = serverAppUser
        val client = newClient()

        val appUser = client.initialize(applicationId = "app-1", deviceId = "device-1")

        assertTrue(network.requests.isEmpty())
        assertEquals("user-1", appUser.customUserId)
        assertEquals(listOf("user-1"), userSetter.ids)
    }

    @Test
    fun forceRefreshCallsBackendEvenWhenStored() = runTest {
        store.values[GMSingularClient.APP_USER_KEY] = serverAppUser
        val client = newClient()

        client.initialize(applicationId = "app-1", deviceId = "device-2", forceRefresh = true)

        assertEquals(1, network.requests.size)
        assertEquals("device-2", network.lastBody()["singular_device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun corruptStoredAppUserFallsBackToBackend() = runTest {
        store.values[GMSingularClient.APP_USER_KEY] = "not json"
        val client = newClient()

        client.initialize(applicationId = "app-1", deviceId = "device-1", singularDeviceId = "sdid-1")

        assertEquals(1, network.requests.size)
    }

    @Test
    fun sendsDeviceIdAsSingularDeviceIdWithoutWaiting() = runTest {
        val client = newClient()

        client.initialize(applicationId = "app-1", deviceId = " android-id-1 ")

        assertEquals(0L, testScheduler.currentTime)
        assertEquals("android-id-1", network.lastBody()["singular_device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun blankSingularDeviceIdFallsBackToDeviceId() = runTest {
        val client = newClient()

        client.initialize(applicationId = "app-1", deviceId = "android-id-1", singularDeviceId = "  ")

        assertEquals("android-id-1", network.lastBody()["singular_device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun usesAppSetIdForBothDeviceFieldsWhenNoDeviceIdIsGiven() = runTest {
        val client = newClient()

        client.initialize(applicationId = "app-1")

        val body = network.lastBody()
        assertEquals("appset-1", body["device_id"]!!.jsonPrimitive.content)
        assertEquals("appset-1", body["singular_device_id"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("custom_user_id"))
        assertFalse(body.containsKey("adapty_id"))
    }

    @Test
    fun blankDeviceIdFallsBackToAppSetId() = runTest {
        val client = newClient()

        client.initialize(applicationId = "app-1", deviceId = "  ", customUserId = " ")

        val body = network.lastBody()
        assertEquals("appset-1", body["device_id"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("custom_user_id"))
    }

    @Test
    fun missingDeviceIdAndAppSetIdFailsWithoutCallingBackend() = runTest {
        identifiers.appSetId = null
        val client = newClient()

        val error = runCatching {
            client.initialize(applicationId = "app-1")
        }.exceptionOrNull()

        assertTrue(error is GMSingularException.MissingRequestData)
        assertTrue(network.requests.isEmpty())
    }

    @Test
    fun sendsAdvertisingIdWhenAvailable() = runTest {
        identifiers.advertisingId = "38400000-8cf0-11bd-b23e-10b96e40000d"
        val client = newClient()

        client.initialize(applicationId = "app-1")

        assertEquals(
            "38400000-8cf0-11bd-b23e-10b96e40000d",
            network.lastBody()["advertising_id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun omitsAdvertisingIdWhenUnavailable() = runTest {
        identifiers.advertisingId = null
        val client = newClient()

        client.initialize(applicationId = "app-1")

        assertFalse(network.lastBody().containsKey("advertising_id"))
    }

    @Test
    fun omitsZeroedAdvertisingId() = runTest {
        identifiers.advertisingId = "00000000-0000-0000-0000-000000000000"
        val client = newClient()

        client.initialize(applicationId = "app-1")

        assertFalse(network.lastBody().containsKey("advertising_id"))
    }

    @Test
    fun passedAdvertisingIdWinsOverLookup() = runTest {
        identifiers.advertisingId = "looked-up"
        val client = newClient()

        client.initialize(applicationId = "app-1", advertisingId = "given")

        assertEquals("given", network.lastBody()["advertising_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun storedAppUserSkipsIdentifierLookups() = runTest {
        store.values[GMSingularClient.APP_USER_KEY] = serverAppUser
        val client = newClient()

        client.initialize(applicationId = "app-1")

        assertEquals(0, identifiers.lookups)
        assertTrue(network.requests.isEmpty())
    }

    @Test
    fun savedCustomUserIdIsReadableWithoutCoroutine() {
        store.values[GMSingularClient.APP_USER_KEY] = serverAppUser
        val client = TestScope().newClient()

        assertEquals("user-1", client.savedCustomUserId())
        assertEquals("device-1", client.savedSingularDeviceId())
        assertEquals("user-1", client.savedAdaptyId())
    }

    @Test
    fun savedCustomUserIdIsNullBeforeFirstInitialize() {
        val client = TestScope().newClient()

        assertNull(client.savedCustomUserId())
    }

    @Test
    fun backendGeneratedCustomUserIdIsAppliedToSingularAndSaved() = runTest {
        val client = newClient()

        val appUser = client.initialize(applicationId = "app-1")

        assertEquals("user-1", appUser.customUserId)
        assertEquals(listOf("user-1"), userSetter.ids)
        assertEquals("user-1", client.savedCustomUserId())
    }

    @Test
    fun blankApplicationIdFailsBeforeAnyWork() = runTest {
        val client = newClient()

        val error = runCatching {
            client.initialize(applicationId = "  ", deviceId = "device-1", singularDeviceId = "sdid-1")
        }.exceptionOrNull()

        assertTrue(error is GMSingularException.MissingRequestData)
        assertTrue(network.requests.isEmpty())
    }

    @Test
    fun errorStatusIsReportedAndNothingIsStored() = runTest {
        network.response = NetworkResponse(500, "boom")
        val client = newClient()

        val error = runCatching {
            client.initialize(applicationId = "app-1", deviceId = "device-1", singularDeviceId = "sdid-1")
        }.exceptionOrNull()

        assertEquals(500, (error as GMSingularException.InvalidStatusCode).statusCode)
        assertNull(store.values[GMSingularClient.APP_USER_KEY])
        assertTrue(userSetter.ids.isEmpty())
    }

    @Test
    fun undecodableResponseIsReported() = runTest {
        network.response = NetworkResponse(200, "{\"unexpected\":true}")
        val client = newClient()

        val error = runCatching {
            client.initialize(applicationId = "app-1", deviceId = "device-1", singularDeviceId = "sdid-1")
        }.exceptionOrNull()

        assertTrue(error is GMSingularException.DecodingFailed)
    }

    @Test
    fun clearStoredAppUserForgetsCachedAndStoredUser() = runTest {
        val client = newClient()
        client.initialize(applicationId = "app-1", deviceId = "device-1", singularDeviceId = "sdid-1")

        client.clearStoredAppUser()

        assertNull(client.currentAppUser())
        assertNull(store.values[GMSingularClient.APP_USER_KEY])
    }
}
