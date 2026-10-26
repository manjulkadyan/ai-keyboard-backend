package com.manjul.kbapi

enum class AuthMode { FIREBASE, DEV }

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
) {
    override fun toString(): String =
        "Config(authMode=$authMode, firebaseProjectId=$firebaseProjectId, firebaseProjectNumber=$firebaseProjectNumber, " +
            "anthropicApiKey=${if (anthropicApiKey == null) "unset" else "set"}, rewriteModel=$rewriteModel, " +
            "stepUpModel=$stepUpModel, freeDaily=$freeDaily, proDaily=$proDaily, proMonthly=$proMonthly, " +
            "uidPerMinute=$uidPerMinute, ipPerMinute=$ipPerMinute, hourlySpendLimitUsd=$hourlySpendLimitUsd, " +
            "aiEnabled=$aiEnabled, maxInputChars=$maxInputChars)"

    companion object {
        /**
         * Reads the config. Throws [IllegalStateException] (message names the variable, never a value) when:
         * - `AUTH_MODE=dev` and `K_SERVICE` is set, i.e. on Cloud Run (decision 6);
         * - `AUTH_MODE` is unknown;
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
            val projectId = str("FIREBASE_PROJECT_ID") ?: str("GOOGLE_CLOUD_PROJECT")
            val projectNumber = str("FIREBASE_PROJECT_NUMBER")
            if (mode == AuthMode.FIREBASE) {
                if (projectId == null) error("FIREBASE_PROJECT_ID is required when AUTH_MODE=firebase")
                if (projectNumber == null) error("FIREBASE_PROJECT_NUMBER is required when AUTH_MODE=firebase")
            }
            val defaults = Config(AuthMode.DEV, "", "", null)
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
            )
        }
    }
}
