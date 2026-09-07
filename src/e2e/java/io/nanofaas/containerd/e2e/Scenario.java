package io.nanofaas.containerd.e2e;

import java.util.ArrayList;
import java.util.List;

/**
 * One named thing the libraries are asked to do, and whether they did it.
 *
 * <p>Scenarios report rather than throw, so one failure does not hide the results of everything
 * after it — the point of the run is to learn how much works, not to stop at the first thing that
 * does not.
 */
final class Scenario {

    private final String name;
    private final List<String> notes = new ArrayList<>();
    private String failure;

    Scenario(String name) {
        this.name = name;
    }

    /** Records something observed, which is printed whether or not the scenario passes. */
    void note(String detail) {
        notes.add(detail);
    }

    /** Records a failure, keeping the first: later ones are usually consequences of it. */
    void require(boolean condition, String description) {
        if (!condition && failure == null) {
            failure = description;
        }
    }

    void failed(String description) {
        if (failure == null) {
            failure = description;
        }
    }

    boolean passed() {
        return failure == null;
    }

    String render() {
        StringBuilder out = new StringBuilder();
        out.append(passed() ? "  PASS  " : "  FAIL  ").append(name).append('\n');
        for (String note : notes) {
            out.append("          ").append(note).append('\n');
        }
        if (failure != null) {
            out.append("          -> ").append(failure).append('\n');
        }
        return out.toString();
    }

    String name() {
        return name;
    }
}
