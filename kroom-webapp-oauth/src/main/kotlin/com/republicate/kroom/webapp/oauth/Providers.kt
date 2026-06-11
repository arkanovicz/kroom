package com.republicate.kroom.webapp.oauth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.AuthorizationRequest
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.TokenErrorResponse
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import com.republicate.kson.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/** Normalized identity returned by a provider after code exchange. */
data class Profile(
    /** Stable per-provider user id (OIDC `sub`, GitHub numeric id). */
    val id: String,
    val name: String?,
    val email: String?
)

/** An authentication provider usable in [OAuthConfig.providers]. */
interface OAuthProvider {
    val name: String

    /** The authorization URI the user is redirected to. */
    suspend fun authRequestUri(redirectUri: String, state: State, nonce: Nonce): URI

    /** Exchange the callback code for a normalized [Profile]. */
    suspend fun authenticate(code: String, redirectUri: String, nonce: String): Profile
}

/**
 * An OIDC provider. Metadata is fetched from [discoveryUri] on first use,
 * or injected directly (tests, non-discoverable providers).
 */
class OidcProvider(
    override val name: String,
    val clientId: String,
    val clientSecret: String?,
    private val discoveryUri: String? = null,
    metadata: OIDCProviderMetadata? = null,
    jwkSet: JWKSet? = null,
    private val scope: List<String> = listOf("openid", "profile", "email"),
    /** Extra authorization-request parameters (e.g. Apple's `response_mode=form_post`). */
    private val extraAuthParams: Map<String, String> = emptyMap(),
    /** Dynamic secret (e.g. Apple's generated JWT); takes precedence over [clientSecret]. */
    private val clientSecretSupplier: (() -> String)? = null,
    /** Send the secret as `client_secret_post` instead of Basic auth (Apple requires it). */
    private val clientSecretPost: Boolean = false,
    /** Some providers (LinkedIn) never echo the nonce in the id_token. */
    private val requireNonce: Boolean = true
) : OAuthProvider {
    init {
        require(discoveryUri != null || metadata != null) { "provider '$name' needs a discoveryUri or metadata" }
    }

    // benign race: concurrent first uses may fetch twice
    private var resolvedMetadata: OIDCProviderMetadata? = metadata
    private var resolvedValidator: IDTokenValidator? = null
    private val jwkSetOverride = jwkSet

    private suspend fun metadata(): OIDCProviderMetadata =
        resolvedMetadata ?: withContext(Dispatchers.IO) {
            OIDCProviderMetadata.parse(URI(discoveryUri!!).toURL().readText())
        }.also { resolvedMetadata = it }

    private suspend fun validator(): IDTokenValidator =
        resolvedValidator ?: metadata().let { md ->
            if (jwkSetOverride != null)
                IDTokenValidator(md.issuer, ClientID(clientId), JWSAlgorithm.RS256, jwkSetOverride)
            else
                IDTokenValidator(md.issuer, ClientID(clientId), JWSAlgorithm.RS256, md.jwkSetURI.toURL())
        }.also { resolvedValidator = it }

    override suspend fun authRequestUri(redirectUri: String, state: State, nonce: Nonce): URI =
        AuthenticationRequest.Builder(
            ResponseType.CODE,
            Scope(*scope.toTypedArray()),
            ClientID(clientId),
            URI(redirectUri)
        ).endpointURI(metadata().authorizationEndpointURI)
            .state(state)
            .nonce(nonce)
            .apply { extraAuthParams.forEach { (k, v) -> customParameter(k, v) } }
            .build()
            .toURI()

    override suspend fun authenticate(code: String, redirectUri: String, nonce: String): Profile {
        val grant = AuthorizationCodeGrant(AuthorizationCode(code), URI(redirectUri))
        val secret = clientSecretSupplier?.invoke() ?: clientSecret
        val request = when {
            secret == null -> TokenRequest(metadata().tokenEndpointURI, ClientID(clientId), grant)
            clientSecretPost -> TokenRequest(metadata().tokenEndpointURI, ClientSecretPost(ClientID(clientId), Secret(secret)), grant)
            else -> TokenRequest(metadata().tokenEndpointURI, ClientSecretBasic(ClientID(clientId), Secret(secret)), grant)
        }
        val tokenEndpointUri = metadata().tokenEndpointURI
        val claims = withContext(Dispatchers.IO) {
            val response = OIDCTokenResponseParser.parse(request.toHTTPRequest().send())
            if (!response.indicatesSuccess())
                throw OAuthFlowException("token exchange at $tokenEndpointUri failed: ${(response as TokenErrorResponse).errorObject}")
            val idToken = (response as OIDCTokenResponse).oidcTokens.idToken
                ?: throw OAuthFlowException("no id_token in token response")
            validator().validate(idToken, if (requireNonce) Nonce(nonce) else null)
        }
        return Profile(
            id = claims.subject.value,
            name = claims.getStringClaim("name") ?: claims.getStringClaim("preferred_username"),
            email = claims.getStringClaim("email")
        )
    }
}

/**
 * A raw OAuth2 provider (no OIDC, no id_token — the GitHub case): explicit
 * authorize/token/userinfo endpoints, code → access token → userinfo.
 *
 * [extractProfile] maps the userinfo document to a [Profile]; it receives a
 * `fetch` helper doing authorized GETs for providers needing extra calls
 * (e.g. GitHub's private-email fallback). Returning null rejects the login.
 */
class OAuth2Provider(
    override val name: String,
    val clientId: String,
    val clientSecret: String,
    private val authorizeUri: String,
    private val tokenUri: String,
    private val userInfoUri: String,
    private val scope: String,
    private val extractProfile: suspend (userInfo: Json.Object, fetch: suspend (url: String) -> Json?) -> Profile? = DEFAULT_EXTRACTOR
) : OAuthProvider {

    override suspend fun authRequestUri(redirectUri: String, state: State, nonce: Nonce): URI =
        AuthorizationRequest.Builder(ResponseType.CODE, ClientID(clientId))
            .scope(Scope.parse(scope))
            .redirectionURI(URI(redirectUri))
            .endpointURI(URI(authorizeUri))
            .state(state)
            .build()
            .toURI()

    override suspend fun authenticate(code: String, redirectUri: String, nonce: String): Profile {
        val tokenBody = postForm(
            tokenUri, mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "code" to code,
                "redirect_uri" to redirectUri,
                "grant_type" to "authorization_code"
            )
        )
        val tokenJson = Json.parse(tokenBody) as? Json.Object
            ?: throw OAuthFlowException("unparseable token response from $name")
        val accessToken = tokenJson.getString("access_token")
            ?: throw OAuthFlowException("no access_token from $name: ${tokenJson.getString("error") ?: tokenBody}")
        val fetch: suspend (String) -> Json? = { url -> Json.parse(getWithBearer(url, accessToken)) }
        val userInfo = fetch(userInfoUri) as? Json.Object
            ?: throw OAuthFlowException("unparseable userinfo from $name")
        return extractProfile(userInfo, fetch)
            ?: throw OAuthFlowException("no usable profile from $name userinfo")
    }

    companion object {
        /** Standard-ish fields: `sub`/`id`, `name`, `email`. */
        val DEFAULT_EXTRACTOR: suspend (Json.Object, suspend (String) -> Json?) -> Profile? = { userInfo, _ ->
            (userInfo.getString("sub") ?: userInfo["id"]?.toString())?.let { id ->
                Profile(id, userInfo.getString("name"), userInfo.getString("email"))
            }
        }

        private val http: java.net.http.HttpClient = java.net.http.HttpClient.newHttpClient()

        private suspend fun postForm(url: String, form: Map<String, String>): String = withContext(Dispatchers.IO) {
            val body = form.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}"
            }
            val request = HttpRequest.newBuilder(URI(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json") // GitHub answers form-encoded without it
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299)
                throw OAuthFlowException("token exchange at $url failed: HTTP ${response.statusCode()}")
            response.body()
        }

        private suspend fun getWithBearer(url: String, accessToken: String): String = withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI(url))
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299)
                throw OAuthFlowException("GET $url failed: HTTP ${response.statusCode()}")
            response.body()
        }
    }
}

/**
 * GitHub as a raw OAuth2 provider. [webBase]/[apiBase] are overridable for
 * GitHub Enterprise (and tests).
 */
fun githubProvider(
    clientId: String,
    clientSecret: String,
    webBase: String = "https://github.com",
    apiBase: String = "https://api.github.com"
): OAuth2Provider = OAuth2Provider(
    name = "github",
    clientId = clientId,
    clientSecret = clientSecret,
    authorizeUri = "$webBase/login/oauth/authorize",
    tokenUri = "$webBase/login/oauth/access_token",
    userInfoUri = "$apiBase/user",
    scope = "user:email",
    extractProfile = { user, fetch ->
        // email is absent when private: fall back to /user/emails, primary-verified first
        val email = user.getString("email")
            ?: (fetch("$apiBase/user/emails") as? Json.Array)
                ?.filterIsInstance<Json.Object>()
                ?.sortedByDescending { it.getBoolean("primary") == true }
                ?.firstOrNull { it.getBoolean("verified") == true }
                ?.getString("email")
        user["id"]?.toString()?.let { id ->
            Profile(id, user.getString("name") ?: user.getString("login"), email)
        }
    }
)
