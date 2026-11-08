package com.goingmerry.gmsingular

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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

    private val serverAppUser = """
        {
          "application_id": "app-1",
          "custom_user_id": "user-1",
          "adapty_id": "user-1",
          "device_id": "device-1",
          "singular_device_id": "sdid-1",
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

    private fun TestScope.newClient() = GMSingularClient(
        appContext = null,
        baseUrl = "https://events.example.com/",
        requestPath = "/v1/app-users",
        store = store,
        network = network,
        singularUserSetter = userSetter,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        osVersionProvider = { "14" },
    )

    private fun FakeNetwork.lastBody(): JsonObject = Json.parseToJsonElement(requests.last().body).jsonObject

    @Test
    fun initializePostsRequestAndPersistsResponse() = runTest {
        val client = newClient()
        client.recordSingularDeviceId("sdid-1")

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
                "singular_device_id" to "sdid-1",
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
        assertEquals("sdid-1", client.savedSingularDeviceId())
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

        client.initialize(applicationId = "app-1", deviceId = "device-1", forceRefresh = true)

        assertEquals(1, network.requests.size)
        assertEquals("sdid-1", network.lastBody()["singular_device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun corruptStoredAppUserFallsBackToBackend() = runTest {
        store.values[GMSingularClient.APP_USER_KEY] = "not json"
        val client = newClient()

        client.initialize(applicationId = "app-1", deviceId = "device-1", singularDeviceId = "sdid-1")

        assertEquals(1, network.requests.size)
    }

    @Test
    fun waitsForSingularDeviceIdDeliveredBySdk() = runTest {
        val client = newClient()
        launch {
            delay(2_000)
            client.recordSingularDeviceId("sdid-late")
        }

        client.initialize(applicationId = "app-1", deviceId = "device-1")

        assertEquals("sdid-late", network.lastBody()["singular_device_id"]!!.jsonPrimitive.content)
        assertEquals("sdid-late", store.values[GMSingularClient.SINGULAR_DEVICE_ID_KEY])
    }

    @Test
    fun usesSingularDeviceIdSavedOnPreviousLaunch() = runTest {
        store.values[GMSingularClient.SINGULAR_DEVICE_ID_KEY] = "sdid-saved"
        val client = newClient()

        client.initialize(applicationId = "app-1", deviceId = "device-1")

        assertEquals("sdid-saved", network.lastBody()["singular_device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun failsWhenSingularDeviceIdNeverArrives() = runTest {
        val client = newClient()

        val error = runCatching {
            client.initialize(applicationId = "app-1", deviceId = "device-1", singularDeviceIdTimeoutMillis = 3_000)
        }.exceptionOrNull()

        assertTrue(error is GMSingularException.MissingSingularDeviceId)
        assertTrue(network.requests.isEmpty())
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
