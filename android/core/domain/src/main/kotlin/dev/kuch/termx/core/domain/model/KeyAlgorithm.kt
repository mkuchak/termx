package dev.kuch.termx.core.domain.model

/**
 * Supported private-key algorithms for generated/imported [KeyPair]s.
 *
 * Ed25519 is the default per the architectural grilling decisions; RSA 4096 is
 * kept for compatibility with legacy hosts that reject Ed25519.
 */
enum class KeyAlgorithm { ED25519, RSA_4096 }
