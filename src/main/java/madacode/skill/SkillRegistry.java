package madacode.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SkillRegistry {

    private final SkillStateStore stateStore;
    private final SkillLoader[] loaders;
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillRegistry(SkillStateStore stateStore, SkillLoader... loaders) {
        this.stateStore = stateStore;
        this.loaders = loaders;
    }

    public synchronized void reload() {
        skills.clear();
        for (SkillLoader loader : loaders) {
            for (Skill s : loader.load()) {
                skills.put(s.name(), s);       // last loader wins
            }
        }
    }

    public synchronized List<Skill> all() {
        return List.copyOf(skills.values());
    }

    public synchronized List<Skill> enabled() {
        return skills.values().stream()
                .filter(s -> !stateStore.isDisabled(s.name()))
                .toList();
    }

    public synchronized Optional<Skill> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        Skill s = skills.get(name);
        if (s == null || stateStore.isDisabled(name)) return Optional.empty();
        return Optional.of(s);
    }

    public synchronized List<Skill> allIncludingDisabled() {
        return new ArrayList<>(skills.values());
    }

    public SkillStateStore stateStore() { return stateStore; }
}
