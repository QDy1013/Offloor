package com.nopalou;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One foreground visit: membership uses card IDs, never message time or AI revision time. */
final class NewCardSession {
    private final Set<String> baseline;
    private final Set<String> seen;
    private boolean initialized;

    NewCardSession(Set<String> previouslySeen) {
        initialized = previouslySeen != null;
        baseline = previouslySeen == null ? new HashSet<>() : new HashSet<>(previouslySeen);
        seen = new HashSet<>(baseline);
    }

    /** First upgrade establishes a baseline rather than labelling all historical cards as new. */
    boolean observe(Collection<String> currentIds) {
        boolean changed = !initialized;
        for (String id : currentIds) {
            if (id == null || id.isEmpty()) continue;
            if (!initialized) baseline.add(id);
            changed |= seen.add(id);
        }
        initialized = true;
        return changed;
    }

    boolean isNew(String id) {
        return initialized && id != null && !id.isEmpty() && !baseline.contains(id);
    }

    /** Filter before applying the page limit, keeping Engine's existing ranking and search. */
    List<Engine.Card> queryCards(Engine engine, String keywords, Engine.CardScope scope, int limit, long now) {
        List<Engine.Card> result = new ArrayList<>();
        if (limit <= 0) return result;
        for (Engine.Card card : engine.queryCards(keywords, scope, Integer.MAX_VALUE, now)) {
            if (isNew(card.id)) {
                result.add(card);
                if (result.size() == limit) break;
            }
        }
        return result;
    }

    int count(Collection<Engine.Card> cards) {
        int count = 0;
        for (Engine.Card card : cards) if (isNew(card.id)) count++;
        return count;
    }

    Set<String> seenIds() { return new HashSet<>(seen); }
}
