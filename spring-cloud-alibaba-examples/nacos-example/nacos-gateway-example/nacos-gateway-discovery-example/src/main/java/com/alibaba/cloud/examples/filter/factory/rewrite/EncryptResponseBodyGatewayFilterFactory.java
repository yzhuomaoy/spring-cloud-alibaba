package com.alibaba.cloud.examples.filter.factory.rewrite;

import static java.util.function.Function.identity;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.ForwardRoutingFilter;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyDecoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class EncryptResponseBodyGatewayFilterFactory extends AbstractGatewayFilterFactory<EncryptDecryptConfig>  {

  private static final String NAME = "EncryptResponseBody";

  private static final Log log = LogFactory.getLog(ForwardRoutingFilter.class);

  private final Map<String, MessageBodyDecoder> messageBodyDecoders;

  private final Map<String, MessageBodyEncoder> messageBodyEncoders;

  private final EncryptDecryptComponent encryptDecryptComponent;

  public EncryptResponseBodyGatewayFilterFactory(
      Set<MessageBodyDecoder> messageBodyDecoders,
      Set<MessageBodyEncoder> messageBodyEncoders,
      EncryptDecryptComponent encryptDecryptComponent) {
    super(EncryptDecryptConfig.class);
    this.messageBodyDecoders = messageBodyDecoders.stream()
        .collect(Collectors.toMap(MessageBodyDecoder::encodingType, identity()));
    this.messageBodyEncoders = messageBodyEncoders.stream()
        .collect(Collectors.toMap(MessageBodyEncoder::encodingType, identity()));
    this.encryptDecryptComponent = encryptDecryptComponent;
  }

  @Override
  public GatewayFilter apply(EncryptDecryptConfig config) {
    ModifyResponseGatewayFilter gatewayFilter = new ModifyResponseGatewayFilter(config);
    return gatewayFilter;
  }

  public class ModifyResponseGatewayFilter implements GatewayFilter, Ordered {

    private final EncryptDecryptConfig config;

    public ModifyResponseGatewayFilter(EncryptDecryptConfig config) {
      this.config = config;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
      return chain.filter(exchange.mutate()
          .response(new ModifiedServerHttpResponse(exchange, config)).build());
    }

    @SuppressWarnings("unchecked")
    @Deprecated
    ServerHttpResponse decorate(ServerWebExchange exchange) {
      return new ModifiedServerHttpResponse(exchange, config);
    }

    @Override
    public int getOrder() {
      return NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;
    }

  }

  protected class ModifiedServerHttpResponse extends ServerHttpResponseDecorator {

    private final ServerWebExchange exchange;

    private final EncryptDecryptConfig config;

    public ModifiedServerHttpResponse(ServerWebExchange exchange, EncryptDecryptConfig config) {
      super(exchange.getResponse());
      this.exchange = exchange;
      this.config = config;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
      String originalResponseContentType = exchange
          .getAttribute(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR);
      HttpHeaders httpHeaders = new HttpHeaders();
      // explicitly add it in this way instead of
      // 'httpHeaders.setContentType(originalResponseContentType)'
      // this will prevent exception in case of using non-standard media
      // types like "Content-Type: image"
      httpHeaders.add(HttpHeaders.CONTENT_TYPE, originalResponseContentType);

      ClientResponse clientResponse = prepareClientResponse(body, httpHeaders);

      // TODO: flux or mono
      Mono<String> modifiedBody = extractBody(exchange, clientResponse)
          .flatMap( originalBody -> encryptDecryptComponent.encrypt(exchange, originalBody, config))
          .switchIfEmpty(Mono.empty());

      CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange,
          exchange.getResponse().getHeaders());
      return BodyInserters.fromPublisher(modifiedBody, String.class)
          .insert(outputMessage, new BodyInserterContext())
          .then(Mono.defer(() -> {
            Mono<DataBuffer> messageBody = writeBody(getDelegate(), outputMessage);
            HttpHeaders headers = getDelegate().getHeaders();
            if (!headers.containsKey(HttpHeaders.TRANSFER_ENCODING)
                || headers.containsKey(HttpHeaders.CONTENT_LENGTH)) {
              messageBody = messageBody.doOnNext(data -> headers
                  .setContentLength(data.readableByteCount()));
            }
            // TODO: fail if isStreamingMediaType?
            return getDelegate().writeWith(messageBody);
          }));
    }

    @Override
    public Mono<Void> writeAndFlushWith(
        Publisher<? extends Publisher<? extends DataBuffer>> body) {
      return writeWith(Flux.from(body).flatMapSequential(p -> p));
    }

    private ClientResponse prepareClientResponse(Publisher<? extends DataBuffer> body,
        HttpHeaders httpHeaders) {
      ClientResponse.Builder builder;
      builder = ClientResponse.create(exchange.getResponse().getStatusCode(),
          HandlerStrategies.withDefaults().messageReaders());
      return builder.headers(headers -> headers.putAll(httpHeaders))
          .body(Flux.from(body)).build();
    }

    private Mono<String> extractBody(ServerWebExchange exchange, ClientResponse clientResponse) {
      List<String> encodingHeaders = exchange.getResponse().getHeaders()
          .getOrEmpty(HttpHeaders.CONTENT_ENCODING);
      for (String encoding : encodingHeaders) {
        MessageBodyDecoder decoder = messageBodyDecoders.get(encoding);
        if (decoder != null) {
          return clientResponse.bodyToMono(byte[].class)
              .publishOn(Schedulers.parallel()).map(decoder::decode)
              .map(bytes -> exchange.getResponse().bufferFactory()
                  .wrap(bytes))
              .map(buffer -> prepareClientResponse(Mono.just(buffer),
                  exchange.getResponse().getHeaders()))
              .flatMap(response -> response.bodyToMono(String.class));
        }
      }


      return clientResponse.bodyToMono(String.class);

    }

    private Mono<DataBuffer> writeBody(ServerHttpResponse httpResponse,
        CachedBodyOutputMessage message) {
      Mono<DataBuffer> response = DataBufferUtils.join(message.getBody());

      List<String> encodingHeaders = httpResponse.getHeaders()
          .getOrEmpty(HttpHeaders.CONTENT_ENCODING);
      for (String encoding : encodingHeaders) {
        MessageBodyEncoder encoder = messageBodyEncoders.get(encoding);
        if (encoder != null) {
          DataBufferFactory dataBufferFactory = httpResponse.bufferFactory();
          response = response.publishOn(Schedulers.parallel()).map(buffer -> {
            byte[] encodedResponse = encoder.encode(buffer);
            DataBufferUtils.release(buffer);
            return encodedResponse;
          }).map(dataBufferFactory::wrap);
          break;
        }
      }

      return response;
    }

  }

  @Override
  public String name() {
    return NAME;
  }

}
