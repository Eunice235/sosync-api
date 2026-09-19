package com.sosync.common

import org.springframework.security.crypto.password.PasswordEncoder

/**
 * [PasswordEncoder.encode] is declared as returning a nullable String, because the interface
 * allows an implementation to refuse. BCrypt never does, and a null hash reaching the database
 * would mean an account nobody could ever sign in to, so it is turned into a failure here
 * rather than carried through every call site as a nullable type.
 */
fun PasswordEncoder.hash(raw: String): String =
    checkNotNull(encode(raw)) { "Password encoder produced no hash" }
