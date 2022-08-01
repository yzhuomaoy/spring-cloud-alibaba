package com.alibaba.cloud.examples.filter.factory.rewrite;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.CONNECT_TIMEOUT_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.containsEncodedParts;

import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.DelegatingServiceInstance;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;

@Component
public class EncryptDecryptComponent {

  private static final Log log = LogFactory.getLog(EncryptDecryptComponent.class);

  private static final String ENCRYPT_SUFIX = "/encrypt";

  private static final String DECRYPT_SUFIX = "/decrypt";

  private final LoadBalancerClient loadBalancer;

  private final HttpClient httpClient;

  public EncryptDecryptComponent(LoadBalancerClient loadBalancer, HttpClient httpClient) {
    this.loadBalancer = loadBalancer;
    this.httpClient = httpClient;
  }

  private boolean isExclude(List<String> excludeUris, String path) {
    return excludeUris.contains(path);
  }

  private URI constructEncDecRequestUrl(EncryptDecryptConfig config, String sufix) {
    URI uri = config.getEncDecUri();
    boolean encoded = containsEncodedParts(uri);
    URI mergedUrl = UriComponentsBuilder.fromUri(uri)
        .path(sufix)
        .build(encoded)
        .toUri();

    final ServiceInstance instance = choose(uri);
    String overrideScheme = instance.isSecure() ? "https" : "http";

    return loadBalancer.reconstructURI(
        new DelegatingServiceInstance(instance, overrideScheme), mergedUrl);
  }

  public Mono<String> decrypt(ServerWebExchange exchange, String body, EncryptDecryptConfig config) {
    String originalPath = exchange.getRequest().getURI().getPath();
    if (isExclude(config.getExcludeUris(), originalPath)) {
      return Mono.just(body);
    }

    URI requestUrl = constructEncDecRequestUrl(config, DECRYPT_SUFIX);
    return post2Service(requestUrl, exchange, body, config);
  }

  public Mono<String> encrypt(ServerWebExchange exchange, String body, EncryptDecryptConfig config) {
    String originalPath = exchange.getRequest().getURI().getPath();
    if (isExclude(config.getExcludeUris(), originalPath)) {
      return Mono.just(body);
    }

    URI requestUrl = constructEncDecRequestUrl(config, ENCRYPT_SUFIX);
    return post2Service(requestUrl, exchange, body, config);
  }

  static Integer getInteger(Object connectTimeoutAttr) {
    Integer connectTimeout;
    if (connectTimeoutAttr instanceof Integer) {
      connectTimeout = (Integer) connectTimeoutAttr;
    }
    else {
      connectTimeout = Integer.parseInt(connectTimeoutAttr.toString());
    }
    return connectTimeout;
  }

  private HttpClient getHttpClient(Route route, ServerWebExchange exchange) {
    Object connectTimeoutAttr = route.getMetadata().get(CONNECT_TIMEOUT_ATTR);
    if (connectTimeoutAttr != null) {
      Integer connectTimeout = getInteger(connectTimeoutAttr);
      return this.httpClient.tcpConfiguration((tcpClient) -> tcpClient
          .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout));
    }
    return httpClient;
  }

  private Mono<String> post2Service(URI requestUrl, ServerWebExchange exchange, String body, EncryptDecryptConfig config) {
    final HttpMethod method = HttpMethod.valueOf("POST");
    final String url = requestUrl.toASCIIString();

    HttpHeaders filtered = exchange.getRequest().getHeaders();

    final DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();
    filtered.forEach(httpHeaders::set);

    // set the original request path
    String originalPath = exchange.getRequest().getURI().getPath();
    httpHeaders.set("Original-Path", originalPath);
    httpHeaders.remove(HttpHeaders.CONTENT_LENGTH);

    // TODO: this causes a 'HTTP/1.1 411 Length Required' // on
    httpHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");

    Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);

    Duration responseTimeout = Duration.ofMillis(config.getTimeout());
    return getHttpClient(route, exchange)
        .headers(headers -> {
          headers.add(httpHeaders);
        })
        .request(method)
        .uri(url)
        .send(ByteBufFlux.fromString(Mono.just(body)))
        .responseContent()
        .aggregate()
        .asString()
        .timeout(responseTimeout, Mono.error(new TimeoutException("Response took longer than timeout: "+responseTimeout)))
        .onErrorMap(TimeoutException.class,
            th -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, th.getMessage(), th));

  }

  private ServiceInstance choose(URI uri) {
    return loadBalancer.choose(uri.getHost());
  }

}
