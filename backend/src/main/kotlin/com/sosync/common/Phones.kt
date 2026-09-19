package com.sosync.common

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Phone numbers are normalised to E.164 before they are stored or compared.
 *
 * This matters more here than in most systems. The number is the login identifier, the SMS
 * fallback address, and the link between a trusted contact and the account that contact belongs
 * to. If `0712345678` and `+254712345678` are stored as different strings, a registered contact
 * silently stops receiving in-app alerts and nobody finds out until an emergency.
 *
 * [DEFAULT_REGION] only decides how a local-format number is interpreted; any number typed with
 * a `+` prefix is parsed on its own terms, so international contacts work regardless.
 */
object Phones {
    /**
     * Region assumed for numbers entered without a country code. Kenya, matching the first
     * deployment context. A multi-country build would take this from the user profile rather
     * than a constant.
     */
    const val DEFAULT_REGION = "KE"

    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    /**
     * Returns the number in E.164 form, or throws [BadRequestException] if it is not a possible
     * number for its region.
     */
    fun normalize(raw: String, region: String = DEFAULT_REGION): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw BadRequestException("Phone number is required")

        val parsed = try {
            util.parse(trimmed, region)
        } catch (ex: NumberParseException) {
            throw BadRequestException("$raw is not a phone number we can read")
        }

        // isValidNumber checks the prefix against the real numbering plan, which a regex cannot
        // do. A number wrong by one digit belongs to somebody else.
        if (!util.isValidNumber(parsed)) {
            throw BadRequestException("$raw is not a valid phone number")
        }

        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    /** Normalises without throwing, for matching against data that is already stored. */
    fun normalizeOrNull(raw: String?, region: String = DEFAULT_REGION): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching { normalize(raw, region) }.getOrNull()
    }
}
