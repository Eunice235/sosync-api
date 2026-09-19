package com.sosync.service.incident

import com.sosync.common.ForbiddenException
import com.sosync.common.NotFoundException
import com.sosync.domain.Incident
import com.sosync.domain.IncidentStatus
import com.sosync.domain.Role
import com.sosync.persistence.EmergencyContactRepository
import com.sosync.persistence.FamilyMemberRepository
import com.sosync.persistence.IncidentRepository
import com.sosync.persistence.ResponderRepository
import com.sosync.persistence.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Why a viewer is allowed to see an incident. Returned so the API can label the relationship. */
enum class ViewerRelationship {
    REPORTER,
    TRUSTED_CONTACT,
    FAMILY_MEMBER,
    ASSIGNED_RESPONDER,
    /** A verified responder looking at an alert nobody has accepted yet. */
    AVAILABLE_RESPONDER,
    ADMIN,
}

/**
 * The only place that decides who may look at an incident and its location.
 *
 * Centralised deliberately. Location access is the sensitive capability in this system, and the
 * failure mode of scattering these checks across controllers is that one endpoint ends up
 * missing one, which is not a bug anybody notices until it matters.
 *
 * Six ways to qualify:
 *
 * - the **reporter**, always
 * - a **trusted contact**, matched on phone number against the reporter's contact list
 * - a **family member** of the reporter
 * - the **assigned responder**
 * - any **verified, available responder**, but only while the incident is still unclaimed, so
 *   they can read the alert they are being asked to accept
 * - an **admin**
 *
 * Note what is not here: a responder does not keep access to an incident they did not take. Once
 * somebody else accepts it, the rest lose sight of it.
 */
@Service
class IncidentAccessPolicy(
    private val incidentRepository: IncidentRepository,
    private val contactRepository: EmergencyContactRepository,
    private val responderRepository: ResponderRepository,
    private val familyMemberRepository: FamilyMemberRepository,
    private val userRepository: UserRepository,
) {

    /**
     * Loads the incident and establishes the caller's relationship to it, or throws.
     *
     * A caller with no relationship gets 404, not 403: confirming that an incident id exists
     * tells a stranger that somebody raised an emergency, which is itself information they are
     * not entitled to.
     */
    @Transactional(readOnly = true)
    fun requireViewable(incidentId: String, viewerId: String): Pair<Incident, ViewerRelationship> {
        val incident = incidentRepository.findById(incidentId).orElseThrow {
            NotFoundException("No such incident")
        }
        val relationship = relationshipOf(incident, viewerId)
            ?: throw NotFoundException("No such incident")
        return incident to relationship
    }

    /** Only the reporter may stand down their own emergency. */
    @Transactional(readOnly = true)
    fun requireReporter(incidentId: String, viewerId: String): Incident {
        val incident = incidentRepository.findById(incidentId).orElseThrow {
            NotFoundException("No such incident")
        }
        if (incident.userId != viewerId) {
            throw ForbiddenException("Only the person who raised this alert can do that")
        }
        return incident
    }

    @Transactional(readOnly = true)
    fun relationshipOf(incident: Incident, viewerId: String): ViewerRelationship? {
        if (incident.userId == viewerId) return ViewerRelationship.REPORTER

        val viewer = userRepository.findById(viewerId).orElse(null) ?: return null

        if (viewer.role == Role.ADMIN) return ViewerRelationship.ADMIN

        // Matched on phone number, which is why numbers are normalised to E.164 before they are
        // stored. A contact saved as 0712... and an account registered as +254712... are the
        // same person and must resolve to the same access.
        val nominatedBy = contactRepository.findAllByPhone(viewer.phone).map { it.userId }
        if (incident.userId in nominatedBy) return ViewerRelationship.TRUSTED_CONTACT

        if (sharesFamilyWith(viewerId, incident.userId)) return ViewerRelationship.FAMILY_MEMBER

        val responder = responderRepository.findByUserId(viewerId)
        if (responder != null) {
            if (incident.assignedResponderId == responder.id) {
                return ViewerRelationship.ASSIGNED_RESPONDER
            }
            // Unclaimed alerts are readable by anyone who could take them, and by nobody once
            // somebody has.
            if (incident.status == IncidentStatus.TRIGGERED && responder.isDispatchable) {
                return ViewerRelationship.AVAILABLE_RESPONDER
            }
        }

        return null
    }

    /**
     * Whether live location may be read.
     *
     * The reporter can always see their own trail. Everyone else loses access the moment the
     * incident closes: consent to being followed was consent for the duration of an emergency,
     * not permanently.
     */
    fun canViewLocation(
        incident: Incident,
        relationship: ViewerRelationship,
    ): Boolean = when {
        relationship == ViewerRelationship.REPORTER -> true
        relationship == ViewerRelationship.ADMIN -> true
        else -> incident.isOpen
    }

    private fun sharesFamilyWith(viewerId: String, reporterId: String): Boolean {
        val viewerFamilies = familyMemberRepository.findAllByUserId(viewerId)
            .map { it.familyId }
            .toSet()
        if (viewerFamilies.isEmpty()) return false

        return familyMemberRepository.findAllByUserId(reporterId)
            .any { it.familyId in viewerFamilies }
    }
}
