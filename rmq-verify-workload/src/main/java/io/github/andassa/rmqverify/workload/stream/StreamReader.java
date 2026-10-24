package io.github.andassa.rmqverify.workload.stream;

import com.rabbitmq.stream.MessageHandler;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.workload.MessageCodec;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records every message read from a stream as a delivery whose tag is the stream offset. Streams
 * have no redelivered flag and no acknowledgement.
 */
final class StreamReader implements MessageHandler {

  private final String process;
  private final String stream;
  private final HistoryRecorder recorder;
  private final AtomicLong lastOffset = new AtomicLong(-1);
  private final AtomicLong count = new AtomicLong();

  StreamReader(String process, String stream, HistoryRecorder recorder) {
    this.process = process;
    this.stream = stream;
    this.recorder = recorder;
  }

  @Override
  public void handle(Context context, com.rabbitmq.stream.Message message) {
    byte[] body = message.getBodyAsBinary();
    long offset = context.offset();
    Value value =
        MessageCodec.decode(body)
            .<Value>map(m -> new Value.Delivery(m, false, offset))
            .orElseGet(
                () -> new Value.CorruptDelivery(stream, MessageCodec.digest(body), false, offset));
    recorder.observe(process, OpFunction.DELIVER, value);
    lastOffset.accumulateAndGet(offset, Math::max);
    count.incrementAndGet();
  }

  long lastOffset() {
    return lastOffset.get();
  }

  long count() {
    return count.get();
  }
}
