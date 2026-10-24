package io.github.andassa.rmqverify.model;

/**
 * Identity of a published message, as carried in its payload.
 *
 * @param runId identifier of the run that published the message
 * @param publisherId logical publisher
 * @param queue destination queue
 * @param sequence position of the message in the stream of its publisher for this queue
 * @param uuid globally unique identifier of the message
 */
public record Message(String runId, String publisherId, String queue, long sequence, String uuid) {

  /** Validates every component. */
  public Message {
    Checks.name(runId, "runId");
    Checks.name(publisherId, "publisherId");
    Checks.name(queue, "queue");
    Checks.nonNegative(sequence, "sequence");
    Checks.name(uuid, "uuid");
  }
}
