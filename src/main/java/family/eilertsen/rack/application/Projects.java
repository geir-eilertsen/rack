package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.ProjectId;
import family.eilertsen.rack.domain.port.ProjectStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every project, in memory, with the file as the record. Same shape as
 * {@link ContainerRegistry} — there are a handful of them and they are read on
 * every page that mentions them.
 */
@Service
public class Projects {

    private final ProjectStore store;
    private final Map<ProjectId, Project> byId = new ConcurrentHashMap<>();

    public Projects(ProjectStore store) {
        this.store = store;
    }

    @PostConstruct
    public void load() {
        for (Project p : store.loadAll()) byId.put(p.id(), p);
    }

    /** Open projects first, then by most recently touched: what you came to see. */
    public List<Project> all() {
        return byId.values().stream()
            .sorted(Comparator.comparing((Project p) -> !p.open())
                .thenComparing(Project::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    public Optional<Project> get(ProjectId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized void save(Project project) {
        byId.put(project.id(), project);
        store.save(project);
    }

    public synchronized void remove(ProjectId id) {
        byId.remove(id);
        store.delete(id);
    }

    /** A free id near the one asked for: "quad-606", then "quad-606-2". */
    public synchronized ProjectId freeId(String name) {
        ProjectId wanted = ProjectId.from(name);
        if (!byId.containsKey(wanted)) return wanted;
        for (int n = 2; n < 1000; n++) {
            String suffix = "-" + n;
            String base = wanted.value();
            // Keep it inside the 48-character limit rather than failing on a long name.
            if (base.length() + suffix.length() > 48) base = base.substring(0, 48 - suffix.length());
            ProjectId candidate = new ProjectId(base.replaceAll("-+$", "") + suffix);
            if (!byId.containsKey(candidate)) return candidate;
        }
        throw new IllegalStateException("No free id near " + wanted.value());
    }
}
