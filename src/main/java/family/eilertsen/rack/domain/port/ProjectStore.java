package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.ProjectId;

import java.util.List;

/**
 * One file per project, saved one at a time. Containers are saved all together
 * because there are five of them and they are configuration; a project is a
 * document that gets edited while other things are happening to it.
 */
public interface ProjectStore {

    List<Project> loadAll();

    void save(Project project);

    void delete(ProjectId id);
}
