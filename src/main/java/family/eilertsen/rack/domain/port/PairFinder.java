package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.Companion;

import java.util.List;

/**
 * Which entries in a listing a thing belongs with — a charger and the device
 * it charges, a remote and its receiver — either way round.
 *
 * <p>The model is shown the rack rather than asked to guess words: the whole
 * listing fits in one prompt, and reading "5.1V 3A USB-C power supply" beside
 * "Raspberry Pi 4" is a matching job, where bridging "charger" to a word list
 * and searching it is a guess with heuristics stacked on it.
 */
public interface PairFinder {

    /**
     * @param subject the entry to place, rendered as one line
     * @param listing everything else, one line each, beginning with its reference
     * @return the references cited, with a reason each. Never null; empty when
     *         nothing belongs together or the model is unreachable.
     */
    List<Companion> find(String subject, List<String> listing);
}
