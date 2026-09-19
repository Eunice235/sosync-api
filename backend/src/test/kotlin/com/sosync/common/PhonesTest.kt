package com.sosync.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Phone normalisation.
 *
 * This carries more weight than it looks. The number is the login identifier, the SMS address,
 * and the link between a trusted contact and the account that contact belongs to. If
 * `0712345678` and `+254712345678` are stored as different strings, a registered contact
 * silently stops receiving in-app alerts and nobody finds out until an emergency.
 */
class PhonesTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "+254712345678",
            "0712345678",
            "254712345678",
            "+254 712 345 678",
            "0712-345-678",
            " +254712345678 ",
        ],
    )
    @DisplayName("every readable form of one Kenyan number normalises to the same E.164 string")
    fun equivalentFormsNormaliseIdentically(input: String) {
        assertThat(Phones.normalize(input)).isEqualTo("+254712345678")
    }

    @Test
    @DisplayName("an international number keeps its own country code")
    fun internationalNumbersArePreserved() {
        // A number typed with a + is parsed on its own terms, so contacts abroad still work.
        assertThat(Phones.normalize("+447911123456")).isEqualTo("+447911123456")
        assertThat(Phones.normalize("+27821234567")).isEqualTo("+27821234567")
    }

    @Test
    @DisplayName("the default region only applies to numbers typed without a country code")
    fun defaultRegionAppliesToLocalFormatOnly() {
        assertThat(Phones.normalize("0712345678", region = "KE")).isEqualTo("+254712345678")
        assertThat(Phones.normalize("+254712345678", region = "GB")).isEqualTo("+254712345678")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "abcdefg", "12", "0712345", "not a number"])
    @DisplayName("anything that is not a real number is refused, rather than stored as typed")
    fun invalidNumbersAreRejected(input: String) {
        assertThatThrownBy { Phones.normalize(input) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    @DisplayName("a number that is one digit wrong is refused, because it belongs to somebody else")
    fun wrongLengthIsRejected() {
        // This is what libphonenumber buys over a regex: it knows the real numbering plan.
        assertThatThrownBy { Phones.normalize("+2547123456789012") }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    @DisplayName("normalizeOrNull answers null instead of throwing, for matching stored data")
    fun normalizeOrNullIsLenient() {
        assertThat(Phones.normalizeOrNull("0712345678")).isEqualTo("+254712345678")
        assertThat(Phones.normalizeOrNull("rubbish")).isNull()
        assertThat(Phones.normalizeOrNull(null)).isNull()
        assertThat(Phones.normalizeOrNull("")).isNull()
    }
}
