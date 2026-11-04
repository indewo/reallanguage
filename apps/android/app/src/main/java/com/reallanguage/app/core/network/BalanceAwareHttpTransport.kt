package com.reallanguage.app.core.network

class BalanceAwareHttpTransport(
    private val delegate: HttpTransport,
    private val onBalanceRequired: (HttpRequest) -> Unit,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val response = delegate.execute(request)
        if (
            response.statusCode == HTTP_PAYMENT_REQUIRED &&
            BALANCE_CODE.containsMatchIn(response.body)
        ) {
            onBalanceRequired(request)
        }
        return response
    }

    private companion object {
        const val HTTP_PAYMENT_REQUIRED = 402
        val BALANCE_CODE = Regex("\\\"code\\\"\\s*:\\s*\\\"AI_BALANCE_INSUFFICIENT\\\"")
    }
}
