package madacode.tool;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class VisibleTools implements Iterable<Tool<?>> {

    private final List<Tool<?>> tools;

    public VisibleTools(Collection<Tool<?>> tools) {
        this.tools = List.copyOf(tools == null ? List.<Tool<?>>of() : tools);
    }

    public List<Tool<?>> tools() {
        return tools;
    }

    public Stream<Tool<?>> stream() {
        return tools.stream();
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    public Set<String> names() {
        return tools.stream()
                .map(Tool::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    @Override
    public Iterator<Tool<?>> iterator() {
        return tools.iterator();
    }
}
