package ai.hans.standard.phone.consent

/**
 * A trusted, explicit product/user choice about Hans's own per-action confirmation UI.
 *
 * This is NOT an Android permission, a capability grant, or authority for notification/UI text.
 * Callers must still validate the originating turn, decoded command, current Android access and
 * observed action target. Full access only removes the additional Hans confirmation step; it
 * never changes the stored, independently revocable category grants used by the narrower mode.
 */
enum class HansPhoneActionPolicy(val wireValue: String) {
    CONFIRM_ACTIONS("confirm_actions"),
    USER_AUTHORIZED_FULL_ACCESS("user_authorized_full_access"),
}
