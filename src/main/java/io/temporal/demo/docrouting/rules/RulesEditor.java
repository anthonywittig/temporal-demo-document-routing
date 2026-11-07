package io.temporal.demo.docrouting.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.temporal.demo.docrouting.rules.RulesLoader.Rules;
import io.temporal.demo.docrouting.rules.RulesLoader.UnitRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the rules file for the demo page's rules card. It is just another editor of the same
 * file: the workflow still reads rules only through {@link RulesLoader}, so a page edit and a hand
 * edit behave identically.
 */
public final class RulesEditor {
  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** Thrown when an edit would not validate; nothing is written. */
  public static final class InvalidEditException extends RuntimeException {
    public InvalidEditException(String message) {
      super(message);
    }
  }

  private final Path rulesFile;
  private final RulesLoader loader;

  public RulesEditor(Path rulesFile, RulesLoader loader) {
    this.rulesFile = rulesFile;
    this.loader = loader;
  }

  /**
   * Applies new human-review thresholds to the rules in force and saves them as the next version.
   * Units not mentioned keep their threshold.
   */
  public synchronized Rules saveThresholds(Map<String, Long> thresholds) {
    Rules current = loader.current();
    Map<String, UnitRules> units = new LinkedHashMap<>(current.businessUnits());
    thresholds.forEach((unit, threshold) -> {
      if (!units.containsKey(unit)) throw new InvalidEditException("Unknown business unit " + unit);
      if (threshold == null) throw new InvalidEditException(unit + " needs a threshold");
      units.put(unit, new UnitRules(threshold));
    });
    Rules next = new Rules(current.version() + 1, units);
    List<String> problems = RulesLoader.validate(next);
    if (!problems.isEmpty()) throw new InvalidEditException(String.join("; ", problems));
    write(next);
    return loader.current();
  }

  /** Copies a file into place atomically, so the loader never reads a half-written file. */
  public static void replace(Path source, Path target) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private void write(Rules rules) {
    try {
      Path tmp = rulesFile.resolveSibling(rulesFile.getFileName() + ".tmp");
      Files.writeString(tmp, JSON.writeValueAsString(rules) + "\n");
      Files.move(tmp, rulesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new IllegalStateException("Could not write " + rulesFile + ": " + e.getMessage(), e);
    }
  }
}
