package com.manjul.kbapi

enum class AuthMode { FIREBASE, DEV }

/** The model provider: the Anthropic API, or canned replies for local runs (`GATEWAY=fake`, dev auth only). */
enum class GatewayMode { ANTHROPIC, FAKE }

/**
 * Service configuration, read once at startup from the environment.
 * [anthropicApiKey] comes from Secret Manager (mounted as env) in prod and is never logged:
 * [toString] redacts it.
 */
data class Config(
    val authMode: AuthMode,
    val firebaseProjectId: String,
    val firebaseProjectNumber: String,
    val anthropicApiKey: String?,
    val rewriteModel: String = "claude-haiku-5-5",
    val stepUpModel: String = "claude-sonnet-5-5",
    val freeDaily: Int = 10,
    val proDaily: Int = 150,
    val proMonthly: Int = 3000,
    val uidPerMinute: Int = 10,
    val ipPerMinute: Int = 60,
    val hourlySpendLimitUsd: Double = 20.0,
    val aiEnabled: Boolean = true,
    val maxInputChars: Int = 4000,
    val gateway: GatewayMode = GatewayMode.ANTHROPIC,
    /** Proxies that append to `X-Forwarded-For` (external HTTPS load balancer: 2). See [com.manjul.kbapi.limits.clientIpFrom]. */
    val trustedProxyHops: Int = 2,
    /** The Play package kb-api reads subscriptions for. RTDN for any other package is dropped. */
    val playPackageName: String = "com.manjul.aikeyboard",
    /** PKCS#8 EC P-256 key (base64 DER or PEM) from Secret Manager. Unset: entitlement routes answer 503. */
    val entitlementSigningKey: String? = null,
    /** The push endpoint URL set as the OIDC audience on the Pub/Sub subscription. */
    val rtdnAudience: String? = null,
    /** The service account Pub/Sub signs push tokens as. With [rtdnAudience] unset, RTDN answers 401. */
    val rtdnServiceAccount: String? = null,
    /** The contact address on the public deletion page (decision 21). */
    val deletionContactEmail: String = "privacy@example.com",
    /**
     * The dictionary source base URL (OD-11), for example `https://freedictionaryapi.com/api/v1/entries`.
     * Unset: `/v1/dictionary` answers 503 `dictionary_unavailable` (until the source terms are checked).
     */
    val dictionarySourceUrl: String? = null,
    /** `/v1/dictionary` lookups a minute per uid (Sprint 5 open question 5). */
    val dictionaryPerMinute: Int = 60,
) {
    override fun toString(): String =
        "Config(authMode=$authMode, firebaseProjectId=$firebaseProjectId, firebaseProjectNumber=$firebaseProjectNumber, " +
            "anthropicApiKey=${if (anthropicApiKey == null) "unset" else "set"}, rewriteModel=$rewriteModel, " +
            "stepUpModel=$stepUpModel, freeDaily=$freeDaily, proDaily=$proDaily, proMonthly=$proMonthly, " +
            "uidPerMinute=$uidPerMinute, ipPerMinute=$ipPerMinute, hourlySpendLimitUsd=$hourlySpendLimitUsd, " +
            "aiEnabled=$aiEnabled, maxInputChars=$maxInputChars, gateway=$gateway, trustedProxyHops=$trustedProxyHops, " +
            "playPackageName=$playPackageName, " +
            "entitlementSigningKey=${if (entitlementSigningKey == null) "unset" else "set"}, " +
            "rtdnAudience=${rtdnAudience ?: "unset"}, rtdnServiceAccount=${rtdnServiceAccount ?: "unset"}, " +
            "deletionContactEmail=$deletionContactEmail, dictionarySourceUrl=${dictionarySourceUrl ?: "unset"}, " +
            "dictionaryPerMinute=$dictionaryPerMinute)"

    companion object {
        /**
         * Reads the config. Throws [IllegalStateException] (message names the variable, never a value) when:
         * - `AUTH_MODE=dev` and `K_SERVICE` is set, i.e. on Cloud Run (decision 6);
         * - `AUTH_MODE` is unknown;
         * - `GATEWAY` is unknown, or `GATEWAY=fake` without `AUTH_MODE=dev`;
         * - `TRUSTED_PROXY_HOPS` is below 1;
         * - only one of `RTDN_AUDIENCE` and `RTDN_SERVICE_ACCOUNT` is set;
         * - `DELETION_CONTACT_EMAIL` is not a plain address;
         * - `DICTIONARY_SOURCE_URL` is not an `https` URL (`http` only for localhost);
         * - Firebase mode lacks `FIREBASE_PROJECT_ID` (or `GOOGLE_CLOUD_PROJECT`) or `FIREBASE_PROJECT_NUMBER`.
         */
        fun fromEnv(env: Map<String, String>): Config {
            fun str(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun int(name: String, default: Int): Int =
                str(name)?.let { it.toIntOrNull() ?: error("$name must be an integer") } ?: default
            fun double(name: String, default: Double): Double =
                str(name)?.let { it.toDoubleOrNull() ?: error("$name must be a number") } ?: default
            fun bool(name: String, default: Boolean): Boolean = when (str(name)?.lowercase()) {
                null -> default
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> error("$name must be true or false")
            }

            val mode = when (str("AUTH_MODE")?.lowercase()) {
                null, "firebase" -> AuthMode.FIREBASE
                "dev" -> AuthMode.DEV
                else -> error("AUTH_MODE must be firebase or dev")
            }
            if (mode == AuthMode.DEV && str("K_SERVICE") != null) {
                error("AUTH_MODE=dev is refused on Cloud Run (K_SERVICE is set)")
            }
            val gateway = when (str("GATEWAY")?.lowercase()) {
                null, "anthropic" -> GatewayMode.ANTHROPIC
                "fake" -> GatewayMode.FAKE
                else -> error("GATEWAY must be anthropic or fake")
            }
            if (gateway == GatewayMode.FAKE && mode != AuthMode.DEV) error("GATEWAY=fake needs AUTH_MODE=dev")
            val projectId = str("FIREBASE_PROJECT_ID") ?: str("GOOGLE_CLOUD_PROJECT")
            val projectNumber = str("FIREBASE_PROJECT_NUMBER")
            if (mode == AuthMode.FIREBASE) {
                if (projectId == null) error("FIREBASE_PROJECT_ID is required when AUTH_MODE=firebase")
                if (projectNumber == null) error("FIREBASE_PROJECT_NUMBER is required when AUTH_MODE=firebase")
            }
            val rtdnAudience = str("RTDN_AUDIENCE")
            val rtdnServiceAccount = str("RTDN_SERVICE_ACCOUNT")
            if ((rtdnAudience == null) != (rtdnServiceAccount == null)) {
                error("RTDN_AUDIENCE and RTDN_SERVICE_ACCOUNT must be set together")
            }
            val defaults = Config(AuthMode.DEV, "", "", null)
            val contact = str("DELETION_CONTACT_EMAIL") ?: defaults.deletionContactEmail
            if (!Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matches(contact)) {
                error("DELETION_CONTACT_EMAIL must be a plain email address")
            }
            val dictionaryUrl = str("DICTIONARY_SOURCE_URL")
            if (dictionaryUrl != null && !dictionaryUrl.startsWith("https://") &&
                !Regex("http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?(/.*)?").matches(dictionaryUrl)
            ) {
                error("DICTIONARY_SOURCE_URL must be an https URL")
            }
            return Config(
                authMode = mode,
                firebaseProjectId = projectId.orEmpty(),
                firebaseProjectNumber = projectNumber.orEmpty(),
                anthropicApiKey = str("ANTHROPIC_API_KEY"),
                rewriteModel = str("REWRITE_MODEL") ?: defaults.rewriteModel,
                stepUpModel = str("STEP_UP_MODEL") ?: defaults.stepUpModel,
                freeDaily = int("FREE_DAILY", defaults.freeDaily),
                proDaily = int("PRO_DAILY", defaults.proDaily),
                proMonthly = int("PRO_MONTHLY", defaults.proMonthly),
                uidPerMinute = int("UID_PER_MINUTE", defaults.uidPerMinute),
                ipPerMinute = int("IP_PER_MINUTE", defaults.ipPerMinute),
                hourlySpendLimitUsd = double("HOURLY_SPEND_LIMIT_USD", defaults.hourlySpendLimitUsd),
                aiEnabled = bool("AI_ENABLED", defaults.aiEnabled),
                maxInputChars = int("MAX_INPUT_CHARS", defaults.maxInputChars),
                gateway = gateway,
                trustedProxyHops = int("TRUSTED_PROXY_HOPS", defaults.trustedProxyHops).also {
                    if (it < 1) error("TRUSTED_PROXY_HOPS must be at least 1")
                },
                playPackageName = str("PLAY_PACKAGE_NAME") ?: defaults.playPackageName,
                entitlementSigningKey = str("ENTITLEMENT_SIGNING_KEY"),
                rtdnAudience = rtdnAudience,
                rtdnServiceAccount = rtdnServiceAccount,
                deletionContactEmail = contact,
                dictionarySourceUrl = dictionaryUrl,
                dictionaryPerMinute = int("DICTIONARY_PER_MINUTE", defaults.dictionaryPerMinute).also {
                    if (it < 1) error("DICTIONARY_PER_MINUTE must be at least 1")
                },
            )
        }
    }
}
