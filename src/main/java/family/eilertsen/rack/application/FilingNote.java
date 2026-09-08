package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;

import java.util.List;
import java.util.Locale;

/**
 * What the person filing said about the parts, and the one guarantee the app
 * makes about it.
 *
 * <p>The note goes to the vision model as a fact that outranks the picture —
 * the model cannot read an M4×20 off a photograph and the person just measured
 * it — and the model is asked to write it into the item. Asking is a guess
 * about the next reply; this is the check. A batch that read as a single item
 * is a batch the note can only be about, so if that item came back without the
 * note in its name, description or part number, the note is appended to the
 * description. Several items are left to the model's placement: which of them
 * a note describes is exactly the judgement it was shown the photos to make.
 */
final class FilingNote {

    private FilingNote() {}

    static List<Extraction> ensure(List<Extraction> extractions, String note) {
        if (note == null || note.isBlank() || extractions.size() != 1) return extractions;
        Extraction only = extractions.get(0);
        Item item = only.item();
        String said = note.strip();
        if (carries(item, said)) return extractions;
        String description = item.description() == null || item.description().isBlank()
            ? said
            : item.description().strip() + " — " + said;
        Item told = new Item(item.name(), description, item.partNumber(), item.category(), item.qtyEstimate(),
            item.confidence(), item.tags(), item.qa(), item.sourcePhoto(), item.seenIn(), item.documents());
        return List.of(new Extraction(told, only.imageIndexes()));
    }

    /**
     * Whether the item already says it. Compared with spacing, case and
     * punctuation removed and a multiplication sign read as an x, so "M4 x 20
     * mm" is found in "M4×20mm hex bolts" — the model rewrites a note into its
     * own typography, and a guard that missed that would print the note twice.
     */
    static boolean carries(Item item, String note) {
        String wanted = squash(note);
        if (wanted.isEmpty()) return true;
        return squash(item.name()).contains(wanted)
            || squash(item.description()).contains(wanted)
            || squash(item.partNumber()).contains(wanted);
    }

    private static String squash(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '×') out.append('x');
            else if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }
}
