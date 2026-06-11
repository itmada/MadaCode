/**
 * Application-wide event infrastructure.
 *
 * <p>There are two distinct event systems in MadaCode:
 *
 * <ul>
 *   <li><strong>Session-level events</strong> such as transcript rendering,
 *   turn history, and UI replay flow through {@code SessionListener} and
 *   {@code MetaEvent} inside a {@code ConversationSession}.</li>
 *   <li><strong>Application-level events</strong> such as diagnostics, audit
 *   records, fatal failures, and user-visible bootstrap/runtime notices flow
 *   through {@link madacode.events.AppEventPublisher}.</li>
 * </ul>
 *
 * <p>The bootstrap package is the composition root for application-level event
 * publishers. New runtime code should prefer explicit
 * {@link madacode.events.AppEventPublisher} injection. The static
 * {@link madacode.events.AppEvents} facade remains only as a compatibility
 * boundary for early-bootstrap fallback behavior and legacy callers that have
 * not yet been rewired.
 */
package madacode.events;
