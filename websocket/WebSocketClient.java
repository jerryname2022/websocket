package com.zlwy.activity.http.websocket;

import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Base64;

import com.zlwy.activity.base.Logger;

import org.apache.http.*;
import org.apache.http.client.HttpResponseException;
import org.apache.http.message.BasicLineParser;
import org.apache.http.message.BasicNameValuePair;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;


public class WebSocketClient {
    private static final String TAG = "WebSocketClient";

    private URI mURI;
    private Listener mListener;
    private Socket mSocket;
    private Thread mThread;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private List<BasicNameValuePair> mExtraHeaders;
    private HybiParser mParser;

    private final Object mSendLock = new Object();

    private static TrustManager[] sTrustManagers;

    public static void setTrustManagers(TrustManager[] tm) {
        sTrustManagers = tm;
    }

    /**
     * @param uri          链接uri
     * @param listener     链接监听
     * @param extraHeaders websocket的扩展头字段和值
     */
    public WebSocketClient(URI uri, Listener listener, List<BasicNameValuePair> extraHeaders) {
        mURI = uri;
        mListener = listener;
        mExtraHeaders = extraHeaders;
        mParser = new HybiParser(this);

        mHandlerThread = new HandlerThread("websocket-thread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    public Listener getListener() {
        return mListener;
    }

    public void connect() {
        if (mThread != null && mThread.isAlive()) {
            return;
        }
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String secret = createSecret();
                    int port = (mURI.getPort() != -1) ? mURI.getPort() : (mURI.getScheme().equals("wss") ? 443 : 80);

                    String path = TextUtils.isEmpty(mURI.getPath()) ? "/" : mURI.getPath();
                    if (!TextUtils.isEmpty(mURI.getQuery())) {
                        path += "?" + mURI.getQuery();
                    }
                    String originScheme = mURI.getScheme().equals("wss") ? "https" : "http";
                    URI origin = new URI(originScheme, "//" + mURI.getHost(), null);

                    SocketFactory factory = mURI.getScheme().equals("wss") ? getSSLSocketFactory() : SocketFactory.getDefault();
                    mSocket = factory.createSocket(mURI.getHost(), port);
                    mSocket.setKeepAlive(true);

                    PrintWriter out = new PrintWriter(mSocket.getOutputStream());
                     /*----------------打开阶段握手发送---------------*/
                    out.print("GET " + path + " HTTP/1.1\r\n");
                    out.print("Upgrade: websocket\r\n");
                    out.print("Connection: Upgrade\r\n");
                    out.print("Host: " + mURI.getHost() + "\r\n");
                    out.print("Origin: " + origin.toString() + "\r\n");
                    out.print("Sec-WebSocket-Key: " + secret + "\r\n");
                    out.print("Sec-WebSocket-Version: 13\r\n");
                    if (mExtraHeaders != null) {
                        for (NameValuePair pair : mExtraHeaders) {
                            out.print(String.format("%s: %s\r\n", pair.getName(), pair.getValue()));
                        }
                    }
                    out.print("\r\n");
                    out.flush();
                  /*----------------打开阶段握手已发送---------------*/
                    HybiParser.HappyDataInputStream stream = new HybiParser.HappyDataInputStream(mSocket.getInputStream());
                    // Read HTTP response status line.
                    StatusLine statusLine = parseStatusLine(readLine(stream));
                    if (statusLine == null) {
                        throw new HttpException("Received no reply from server.");
                    } else if (statusLine.getStatusCode() != HttpStatus.SC_SWITCHING_PROTOCOLS) {
                        throw new HttpResponseException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
                    }
                    // Read HTTP response headers.
                    String line;
                    boolean validated = false;
                    while (!TextUtils.isEmpty(line = readLine(stream))) {
                        Header header = parseHeader(line);
                        if (header.getName().equals("Sec-WebSocket-Accept")) {
                            String expected = createSecretValidation(secret);
                            String actual = header.getValue().trim();
                            if (!expected.equals(actual)) {//检测服务器返回的Sec-WebSocket-Accept值是否正确
                                throw new HttpException("Bad Sec-WebSocket-Accept header value.");
                            }
                            validated = true;
                        }
                    }

                    if (!validated) {
                        throw new HttpException("No Sec-WebSocket-Accept header.");
                    }

                    mListener.onConnect();
                    // Now decode websocket frames.  while(true)监听socket接口
                    mParser.start(stream);
                } catch (EOFException ex) {
                    Logger.i(TAG, "WebSocket EOF!" + ex);
                    mListener.onDisconnect(0, "EOF");
                } catch (SSLException ex) {
                    // Connection reset by peer
                    Logger.i(TAG, "Websocket SSL error!" + ex);
                    mListener.onDisconnect(0, "SSL");
                } catch (Exception ex) {
                    mListener.onError(ex);
                }
            }
        });
        mThread.start();
    }

    public void disconnect() {
        if (mSocket != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (mSocket != null && mSocket.isConnected()) {
                            mSocket.close();
                        }
                        mSocket = null;
                        try {
                            if (mThread != null && mThread.isAlive()) {
                                mThread.interrupt();
                            }
                        } catch (Exception e) {
                            mThread = null;
                        }

                    } catch (IOException ex) {
                        Logger.i(TAG, "Error while disconnecting" + ex);
                        try {
                            if (mThread != null && mThread.isAlive()) {
                                mThread.interrupt();
                            }
                        } catch (Exception e) {
                            mThread = null;
                        }
                        mSocket = null;
                        mListener.onError(ex);
                    }
                }
            });
        }

    }

    public void send(String data) throws Exception {
        sendFrame(mParser.frame(data));
    }

    public void send(byte[] data) throws Exception {
        sendFrame(mParser.frame(data));
    }

    /**
     * 解析状态行
     *
     * @param line
     * @return
     */
    private StatusLine parseStatusLine(String line) {
        if (TextUtils.isEmpty(line)) {
            return null;
        }
        return BasicLineParser.parseStatusLine(line, new BasicLineParser());
    }

    private Header parseHeader(String line) {
        return BasicLineParser.parseHeader(line, new BasicLineParser());
    }

    // Can't use BufferedReader because it buffers past the HTTP data.
    private String readLine(HybiParser.HappyDataInputStream reader) throws IOException {
        int readChar = reader.read();
        if (readChar == -1) {
            return null;
        }
        StringBuilder string = new StringBuilder("");
        while (readChar != '\n') {
            if (readChar != '\r') {
                string.append((char) readChar);
            }
            readChar = reader.read();
            if (readChar == -1) {
                return null;
            }
        }
        return string.toString();
    }

    private String createSecret() {
        byte[] nonce = new byte[16];
        for (int i = 0; i < 16; i++) {
            nonce[i] = (byte) (Math.random() * 256);
        }
        return Base64.encodeToString(nonce, Base64.DEFAULT).trim();
    }

    private String createSecretValidation(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((secret + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes());
            return Base64.encodeToString(md.digest(), Base64.DEFAULT).trim();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    void sendFrame(final byte[] frame) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (mSendLock) {
                        if (mSocket == null) {
                            IllegalStateException e = new IllegalStateException("Socket not connected");
                            throw e;
                        }
                        OutputStream outputStream = mSocket.getOutputStream();
                        outputStream.write(frame);
                        outputStream.flush();
                        Logger.i("HybiParser", "OutputStream write isConnected : " + mSocket.isConnected() + " getKeepAlive : " + mSocket.getKeepAlive());
                    }
                } catch (Exception e) {
                    mListener.onError(e);
                }
            }
        });
    }

    public interface Listener {
        void onConnect();

        void onMessage(String message);

        void onMessage(byte[] data);

        void onDisconnect(int code, String reason);

        void onError(Exception error);
    }

    /**
     * 链接发生在HTTPS端口上
     *
     * @return
     * @throws NoSuchAlgorithmException
     * @throws KeyManagementException
     */
    private SSLSocketFactory getSSLSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, sTrustManagers, null);
        return context.getSocketFactory();
    }

    /**
     * 连接状态
     * @return
     */
    public boolean isConnected() {
        if (null != mSocket)
            return mSocket.isConnected();
        return false;
    }

    /**
     * 关闭状态
     * default:true 关闭状态
     *
     * @return
     */
    public boolean isClosed() {
        if (null != mSocket)
            return mSocket.isClosed();
        return true;
    }
}
