package common;

import java.util.Set;

import common.parser.Program;

public interface Evaluator {

  FactsSet evaluate(Program program, FactsSet facts, Set<String> queryRelations) throws Exception;

  default void debug(Program program, FactsSet facts, Set<String> queryRelations) throws Exception {
    System.err.println("warning: " + this.getClass() + " does not provide debug information");
  }
}