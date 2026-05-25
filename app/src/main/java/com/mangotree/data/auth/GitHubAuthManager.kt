package com.mangotree.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.openid.appauth.*

class GitHubAuthManager(private val context: Context) {

    private val authService = AuthorizationService(context)

    fun buildAuthIntent(clientId: String): Intent {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse("https://github.com/login/oauth/authorize"),
            Uri.parse("https://github.com/login/oauth/access_token")
        )
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse("com.mangotree://oauth")
        )
            .setScope("repo")
            .build()

        return authService.getAuthorizationRequestIntent(request)
    }

    fun exchangeCodeForToken(
        response: AuthorizationResponse,
        clientId: String,
        clientSecret: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val tokenRequest = response.createTokenExchangeRequest(
            mapOf("client_secret" to clientSecret)
        )
        authService.performTokenRequest(tokenRequest) { tokenResponse, ex ->
            val token = tokenResponse?.accessToken
            if (token != null) {
                onSuccess(token)
            } else {
                onError(ex?.message ?: "Token exchange failed")
            }
        }
    }

    fun dispose() = authService.dispose()
}
