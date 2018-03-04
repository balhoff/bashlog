package common.parser;

import java.util.*;

/**
 * A rule of the form rel(X,Y) :~ cat abc.txt !other/2
 * Where other/2 is another predicate.
 * Relations need to be prefixed by " !"
 */
public class BashRule extends Rule {

  /** 
   * Constructor 
   * @param head
   * @param command bash command
   * @param relations used in bash command, in the order they appear in the command
   * @param commandParts list of strings that surround the "!abc"
   */
  public BashRule(CompoundTerm head, String command, List<String> relations, List<String> commandParts) {
    super(head, new ArrayList<>());
    this.relations = relations;
    this.command = command;
    this.commandParts = commandParts;
  }

  public String command;

  public List<String> commandParts;
  
  public List<String> relations;

  public static BashRule read(ParserReader pr, Set<String> supportedFeatures, CompoundTerm head) {

    pr.skipComments();
    String command = pr.readLine().trim();

    List<String> relations = new ArrayList<>(), commandParts = new ArrayList<>();
    int start = -1, prev = 0;
    while ((start = command.indexOf(" !", start + 1)) > 0) {
      commandParts.add(command.substring(prev, start + 1));

      ParserReader prName = new ParserReader(command, start + 2);
      String name = prName.readName();

      if (prName.consume("/") != null) {
        name += "/" + prName.readInteger();
      }

      relations.add(name);

      start = prName.pos();
      prev = start;
    }
    commandParts.add(command.substring(prev));

    return new BashRule(head, command, relations, commandParts);
  }

  @Override
  public String toString() {
    StringBuilder b = new StringBuilder();
    b.append(head).append(" :- ").append(command);
    return b.toString();
  }

  public static void main(String[] args) {
    ParserReader pr = new ParserReader("cat !abc xyz !def 123");
    BashRule br = read(pr, new HashSet<>(), new CompoundTerm("abc"));
    System.out.println(br.relations);
    System.out.println(br.commandParts);
  }
}