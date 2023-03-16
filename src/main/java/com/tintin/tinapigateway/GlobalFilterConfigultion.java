package com.tintin.tinapigateway;

import com.tintin.tinapiclientsdk.utils.SignUtils;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class GlobalFilterConfigultion {
    @Bean
    public GlobalFilter customFilter() {
        return new CustomGlobalFilter();
    }

    public class CustomGlobalFilter implements GlobalFilter, Ordered {

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            // 1. 请求日志
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getPath().value();
            String method = request.getMethod().toString();
            log.info("请求唯一标识：" + request.getId());
            log.info("请求路径：" + path);
            log.info("请求方法：" + method);
            log.info("请求参数：" + request.getQueryParams());
            String sourceAddress = request.getLocalAddress().getHostString();
            log.info("请求来源地址：" + sourceAddress);
            log.info("请求来源地址：" + request.getRemoteAddress());
            ServerHttpResponse response = exchange.getResponse();
            // 2. 访问控制 - 黑白名单
//            if (!IP_WHITE_LIST.contains(sourceAddress)) {
//                response.setStatusCode(HttpStatus.FORBIDDEN);
//                return response.setComplete();
//            }
            // 3. 用户鉴权（判断 ak、sk 是否合法）
            HttpHeaders headers = request.getHeaders();
            String accessKey = headers.getFirst("accessKey");
            String sign = headers.getFirst("sign");
            String body = headers.getFirst("body");

            // todo 实际情况应该是去数据库中查是否已分配给用户
            if (!"accesskey".equals(accessKey)) {
                return handleNoAuth(response);
            }

            // 实际情况中是从数据库中查出 secretKey
            String serverSign = SignUtils.getSign(body, "abc");
            if (sign == null || !sign.equals(serverSign)) {
                return handleNoAuth(response);
            }
            // 4. 请求的模拟接口是否存在，以及请求方法是否匹配

            // todo 是否还有调用次数
            // 5. 请求转发，调用模拟接口 + 响应日志
            Mono<Void> filter = chain.filter(exchange);
            return filter;
//            return handleResponse(exchange, chain, interfaceInfo.getId(), invokeUser.getId());

        }

        /**
         * 处理响应
         * @param exchange
         * @param chain
         * @return
         */
        public Mono<Void> handleResponse(ServerWebExchange exchange, GatewayFilterChain chain){
            try {
                //从交换寄拿响应对象
                ServerHttpResponse originalResponse = exchange.getResponse();
                //缓冲区工厂，拿到缓存数据
                DataBufferFactory bufferFactory = originalResponse.bufferFactory();
                //拿到响应码
                HttpStatus statusCode = originalResponse.getStatusCode();

                if(statusCode == HttpStatus.OK){
                    //装饰，增强能力
                    ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                        //等调用完转发的接口后才会执行
                        @Override
                        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                            log.info("body instanceof Flux: {}", (body instanceof Flux));
                            //对象是响应式的
                            if (body instanceof Flux) {
                                //我们拿到真正的body
                                Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                                //往返回值里面写数据
                                //拼接字符串
                                return super.writeWith(
                                        fluxBody.map(dataBuffer -> {
                                            // 8. 调用成功， todo 接口调用次数+1 invokeCount
                                            byte[] content = new byte[dataBuffer.readableByteCount()];
                                            dataBuffer.read(content);
                                            DataBufferUtils.release(dataBuffer);//释放掉内存
                                            // 构建日志
                                            StringBuilder sb2 = new StringBuilder(200);
                                            List<Object> rspArgs = new ArrayList<>();
                                            rspArgs.add(originalResponse.getStatusCode());
                                            String data = new String(content, StandardCharsets.UTF_8);//data
                                            sb2.append(data);
                                            //打印日志
                                            log.info("响应结果" + data);
                                            return bufferFactory.wrap(content);
                                        }));
                            } else {
                                // 9. 调用失败，返回规范错误码
                                log.error("<--- {} 响应code异常", getStatusCode());
                            }
                            return super.writeWith(body);
                        }
                    };
                    //设置 response 对象为装饰过的
                    return chain.filter(exchange.mutate().response(decoratedResponse).build());
                }
                return chain.filter(exchange);//降级处理返回数据
            }catch (Exception e){
                log.error("网关处理响应异常" + e);
                return chain.filter(exchange);
            }

        }

        @Override
        public int getOrder() {
            return -1;
        }

        public Mono<Void> handleNoAuth(ServerHttpResponse response) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }

    }
}
