package dev.badrilogaram.orderflow.orders;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class GatewayTransport {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    // Keep a single probe's response bounded, including unexpected error pages.
    public Response send(HttpRequest request, int maxBytes) throws IOException, InterruptedException {
        if (maxBytes < 1) throw new IllegalArgumentException("A positive response limit is required");
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> new BoundedBody(maxBytes));
        try {
            // A deadline around the complete body also covers a server that stalls after sending headers.
            var response = future.get(request.timeout().orElse(Duration.ofSeconds(3)).toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new HttpTimeoutException("Complete response deadline exceeded");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            throw new IOException("HTTP exchange failed", failed.getCause());
        }
    }

    public record Response(int status, String body) { }

    public static final class ResponseTooLargeException extends IOException {
        public ResponseTooLargeException() { super("Response exceeded the configured size limit"); }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> complete = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return complete; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > limit) {
                    subscription.cancel(); complete.completeExceptionally(new ResponseTooLargeException()); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
        }
        public void onError(Throwable failure) { complete.completeExceptionally(failure); }
        public void onComplete() { complete.complete(bytes.toByteArray()); }
    }
}

