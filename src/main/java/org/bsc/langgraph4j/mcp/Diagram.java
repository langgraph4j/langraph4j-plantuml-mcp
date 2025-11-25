package org.bsc.langgraph4j.mcp;

import java.util.List;


/**
 * Represents a diagram which contains various elements such as participants, relations, and containers.
 */
public interface Diagram {

    /**
     * Represents a participant in an event.
     *
     * @param name      the name of the participant
     * @param shape     the shape associated with the participant
     * @param description a brief description of the participant
     */
    record Participant (String name, String shape, String description) {
    }

    /**
     * Represents a relation between two entities with an optional description.
     *
     * @param source     the identifier of the source entity
     * @param target     the identifier of the target entity
     * @param description  an optional description of the relation
     */
    record Relation (String source, String target, String description) {
    }

    /**
     * Represents a container with a name, children, and a description.
     * 
     * @param name         the name of the container
     * @param children     a list of child containers or items
     * @param description  a brief description of the container
     */
    record Container(String name, List<String> children, String description) {
    }

    /**
     * Represents an element in a structured format.
     *
     * @param type        the type of the element
     * @param title       the title of the element
     * @param participants  a list of participants associated with the element
     * @param relations     a list of relations associated with the element
     * @param containers    a list of containers associated with the element
     * @param description a list of descriptions for the element
     */
    record Model(
        String type,
        String title,
        List<Participant> participants,
        List<Relation> relations,
        List<Container> containers,
        List<String> description
    ) {}

}