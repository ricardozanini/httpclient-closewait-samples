# HttpCommons Client pool connection manager example

Quoting from the [docs](http://hc.apache.org/httpcomponents-client-4.5.x/tutorial/html/connmgmt.html):

> One of the major shortcomings of the classic blocking I/O model is that the network socket can react to I/O events only when blocked in an I/O operation. When a connection is released back to the manager, it can be kept alive however it is unable to monitor the status of the socket and react to any I/O events. If the connection gets closed on the server side, the client side connection is unable to detect the change in the connection state (and react appropriately by closing the socket on its end).

When using the `PoolingHttpClientConnectionManager` you might face a situation where your connection will be returned to the pool, but after some time the server WILL close the connection with your app. So you would have those `CLOSE_WAIT` connections pending and claiming to be closed.

To exemplify this behavior, check the example in this repo.

In a nutshell, what we are doing is:

1. Create one connection manager and one `HttpClient` connection for the entire lifecycle
2. Create 15 threads to perform 15 requests to the [http://hc.apache.org](http://hc.apache.org) website.
3. Our pool have a limit of 5 connections, so we're limitating the requests in parallel
4. We perform those requests for the first time and the manager reuses the connections (keeping them alive, after all we're handling with the same server)
5. After that we sleep for a while. Those 5 connections will be on the pool and eventually the server will close the connections with us, leaving then in a `CLOSE_WAIT` state:

    ```log
    CLOSE-WAIT  1        0                             [::ffff:172.20.10.3]:34242                                [::ffff:95.216.24.32]:http                          users:(("java",pid=10651,fd=29))

    CLOSE-WAIT  1        0                             [::ffff:172.20.10.3]:34240                                [::ffff:95.216.24.32]:http                          users:(("java",pid=10651,fd=31))

    CLOSE-WAIT  1        0                             [::ffff:172.20.10.3]:34238                                [::ffff:95.216.24.32]:http                          users:(("java",pid=10651,fd=28))

    CLOSE-WAIT  1        0                             [::ffff:172.20.10.3]:34244                                [::ffff:95.216.24.32]:http                          users:(("java",pid=10651,fd=27))

    CLOSE-WAIT  1        0                             [::ffff:172.20.10.3]:34246                                [::ffff:95.216.24.32]:http                          users:(("java",   pid=10651,fd=30))
    ```
6. Now, to avoid this behavior we're closing idle connections by calling `connMgr.closeExpiredConnections()` and `connMgr.closeIdleConnections(5, TimeUnit.SECONDS)`. Here we use 5 seconds because we're in a lab env, in production 30 seconds should do.
7. We just perform those requests again to see if anything will break. It won't. The connection manager will create these connections again and everything should be fine.
8. Finally we shutdown everything and exit beautifully.

**The bottom line is**: you should reuse your connection managers and clients whenever it's possible. If you keep your connections alive for too long, the server will disconnect you, so you have to perform some clean up by your own.

In this sample, we have control of everything, so just calling the clean up methods from the manager did the job. In a production env, try to implement a separate thread to do so, like the example provided in the HttpCommons docs:

```java
public static class IdleConnectionMonitorThread extends Thread {
    
    private final HttpClientConnectionManager connMgr;
    private volatile boolean shutdown;
    
    public IdleConnectionMonitorThread(HttpClientConnectionManager connMgr) {
        super();
        this.connMgr = connMgr;
    }

    @Override
    public void run() {
        try {
            while (!shutdown) {
                synchronized (this) {
                    wait(5000);
                    // Close expired connections
                    connMgr.closeExpiredConnections();
                    // Optionally, close connections
                    // that have been idle longer than 30 sec
                    connMgr.closeIdleConnections(30, TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException ex) {
            // terminate
        }
    }
    
    public void shutdown() {
        shutdown = true;
        synchronized (this) {
            notifyAll();
        }
    }
    
}
```

Also, set the `Keep-Alive` headers on your requests to avoid keeping them open indefinitely. There's an example of how to do it in the Http Components docs and in this repo.

## References

1. [Http Components Docs - Chapter 2. Connection management](http://hc.apache.org/httpcomponents-client-4.5.x/tutorial/html/connmgmt.html)
2. [Baeldung - HttpClient Connection Management](http://www.baeldung.com/httpclient-connection-management)