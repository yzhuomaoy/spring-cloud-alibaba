package com.alibaba.cloud.examples.filter.factory.rewrite;

import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.ForwardRoutingFilter;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class DecryptRequestBodyGatewayFilterFactory extends AbstractGatewayFilterFactory<EncryptDecryptConfig>  {

  private static final String NAME = "DecryptRequestBody";

  private static final Log log = LogFactory.getLog(ForwardRoutingFilter.class);

  private final EncryptDecryptComponent encryptDecryptComponent;

  public DecryptRequestBodyGatewayFilterFactory(EncryptDecryptComponent encryptDecryptComponent) {
    super(EncryptDecryptConfig.class);
    this.encryptDecryptComponent = encryptDecryptComponent;
  }

//  @Override
//  public GatewayFilter apply(EncryptDecryptConfig config) {
//    return (exchange, chain) -> {
//      if (exchange.getRequest().getMethod() == HttpMethod.POST) {
//        return DataBufferUtils.join(exchange.getRequest().getBody()).flatMap(dataBuffer -> {
//          ServerHttpRequest mutatedHttpRequest = getServerHttpRequest(exchange, dataBuffer, config);
//          return chain.filter(exchange.mutate().request(mutatedHttpRequest).response(exchange.getResponse()).build());
//        }).switchIfEmpty(chain.filter(exchange));
//      }
//
//      return chain.filter(exchange);
//    };
//  }

  @Override
  public GatewayFilter apply(EncryptDecryptConfig config) {
    return (exchange, chain) -> {
      if (exchange.getRequest().getMethod() != HttpMethod.POST) {
        return chain.filter(exchange);
      }

      ServerRequest serverRequest = ServerRequest.create(exchange,
          HandlerStrategies.withDefaults().messageReaders());

      Mono<String> modifiedBody = serverRequest.bodyToMono(String.class)
          .flatMap(originalBody -> encryptDecryptComponent.decrypt(exchange, originalBody, config))
          .switchIfEmpty(Mono.empty());

      HttpHeaders headers = new HttpHeaders();
      headers.putAll(exchange.getRequest().getHeaders());
      headers.remove(HttpHeaders.CONTENT_LENGTH);

      CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, headers);
      return BodyInserters.fromPublisher(modifiedBody, String.class)
          .insert(outputMessage, new BodyInserterContext())
          .then(Mono.defer(() -> {
            ServerHttpRequest decorator = decorate(exchange, headers, outputMessage);
            return chain.filter(exchange.mutate().request(decorator).build());
          }))
          .switchIfEmpty(chain.filter(exchange))
          .onErrorResume((Function<Throwable, Mono<Void>>) throwable ->
              release(exchange, outputMessage, throwable));
    };
  }

  protected Mono<Void> release(ServerWebExchange exchange, CachedBodyOutputMessage outputMessage, Throwable throwable) {
    if (outputMessage.isCached()) {
      return outputMessage.getBody().map(DataBufferUtils::release)
          .then(Mono.error(throwable));
    }
    return Mono.error(throwable);
  }

  ServerHttpRequestDecorator decorate(ServerWebExchange exchange, HttpHeaders headers,
      CachedBodyOutputMessage outputMessage) {
    return new ServerHttpRequestDecorator(exchange.getRequest()) {
      @Override
      public HttpHeaders getHeaders() {
        long contentLength = headers.getContentLength();
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.putAll(headers);
        if (contentLength > 0) {
          httpHeaders.setContentLength(contentLength);
        }
        else {
          // TODO: this causes a 'HTTP/1.1 411 Length Required' // on
          // httpbin.org
          httpHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
        }
        return httpHeaders;
      }

      @Override
      public Flux<DataBuffer> getBody() {
        return outputMessage.getBody();
      }
    };
  }

//  private ServerHttpRequest getServerHttpRequest(ServerWebExchange exchange, DataBuffer dataBuffer, EncryptDecryptConfig config) {
//    DataBufferUtils.retain(dataBuffer);
//    Flux<DataBuffer> cachedFlux = Flux.defer(() -> Flux.just(dataBuffer.slice(0, dataBuffer.readableByteCount())));
//
//    String body = toRaw(cachedFlux);
//    String decryptedBody = encryptDecryptComponent.decrypt(exchange, body, config);
//    byte[] decryptedBodyBytes = decryptedBody.getBytes(StandardCharsets.UTF_8);
//
//    return new ServerHttpRequestDecorator(exchange.getRequest()) {
//
//      @Override
//      public HttpHeaders getHeaders(){
//        HttpHeaders httpHeaders = new HttpHeaders();
//        httpHeaders.putAll(exchange.getRequest().getHeaders());
//        if (decryptedBody.length() > 0) {
//          httpHeaders.setContentLength(decryptedBody.length());
//        }
//        return httpHeaders;
//      }
//
//
//      @Override
//      public Flux<DataBuffer> getBody() {
//        return Flux.just(body).
//            map(s -> new DefaultDataBufferFactory().wrap(decryptedBodyBytes));
//      }
//    };
//  }

//  private static String toRaw(Flux<DataBuffer> body) {
//    AtomicReference<String> rawRef = new AtomicReference<>();
//    body.subscribe(buffer -> {
//      byte[] bytes = new byte[buffer.readableByteCount()];
//      buffer.read(bytes);
//      DataBufferUtils.release(buffer);
//      rawRef.set(Strings.fromUTF8ByteArray(bytes));
//    });
//    return rawRef.get();
//  }

  @Override
  public String name() {
    return NAME;
  }


  public class CachedBodyOutputMessage implements ReactiveHttpOutputMessage {

    private final DataBufferFactory bufferFactory;

    private final HttpHeaders httpHeaders;

    private boolean cached = false;

    private Flux<DataBuffer> body = Flux.error(new IllegalStateException(
        "The body is not set. " + "Did handling complete with success?"));

    public CachedBodyOutputMessage(ServerWebExchange exchange, HttpHeaders httpHeaders) {
      this.bufferFactory = exchange.getResponse().bufferFactory();
      this.httpHeaders = httpHeaders;
    }

    @Override
    public void beforeCommit(Supplier<? extends Mono<Void>> action) {

    }

    @Override
    public boolean isCommitted() {
      return false;
    }

    boolean isCached() {
      return this.cached;
    }

    @Override
    public HttpHeaders getHeaders() {
      return this.httpHeaders;
    }

    @Override
    public DataBufferFactory bufferFactory() {
      return this.bufferFactory;
    }

    /**
     * Return the request body, or an error stream if the body was never set or when.
     * @return body as {@link Flux}
     */
    public Flux<DataBuffer> getBody() {
      return this.body;
    }

    @Deprecated
    public void setWriteHandler(Function<Flux<DataBuffer>, Mono<Void>> writeHandler) {

    }

    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
      this.body = Flux.from(body);
      this.cached = true;
      return Mono.empty();
    }

    @Override
    public Mono<Void> writeAndFlushWith(
        Publisher<? extends Publisher<? extends DataBuffer>> body) {
      return writeWith(Flux.from(body).flatMap(p -> p));
    }

    @Override
    public Mono<Void> setComplete() {
      return writeWith(Flux.empty());
    }

  }
}
