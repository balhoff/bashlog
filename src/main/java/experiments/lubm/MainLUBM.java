package experiments.lubm;

import common.parser.Program;
import flinklog.FactsSet;
import flinklog.FlinkEvaluator;
import flinklog.SimpleFactsSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * Reuses the data from https://www.mat.unical.it/kr2012/
 */
public class MainLUBM {
  public static void main(String[] args) throws IOException {
    Program program = Program.merge(Program.loadFile("data/lubm/tbox.txt"), Program.loadFile("data/lubm/queries.txt"));
    FactsSet facts = loadFacts();
    for (int i = 1; i <= 14; i++) {
      String relation = "query" + i + "/1";
      long count = (new FlinkEvaluator()).evaluate(program, facts, Collections.singleton(relation)).getByRelation(relation).count();
      System.out.println(relation + " has " + count + " results");
    }
  }

  private static FactsSet loadFacts() throws IOException {
    SimpleFactsSet factsSet = new SimpleFactsSet();
    Files.newDirectoryStream(Paths.get("data/lubm/abox-10")).forEach(file -> {
      try {
        factsSet.loadFile(file);
      } catch (IOException e) {
        System.err.println(e.getMessage());
      }
    });
    return factsSet;
  }
}