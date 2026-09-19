package com.sosync.common

import io.viascom.nanoid.NanoId

/**
 * Primary keys are 21-character NanoIds, the same convention the Readers services use.
 *
 * Over a sequential integer: an incident id ends up in SMS bodies and deep links, and a
 * guessable one lets anyone enumerate other people's emergencies. Over a UUID: shorter, so it
 * survives an SMS without eating the character budget.
 */
object NanoIds {
    fun generate(): String = NanoId.generate()
}

/**
 * Six-digit numeric codes for phone verification and password reset.
 *
 * Not cryptographically strong and not meant to be: they are short-lived, single-use, and the
 * prototype has no real SMS channel to deliver them over. A production build needs rate
 * limiting and attempt counting before these are worth anything.
 */
object VerificationCodes {
    private val random = java.security.SecureRandom()

    fun generate(): String = "%06d".format(random.nextInt(1_000_000))
}
