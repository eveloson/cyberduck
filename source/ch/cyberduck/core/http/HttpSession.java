package ch.cyberduck.core.http;

/*
 * Copyright (c) 2002-2010 David Kocher. All rights reserved.
 *
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to:
 * dkocher@cyberduck.ch
 */

import ch.cyberduck.core.Host;
import ch.cyberduck.core.Preferences;
import ch.cyberduck.core.Proxy;
import ch.cyberduck.core.ProxyFactory;
import ch.cyberduck.core.ssl.CustomTrustSSLProtocolSocketFactory;
import ch.cyberduck.core.ssl.SSLSession;

import org.apache.http.*;
import org.apache.http.auth.params.AuthPNames;
import org.apache.http.auth.params.AuthParams;
import org.apache.http.client.params.AuthPolicy;
import org.apache.http.client.params.HttpClientParams;
import org.apache.http.client.protocol.RequestAcceptEncoding;
import org.apache.http.client.protocol.ResponseContentEncoding;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.params.ConnRouteParams;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ConnPoolByRoute;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HttpContext;
import org.apache.log4j.Logger;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * @version $Id: HTTPSession.java 7171 2010-10-02 15:06:28Z dkocher $
 */
public abstract class HttpSession extends SSLSession {
    private static Logger log = Logger.getLogger(HttpSession.class);

    protected HttpSession(Host h) {
        super(h);
    }

    private AbstractHttpClient http;

    /**
     * Create new HTTP client with default configuration and custom trust manager.
     *
     * @return A new instance of a default HTTP client.
     */
    protected AbstractHttpClient http() {
        if(null == http) {
            final HttpParams params = new BasicHttpParams();

            HttpProtocolParams.setVersion(params, org.apache.http.HttpVersion.HTTP_1_1);
            HttpProtocolParams.setContentCharset(params, getEncoding());
            HttpProtocolParams.setUserAgent(params, getUserAgent());

            AuthParams.setCredentialCharset(params, Preferences.instance().getProperty("http.credentials.charset"));
            // We do not currently have a fallback mechanism for multiple
            // authentication schemes.
            // Put Kerberos to the bottom because it requires additional configuration we
            // do not currently provide.
            params.setParameter(AuthPNames.TARGET_AUTH_PREF, Arrays.asList(
                    AuthPolicy.NTLM,
                    AuthPolicy.DIGEST,
                    AuthPolicy.BASIC,
                    // Disable Kerberos
                    AuthPolicy.SPNEGO)
            );
            HttpConnectionParams.setTcpNoDelay(params, true);
            HttpConnectionParams.setSoTimeout(params, timeout());
            HttpConnectionParams.setConnectionTimeout(params, timeout());
            HttpConnectionParams.setSocketBufferSize(params,
                    Preferences.instance().getInteger("http.socket.buffer"));
            HttpConnectionParams.setStaleCheckingEnabled(params, true);

            HttpClientParams.setRedirecting(params, true);
            HttpClientParams.setAuthenticating(params, true);

            // Sets the timeout in milliseconds used when retrieving a connection from the ClientConnectionManager
            ConnManagerParams.setTimeout(params,
                    Preferences.instance().getInteger("http.manager.timeout"));

            SchemeRegistry registry = new SchemeRegistry();
            // Always register HTTP for possible use with proxy
            registry.register(new Scheme("http", host.getPort(), PlainSocketFactory.getSocketFactory()));
            if("https".equals(this.getHost().getProtocol().getScheme())) {
                org.apache.http.conn.ssl.SSLSocketFactory factory = new SSLSocketFactory(
                        new CustomTrustSSLProtocolSocketFactory(this.getTrustManager()).getSSLContext(),
                        new X509HostnameVerifier() {
                            public void verify(String host, SSLSocket ssl) throws IOException {
                                log.warn("Hostname verification disabled for:" + host);
                            }

                            public void verify(String host, X509Certificate cert) throws SSLException {
                                log.warn("Hostname verification disabled for:" + host);
                            }

                            public void verify(String host, String[] cns, String[] subjectAlts) throws SSLException {
                                log.warn("Hostname verification disabled for:" + host);
                            }

                            public boolean verify(String s, javax.net.ssl.SSLSession sslSession) {
                                log.warn("Hostname verification disabled for:" + s);
                                return true;
                            }
                        }
                );
                registry.register(new Scheme(host.getProtocol().getScheme(), host.getPort(), factory));
            }
            if(Preferences.instance().getBoolean("connection.proxy.enable")) {
                final Proxy proxy = ProxyFactory.instance();
                if("https".equals(this.getHost().getProtocol().getScheme())) {
                    if(proxy.isHTTPSProxyEnabled(host)) {
                        ConnRouteParams.setDefaultProxy(params, new HttpHost(proxy.getHTTPSProxyHost(host), proxy.getHTTPSProxyPort(host)));
                    }
                }
                if("http".equals(this.getHost().getProtocol().getScheme())) {
                    if(proxy.isHTTPProxyEnabled(host)) {
                        ConnRouteParams.setDefaultProxy(params, new HttpHost(proxy.getHTTPProxyHost(host), proxy.getHTTPProxyPort(host)));
                    }
                }
            }
            ThreadSafeClientConnManager manager = new ThreadSafeClientConnManager(registry) {
                @Override
                protected ConnPoolByRoute createConnectionPool(long connTTL, TimeUnit connTTLTimeUnit) {
                    // Set the maximum connections per host for the HTTP connection manager,
                    // *and* also set the maximum number of total connections.
                    connPerRoute.setDefaultMaxPerRoute(Preferences.instance().getInteger("http.connections.route"));
                    return new ConnPoolByRoute(connOperator, connPerRoute,
                            Preferences.instance().getInteger("http.connections.total"));
                }
            };
            http = new DefaultHttpClient(manager, params);
            this.configure(http);
        }
        return http;
    }

    protected void configure(AbstractHttpClient client) {
        http.addRequestInterceptor(new HttpRequestInterceptor() {
            public void process(final HttpRequest request, final HttpContext context) throws HttpException, IOException {
                log(true, request.getRequestLine().toString());
                for(Header header : request.getAllHeaders()) {
                    log(true, header.toString());
                }
            }
        });
        http.addResponseInterceptor(new HttpResponseInterceptor() {
            public void process(final HttpResponse response, final HttpContext context) throws HttpException, IOException {
                log(false, response.getStatusLine().toString());
                for(Header header : response.getAllHeaders()) {
                    log(false, header.toString());
                }
            }
        });
        if(Preferences.instance().getBoolean("http.compression.enable")) {
            client.addRequestInterceptor(new RequestAcceptEncoding());
            client.addResponseInterceptor(new ResponseContentEncoding());
        }
    }

    @Override
    public void close() {
        try {
            // When HttpClient instance is no longer needed, shut down the connection manager to ensure
            // immediate deallocation of all system resources
            if(null != http) {
                http.getConnectionManager().shutdown();
            }
        }
        finally {
            http = null;
        }
    }
}
