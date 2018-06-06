package samples.httpclient.closewait;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example of how to manage HttpClient component to avoid "CLOSE_WAIT" and "IDLE" connections on the client side.
 * 
 * @see <a href="http://www.baeldung.com/httpclient-connection-management">HttpClient Connection Management</a>
 * @see <a href="http://hc.apache.org/httpcomponents-client-ga/tutorial/html/connmgmt.html">Http Components - Chapter 2. Connection management</a>
 */
public final class Main {

    private final static Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private final static int ONEMILLI = 1000;
    private final static int TIMEOUT_SECONDS = 1;
    private final static int THREADS = 15;
    private final static int WAIT_SECS = 120;

    public Main() {
    }

    public static void main(String[] args) {
        LOGGER.info("Starting HttpClient sample");
        HttpGet get = new HttpGet("http://hc.apache.org");
        ExecutorService executorService = Executors.newFixedThreadPool(THREADS);
        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setDefaultMaxPerRoute(5);
        connManager.setMaxTotal(5);

        try (CloseableHttpClient client = HttpClients.custom().setConnectionManager(connManager).setKeepAliveStrategy(createConnectionKeepAliveStrategy()).build()) {
            ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<Void>(executorService);
            List<Future<Void>> futures = new ArrayList<Future<Void>>();

            LOGGER.info("Starting all threads: {}", THREADS);
            for (int i = 0; i < THREADS; i++) {
                futures.add(completionService.submit(new MultiHttpClientConnRunnable(client, get)));
            }

            int completed = 0;
            LOGGER.info("Waiting for all requests to be completed");
            while (completed < THREADS) {
                for (int i = 0; i < futures.size(); i++) {
                    if (futures.get(i).isDone()) {
                        completed++;
                        LOGGER.info("Request is done. Completed: {}", completed);
                        futures.remove(i);
                        if (completed == THREADS) {
                            LOGGER.info("End of all requests...");
                        }
                        break;
                    }
                }
            }
            // if you add a Thread.sleep in here, the connections eventually will be on "CLOSE_WAIT" state b/c the server would close the connection with us after the Keep-Alive timeout.
        } catch (IOException e) {
            LOGGER.error("Error during checking for performing client connections", e);
        } finally {
            LOGGER.info("Sleeping a little bit to see what happened to the connections");
            try {
                Thread.sleep(ONEMILLI * WAIT_SECS);
            } catch (InterruptedException e) {
                LOGGER.error("Error during checking for completed requests", e);
            }
            connManager.close();
            executorService.shutdown();
        }

    }

    private static ConnectionKeepAliveStrategy createConnectionKeepAliveStrategy() {
        return new ConnectionKeepAliveStrategy() {

            public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
                HeaderElementIterator it = new BasicHeaderElementIterator(response.headerIterator(HTTP.CONN_KEEP_ALIVE));
                while (it.hasNext()) {
                    HeaderElement he = it.nextElement();
                    String param = he.getName();
                    String value = he.getValue();
                    if (value != null && param.equalsIgnoreCase("timeout")) {
                        return Long.parseLong(value) * ONEMILLI;
                    }
                }
                return TIMEOUT_SECONDS * ONEMILLI;
            }
        };
    }

    /**
     * Notice that we’re using a very simple custom thread implementation
     */
    private static class MultiHttpClientConnRunnable implements Callable<Void> {
        private CloseableHttpClient client;
        private HttpGet get;

        public MultiHttpClientConnRunnable(CloseableHttpClient client, HttpGet get) {
            this.client = client;
            this.get = get;
        }

        public Void call() throws Exception {
            CloseableHttpResponse response = null;
            try {
                LOGGER.info("Executing request");
                response = client.execute(get);
                EntityUtils.consume(response.getEntity());
                LOGGER.info("Response consumed");
            } catch (ClientProtocolException ex) {
                LOGGER.error("Client Protocol Exception", ex);
                throw ex;
            } catch (IOException ex) {
                LOGGER.error("IO Error", ex);
                throw ex;
            } finally {
                if (response != null) {
                    try {
                        response.close();
                    } catch (IOException e) {
                        LOGGER.error("Impossble to close our response", e);
                    }
                }
            }
            return null;
        }
    }

}
