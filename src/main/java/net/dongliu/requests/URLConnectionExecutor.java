package net.dongliu.requests;

import net.dongliu.requests.body.RequestBody;
import net.dongliu.requests.exception.RequestsException;
import net.dongliu.requests.utils.CookieUtils;
import net.dongliu.requests.utils.IOUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static net.dongliu.requests.HttpHeaders.*;

/**
 * Execute http request with url connection
 *
 * @author Liu Dong
 */
public class URLConnectionExecutor implements HttpExecutor {

    @Override
    public RawResponse proceed(Request request) {
        RawResponse response = doRequest(request);

        if (!request.isFollowRedirect() || !isRedirect(response.getStatusCode())) {
            return response;
        }

        // handle redirect
        response.discardBody();
        int redirectCount = 0;
        URL redirectUrl = request.getUrl();
        while (redirectCount++ < 5) {
            String location = response.getFirstHeader(NAME_LOCATION);
            if (location == null) {
                throw new RequestsException("Redirect location not found");
            }
            try {
                redirectUrl = new URL(redirectUrl, location);
            } catch (MalformedURLException e) {
                throw new RequestsException("Get redirect url error", e);
            }
            RequestBuilder builder = request.getSession().get(redirectUrl.toExternalForm())
                    .socksTimeout(request.getSocksTimeout()).connectTimeout(request.getConnectTimeout())
                    .basicAuth(request.getBasicAuth())
                    .userAgent(request.getUserAgent())
                    .compress(request.isCompress())
                    .verify(request.isVerify())
                    .certs(request.getCerts())
                    .keepAlive(request.isKeepAlive())
                    .followRedirect(false);
            if (request.getProxy() != null) {
                builder.proxy(request.getProxy());
            }
            response = builder.send();
            if (!isRedirect(response.getStatusCode())) {
                return response;
            }
            response.discardBody();
        }
        throw new RequestsException("Too many redirect");
    }

    private static boolean isRedirect(int status) {
        return status == 300 || status == 301 || status == 302 || status == 303 || status == 307
                || status == 308;
    }


    private RawResponse doRequest(Request request) {
        Charset charset = request.getCharset();
        URL url = request.getUrl();
        Proxy proxy = request.getProxy();
        RequestBody body = request.getBody();
        Session session = request.getSession();

        String protocol = url.getProtocol();
        String host = url.getHost(); // only domain, do not have port
        // effective path for cookie
        String effectivePath = CookieUtils.effectivePath(url.getPath());

        HttpURLConnection conn;
        try {
            if (proxy != null) {
                conn = (HttpURLConnection) url.openConnection(proxy);
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
        } catch (IOException e) {
            throw new RequestsException(e);
        }

        // disable cache
        conn.setUseCaches(false);

        // deal with https
        if (conn instanceof HttpsURLConnection) {
            SSLSocketFactory ssf = null;
            if (!request.isVerify()) {
                // trust all certificates
                ssf = SSLSocketFactories.getTrustAllSSLSocketFactory();
                // do not verify host of certificate
                ((HttpsURLConnection) conn).setHostnameVerifier(NopHostnameVerifier.getInstance());
            } else if (!request.getCerts().isEmpty()) {
                ssf = SSLSocketFactories.getCustomSSLSocketFactory(request.getCerts());
            }
            if (ssf != null) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(ssf);
            }
        }

        try {
            conn.setRequestMethod(request.getMethod());
        } catch (ProtocolException e) {
            throw new RequestsException(e);
        }
        conn.setReadTimeout(request.getSocksTimeout());
        conn.setConnectTimeout(request.getConnectTimeout());
        // Url connection did not deal with cookie when handle redirect. Disable it and handle it manually
        conn.setInstanceFollowRedirects(false);
        if (body != null) {
            conn.setDoOutput(true);
            String contentType = body.getContentType();
            if (contentType != null) {
                if (body.isIncludeCharset()) {
                    contentType += "; charset=" + request.getCharset().name().toLowerCase();
                }
                conn.setRequestProperty(NAME_CONTENT_TYPE, contentType);
            }
        }

        // headers
        if (!request.getUserAgent().isEmpty()) {
            conn.setRequestProperty(NAME_USER_AGENT, request.getUserAgent());
        }
        if (request.isCompress()) {
            conn.setRequestProperty(NAME_ACCEPT_ENCODING, "gzip, deflate");
        }

        if (request.getBasicAuth() != null) {
            conn.setRequestProperty(NAME_AUTHORIZATION, request.getBasicAuth().encode());
        }

        // set cookies
        List<Cookie> sessionCookies = session.matchedCookies(protocol, host, effectivePath);
        if (!request.getCookies().isEmpty() || !sessionCookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, ?> entry : request.getCookies()) {
                sb.append(entry.getKey()).append("=").append(String.valueOf(entry.getValue())).append("; ");
            }
            for (Cookie cookie : sessionCookies) {
                sb.append(cookie.getName()).append("=").append(cookie.getValue()).append("; ");
            }
            if (sb.length() > 2) {
                sb.setLength(sb.length() - 2);
                String cookieStr = sb.toString();
                conn.setRequestProperty(NAME_COOKIE, cookieStr);
            }
        }

        // set user custom headers
        for (Map.Entry<String, ?> header : request.getHeaders()) {
            conn.setRequestProperty(header.getKey(), String.valueOf(header.getValue()));
        }

        // disable keep alive
        if (!request.isKeepAlive()) {
            conn.setRequestProperty("Connection", "close");
        }

        try {
            conn.connect();
        } catch (IOException e) {
            throw new RequestsException(e);
        }

        try {
            // send body
            if (body != null) {
                sendBody(body, conn, charset);
            }
            return getResponse(conn, session, request.isCompress(), request.getMethod(), host, effectivePath);
        } catch (IOException e) {
            conn.disconnect();
            throw new RequestsException(e);
        } catch (Throwable e) {
            conn.disconnect();
            throw e;
        }
    }

    /**
     * Wrap response, deal with headers and cookies
     */
    private RawResponse getResponse(HttpURLConnection conn, Session session, boolean compress,
                                    String method, String host, String path) throws IOException {
        // read result
        int status = conn.getResponseCode();

        String statusLine = null;
        // headers and cookies
        List<Parameter<String>> headerList = new ArrayList<>();
        Set<Cookie> cookies = new HashSet<>();
        int index = 0;
        while (true) {
            String key = conn.getHeaderFieldKey(index);
            String value = conn.getHeaderField(index);
            if (value == null) {
                break;
            }
            index++;
            //status line
            if (key == null) {
                statusLine = value;
                continue;
            }
            headerList.add(Parameter.of(key, value));
            if (key.equalsIgnoreCase(NAME_SET_COOKIE)) {
                cookies.add(CookieUtils.parseCookieHeader(host, path, value));
            }
        }
        Headers headers = new Headers(headerList);

        InputStream input;
        try {
            input = conn.getInputStream();
        } catch (IOException e) {
            input = conn.getErrorStream();
        }
        if (input != null) {
            // deal with [compressed] input
            if (compress) {
                input = wrapCompressBody(status, method, headers, input);
            }
        } else {
            input = new ByteArrayInputStream(new byte[0]);
        }

        // update session
        session.updateCookie(cookies);
        return new RawResponse(status, Objects.requireNonNull(statusLine), headers, cookies, input, conn);
    }

    /**
     * Wrap response input stream if it is compressed, return input its self if not use compress
     */
    private InputStream wrapCompressBody(int status, String method, Headers headers, InputStream input)
            throws IOException {
        // if has no body, some server still set content-encoding header,
        // GZIPInputStream wrap empty input stream will cause exception. we should check this
        if (method.equals("HEAD") || (status >= 100 && status < 200) || status == 304 || status == 204) {
            return input;
        }

        String contentEncoding = headers.getFirstHeader(NAME_CONTENT_ENCODING);
        if (contentEncoding == null) {
            return input;
        }

        //we should remove the content-encoding header here?
        switch (contentEncoding) {
            case "gzip":
                try {
                    return new GZIPInputStream(input);
                } catch (IOException e) {
                    IOUtils.closeQuietly(input);
                    throw e;
                }
            case "deflate":
                // Note: deflate implements may or may not wrap in zlib due to rfc confusing. 
                // here deal with deflate without zlib header
                return new InflaterInputStream(input, new Inflater(true));
            case "identity":
            case "compress": //historic; deprecated in most applications and replaced by gzip or deflate
            default:
                return input;
        }
    }

    private void sendBody(RequestBody body, HttpURLConnection conn, Charset requestCharset) {
        try (OutputStream os = conn.getOutputStream()) {
            body.writeBody(os, requestCharset);
        } catch (IOException e) {
            throw new RequestsException(e);
        }
    }
}
