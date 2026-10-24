package io.github.andassa.rmqverify.workload;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Client TLS material trusting one CA (the test CA of {@code deploy/scripts/gen-test-certs.sh}).
 * Server certificates and host names are verified; nothing is trusted blindly.
 *
 * @param amqp JSSE context for the AMQP client
 * @param stream Netty context for the stream client
 */
public record Tls(SSLContext amqp, SslContext stream) {

  /**
   * Loads the CA certificates of a PEM file into fresh trust stores.
   *
   * @param caPem PEM file holding one or more CA certificates
   * @return contexts trusting only those certificates
   * @throws WorkloadException if the file cannot be read or holds no certificate
   */
  public static Tls fromCa(Path caPem) throws WorkloadException {
    TrustManagerFactory trust = trustManagers(caPem);
    try {
      SSLContext amqp = SSLContext.getInstance("TLS");
      amqp.init(null, trust.getTrustManagers(), null);
      return new Tls(amqp, SslContextBuilder.forClient().trustManager(trust).build());
    } catch (GeneralSecurityException | IOException e) {
      throw new WorkloadException("cannot build TLS contexts from " + caPem, e);
    }
  }

  private static TrustManagerFactory trustManagers(Path caPem) throws WorkloadException {
    try (InputStream in = Files.newInputStream(caPem)) {
      Collection<? extends Certificate> certificates =
          CertificateFactory.getInstance("X.509").generateCertificates(in);
      if (certificates.isEmpty()) {
        throw new WorkloadException("no certificate in " + caPem);
      }
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      int i = 0;
      for (Certificate certificate : certificates) {
        store.setCertificateEntry("ca-" + i++, certificate);
      }
      TrustManagerFactory factory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(store);
      return factory;
    } catch (IOException | GeneralSecurityException e) {
      throw new WorkloadException("cannot read CA certificates from " + caPem, e);
    }
  }
}
