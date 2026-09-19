package com.sosync.service.notification

import com.sosync.config.SosyncProperties
import com.sosync.domain.DeliveryStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

data class DeliveryOutcome(
    val status: DeliveryStatus,
    val failureReason: String? = null,
)

/**
 * The fallback channel, and the one that matters most.
 *
 * A safety tool that only works over mobile data is a safety tool that stops working in exactly
 * the places people need it. SMS reaches a feature phone, reaches a contact who has never
 * installed the app, and reaches someone whose data bundle ran out.
 */
interface SmsGateway {
    fun send(toPhoneE164: String, body: String): DeliveryOutcome
}

/** Push to a registered device. Fast when it works, and silently unreliable when it does not. */
interface PushGateway {
    fun send(userId: String, title: String, body: String): DeliveryOutcome
}

/**
 * Records what would have been sent, and says so.
 *
 * The prototype ships with no SMS or FCM credentials. Every delivery through here is stored
 * with status [DeliveryStatus.SIMULATED] and returned over the API, so the demo can show the
 * exact message each recipient would have received without anyone being told a text was sent
 * that was not.
 *
 * Replacing these with a real gateway is a single implementation each, plus credentials:
 * Africa's Talking or Twilio for SMS, Firebase Cloud Messaging for push. The call sites do not
 * change.
 */
@Component
class SimulatedSmsGateway(private val properties: SosyncProperties) : SmsGateway {
    override fun send(toPhoneE164: String, body: String): DeliveryOutcome {
        if (!properties.notifications.simulate) {
            return DeliveryOutcome(
                DeliveryStatus.FAILED,
                "No SMS provider configured. Set sosync.notifications.simulate=true or " +
                    "supply a real SmsGateway implementation.",
            )
        }
        log.info { "[SIMULATED SMS] to=$toPhoneE164 body=$body" }
        return DeliveryOutcome(DeliveryStatus.SIMULATED)
    }
}

@Component
class SimulatedPushGateway(private val properties: SosyncProperties) : PushGateway {
    override fun send(userId: String, title: String, body: String): DeliveryOutcome {
        if (!properties.notifications.simulate) {
            return DeliveryOutcome(
                DeliveryStatus.FAILED,
                "No push provider configured. Set sosync.notifications.simulate=true or " +
                    "supply a real PushGateway implementation.",
            )
        }
        log.info { "[SIMULATED PUSH] user=$userId title=$title body=$body" }
        return DeliveryOutcome(DeliveryStatus.SIMULATED)
    }
}
