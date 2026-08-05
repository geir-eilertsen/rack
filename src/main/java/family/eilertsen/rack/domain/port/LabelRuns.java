package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.LabelRun;

import java.util.List;

/** The ledger of what each print run consumed of a physical sheet. */
public interface LabelRuns {

    void record(LabelRun run);

    /** Oldest first. */
    List<LabelRun> all();
}
