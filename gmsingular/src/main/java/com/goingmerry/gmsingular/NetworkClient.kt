package com.goingmerry.gmsingular

import java.net.HttpURLConnection
import java.net.URL

data class NetworkResponse(val statusCode: Int, val body: String)

fun interface NetworkClient {
    fun post(url: String, headers: Map<String, String>, body: String): NetworkResponse
}

class HttpUrlConnectionNetworkClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 15_000,
) : NetworkClient {

    override fun post(url: String, headers: Map<String, String>, body: String): NetworkResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return NetworkResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}
