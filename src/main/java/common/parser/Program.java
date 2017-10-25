package common.parser;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javatools.filehandlers.FileUtils;

/**
 * Parses datalog
 * 
 * current grammar:
 *   program ::= rule*
 *   rule ::= compound_term :- compound_term+ '.' | compound_term '.'
 *   compound_term ::= atom '(' term* ')'
 *   term ::= variable | atom | constant
 *   variable ::= name_starting_with_uppercase
 *   atom ::= name_starting_with_lowercase
 *   constant ::= number | string_with_apostrophes
 * 
 * @author Thomas Rebele
 */
public class Program implements Parseable {

  private List<Rule> rules = new ArrayList<Rule>();

  private Map<String, List<Rule>> relationToRules = new HashMap<>();

  public static Program loadFile(String path) throws IOException {
    File f = new File(path).getAbsoluteFile();
    String content = FileUtils.getFileContent(f);
    return read(new ParserReader(content));
  }

  public static Program read(ParserReader pr) {
    Program p = new Program();
    Rule r;
    do {
      pr.skipComments();
      r = Rule.read(pr);
      if (r != null) {
        p.addRule(r);
      }
    } while (r != null);
    return p;
  }

  public Program copy() {
    Program result = new Program();
    this.rules.forEach(result::addRule);
    return result;
  }

  public void addRule(Rule rule) {
    rules.add(rule);
    relationToRules.computeIfAbsent(rule.head.getRelation(), k -> new ArrayList<>()).add(rule);
  }

  public void addRules(Program p) {
    p.rules().forEach(this::addRule);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    rules.forEach(r -> {
      sb.append(r.toString());
      sb.append("\n");
    });
    return sb.toString();
  }

  public List<Rule> rules() {
    return rules;
  }

  public Set<String> outputRelations() {
    return relationToRules.keySet();
  }

  public Set<String> allRelations() {
    return Stream.concat(outputRelations().stream(), rules.stream().flatMap(r -> r.getDependencies().stream())).collect(Collectors.toSet());
  }

  public List<Rule> rulesForRelation(String relation) {
    return relationToRules.getOrDefault(relation, Collections.emptyList());
  }

  public boolean isRecursive(Rule rule, Set<String> ignoredRelations) {
    return rule.body.stream()
            .map(CompoundTerm::getRelation)
            .filter(rel -> !ignoredRelations.contains(rel))
            .anyMatch(rel -> hasAncestor(rel, rule.head.getRelation(), ignoredRelations));
  }

  public boolean hasAncestor(String relation, String ancestor) {
    return hasAncestor(relation, ancestor, Collections.emptySet());
  }

  public boolean hasAncestor(String relation, String ancestor, Set<String> ignoredRelation) {
    if (relation.equals(ancestor)) {
      return true;
    }
    Set<String> parents = rulesForRelation(relation).stream()
            .flatMap(t -> t.body.stream())
            .map(CompoundTerm::getRelation)
            .filter(rel -> !ignoredRelation.contains(rel))
            .collect(Collectors.toSet());
    Set<String> newIgnoredRelations = new HashSet<>(ignoredRelation);
    newIgnoredRelations.addAll(parents);
    return parents.stream().anyMatch(rel -> hasAncestor(rel, ancestor, newIgnoredRelations));
  }

  public static Program merge(Program p1, Program p2) {
    Program result = new Program();
    p1.rules().forEach(result::addRule);
    p2.rules().forEach(result::addRule);
    return result;
  }

  public static void main(String[] args) throws IOException {
    Program p = loadFile("data/rules.y4");
    System.out.println(p.toString());
  }

}
