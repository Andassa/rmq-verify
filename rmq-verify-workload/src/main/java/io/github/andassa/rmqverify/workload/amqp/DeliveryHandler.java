package io.github.andassa.rmqverify.workload.amqp;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.workload.MessageCodec;
import java.io.IOException;

/** Records a delivery, then acknowledges it and records the outcome of the acknowledgement. */
final class DeliveryHandler {

  private final String process;
  private final HistoryRecorder recorder;

  DeliveryHandler(String process, HistoryRecorder recorder) {
    this.process = process;
    this.recorder = recorder;
  }

  void handle(Channel channel, String queue, byte[] body, long deliveryTag, boolean redelivered) {
    Value value =
        MessageCodec.decode(body)
            .<Value>map(m -> new Value.Delivery(m, redelivered, deliveryTag))
            .orElseGet(
                () ->
                    new Value.CorruptDelivery(
                        queue, MessageCodec.digest(body), redelivered, deliveryTag));
    recorder.observe(process, OpFunction.DELIVER, value);
    recorder.invoke(process, OpFunction.ACK, value);
    try {
      channel.basicAck(deliveryTag, false);
      recorder.complete(process, OpType.OK, OpFunction.ACK, value, "");
    } catch (IOException | ShutdownSignalException e) {
      recorder.complete(process, OpType.INFO, OpFunction.ACK, value, e.getClass().getSimpleName());
    }
  }
}
