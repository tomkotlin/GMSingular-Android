package com.goingmerry.gmsingular

sealed class GMSingularException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class MissingRequestData : GMSingularException("Missing app user request payload.")

    class InvalidStatusCode(val statusCode: Int) : GMSingularException("Unexpected status code: $statusCode")

    class EncodingFailed(cause: Throwable? = null) :
        GMSingularException("Could not encode the app user payload for the request.", cause)

    class DecodingFailed(cause: Throwable? = null) :
        GMSingularException("Could not decode the server response.", cause)

    class Storage(cause: Throwable) : GMSingularException("Storage failure: ${cause.message}", cause)
}
