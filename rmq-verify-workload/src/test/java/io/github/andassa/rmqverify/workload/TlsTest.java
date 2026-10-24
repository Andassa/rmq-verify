package io.github.andassa.rmqverify.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.workload.amqp.RabbitConnector;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsTest {

  private static Path testCa() throws URISyntaxException {
    return Path.of(TlsTest.class.getResource("/tls/test-ca.crt").toURI());
  }

  @Test
  void buildsBothContextsFromACaFile() throws Exception {
    Tls tls = Tls.fromCa(testCa());
    assertThat(tls.amqp().getProtocol()).isEqualTo("TLS");
    assertThat(tls.stream().isClient()).isTrue();
    BrokerAccess access =
        new BrokerAccess("127.0.0.1", List.of(1), Map.of(), "rmqv", "u", "p", Optional.of(tls));
    assertThat(access.toString()).contains("tls=true");
    assertThat(new RabbitConnector(access, 1)).isNotNull();
  }

  @Test
  void rejectsMissingEmptyAndInvalidFiles(@TempDir Path dir) throws Exception {
    assertThatThrownBy(() -> Tls.fromCa(dir.resolve("absent.pem")))
        .isInstanceOf(WorkloadException.class)
        .hasMessageContaining("cannot read CA certificates");
    Path empty = Files.writeString(dir.resolve("empty.pem"), "");
    assertThatThrownBy(() -> Tls.fromCa(empty)).hasMessageContaining("no certificate");
    Path garbage =
        Files.writeString(dir.resolve("garbage.pem"), "-----BEGIN CERTIFICATE-----\nnope\n");
    assertThatThrownBy(() -> Tls.fromCa(garbage)).isInstanceOf(WorkloadException.class);
  }
}
