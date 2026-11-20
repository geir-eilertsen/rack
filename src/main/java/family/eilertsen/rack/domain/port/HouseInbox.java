package family.eilertsen.rack.domain.port;

/**
 * A message on somebody's phone. Vaier's app listens on one inbox per person,
 * so the person is named the way a phone names its owner — Vaier's short id
 * for them ("geir") — not by device.
 */
public interface HouseInbox {

    /** False when there is no way to reach a phone at all, so nothing is ever tried. */
    boolean available();

    /** One line for {@code owner}'s phones; false when it could not be handed over. */
    boolean tell(String owner, String text);
}
