//
// HybiParser.java: draft-ietf-hybi-thewebsocketprotocol-13 parser
//
// Based on code from the faye project.
// https://github.com/faye/faye-websocket-node
// Copyright (c) 2009-2012 James Coglan
//
// Ported from Javascript to Java by Eric Butler <eric@codebutler.com>
//
// (The MIT License)
//
// Permission is hereby granted, free of charge, to any person obtaining
// a copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to
// permit persons to whom the Software is furnished to do so, subject to
// the following conditions:
//
// The above copyright notice and this permission notice shall be
// included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
// EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
// LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
// OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
// WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package com.zlwy.activity.http.websocket;

import com.zlwy.activity.base.Logger;

import java.io.*;
import java.util.Arrays;
import java.util.List;

public class HybiParser {

    private static final String TAG = "HybiParser";
    private WebSocketClient mClient;
    private boolean mMasking = true; //原

    private int mStage = 0;

    private boolean mFinal;
    private boolean mMasked;
    private int mOpcode;
    private int mLengthSize;
    private int mLength;
    private int mMode;

    private byte[] mMask = new byte[0];
    private byte[] mPayload = new byte[0];

    private boolean mClosed = false;

    private ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();

    private static final int BYTE = 255;
    private static final int FIN = 128;
    private static final int MASK = 128;
    private static final int RSV1 = 64;
    private static final int RSV2 = 32;
    private static final int RSV3 = 16;
    private static final int OPCODE = 15;
    private static final int LENGTH = 127;

    private static final int MODE_TEXT = 1;
    private static final int MODE_BINARY = 2;

    private static final int OP_CONTINUATION = 0;//4-7位值，连续帧
    private static final int OP_TEXT = 1;//4-7位值，文本帧
    private static final int OP_BINARY = 2;//4-7位值，二进制帧
    private static final int OP_CLOSE = 8;//4-7位值，关闭连接控制帧
    private static final int OP_PING = 9;//4-7位值，ping帧
    private static final int OP_PONG = 10;//4-7位值，pong帧

    private static final List<Integer> OPCODES = Arrays.asList(//操作码
            OP_CONTINUATION,//连续帧
            OP_TEXT,//文本帧
            OP_BINARY,//二进制帧
            OP_CLOSE,//关闭连接控制帧
            OP_PING,//ping帧
            OP_PONG//pong帧
    );

    private static final List<Integer> FRAGMENTED_OPCODES = Arrays.asList(
            OP_CONTINUATION, OP_TEXT, OP_BINARY
    );

    public HybiParser(WebSocketClient client) {
        mClient = client;
    }

    private static byte[] mask(byte[] payload, byte[] mask, int offset) {
        if (mask.length == 0) return payload;
        for (int i = 0; i < payload.length - offset; i++) {
            payload[offset + i] = (byte) (payload[offset + i] ^ mask[i % 4]);
        }
        return payload;
    }

    public void start(HappyDataInputStream stream) throws Exception {
        A:
        while (true) {
            if (stream.available() == -1) {
                Logger.i("HybiParser", "HappyDataInputStream available : -1");
                break;
            }
            switch (mStage) {
                case 0:
                    parseOpcode(stream.readByte());//取1byte(8位，最大值是0111 1111 从最高位到最低位解析(0-7位))
                    break;
                case 1:
                    parseLength(stream.readByte());
                    break;
                case 2:
                    parseExtendedLength(stream.readBytes(mLengthSize));
                    break;
                case 3:
                    mMask = stream.readBytes(4);
                    mStage = 4;
                    break;
                case 4:
                    mPayload = stream.readBytes(mLength);
                    emitFrame();
                    mStage = 0;
                    break;
                default:
                    break A;
            }
        }
        mClient.getListener().onDisconnect(0, "EOF");
    }

    private void parseOpcode(byte data) throws ProtocolError {
        //取1byte(8位，最大值是0111 1111 即127)
        boolean rsv1 = (data & RSV1) == RSV1;//获取第1位保留位，为0
        boolean rsv2 = (data & RSV2) == RSV2;//获取第2位保留位，为0
        boolean rsv3 = (data & RSV3) == RSV3;//获取第3位保留位，为0

        if (rsv1 || rsv2 || rsv3) {
            throw new ProtocolError("RSV not zero");
        }

        mFinal = (data & FIN) == FIN;//第0位,判断最高位,data最高位用于描述消息是否结束,如果为1则该消息为消息尾部,如果为零则还有后续数据包;
        mOpcode = (data & OPCODE);//第4-7位，opcode 操作码,用于标识该帧负载类型。根据websocket协议，如果收到了未知的操作码，则需要断开websocket链接。

        mMask = new byte[0];
        mPayload = new byte[0];

        if (!OPCODES.contains(mOpcode)) {//判断负载类型
            throw new ProtocolError("Bad opcode");
        }

        if (!FRAGMENTED_OPCODES.contains(mOpcode) && !mFinal) {
            throw new ProtocolError("Expected non-final packet");
        }
        mStage = 1;
    }

    private void parseLength(byte data) {
        mMasked = (data & MASK) == MASK; //MASK掩码位，第8位用来表明负载是否经过掩码处理。
        mLength = (data & LENGTH);//9-15位，用来获取负载长度

        if (mLength >= 0 && mLength <= 125) {//单位字节如果负载长度0~125字节，则此处就是负载长度字节数。
            mStage = mMasked ? 3 : 4;//MASK则跳过4个字节。负载直接读取 mLength 个字节数据。
        } else {
            mLengthSize = (mLength == 126) ? 2 : 8;//继续获取真实的负载长度。如果此时的长度为mLength == 126，则读取后两个字节(16-32位)为真实长度，否则读取后8个字节(16-80位)为真实长度。
            mStage = 2;//重新获取长度。
        }
    }

    private void parseExtendedLength(byte[] buffer) throws ProtocolError {
        mLength = getInteger(buffer);
        mStage = mMasked ? 3 : 4;
    }

    public byte[] frame(String data) {
        return frame(data, OP_TEXT, -1);
    }

    public byte[] frame(byte[] data) {
        return frame(data, OP_BINARY, -1);
    }

    private byte[] frame(byte[] data, int opcode, int errorCode) {
        return frame((Object) data, opcode, errorCode);
    }

    private byte[] frame(String data, int opcode, int errorCode) {
        return frame((Object) data, opcode, errorCode);
    }

    private byte[] frame(Object data, int opcode, int errorCode) {
        if (mClosed) return null;

        Logger.i(TAG, "Creating frame for: " + data + " op: " + opcode + " err: " + errorCode);

        byte[] buffer = (data instanceof String) ? decode((String) data) : (byte[]) data;
        int insert = (errorCode > 0) ? 2 : 0;  //0
        int length = buffer.length + insert; // 63
        int header = (length <= 125) ? 2 : (length <= 65535 ? 4 : 10); // 2
        int offset = header + (mMasking ? 4 : 0); // 6
        int masked = mMasking ? MASK : 0;  // 128
        byte[] frame = new byte[length + offset]; //69

        frame[0] = (byte) ((byte) FIN | (byte) opcode);// 1

        if (length <= 125) {
            frame[1] = (byte) (masked | length);
        } else if (length <= 65535) {
            frame[1] = (byte) (masked | 126);
            frame[2] = (byte) Math.floor(length / 256);
            frame[3] = (byte) (length & BYTE);
        } else {
            frame[1] = (byte) (masked | 127);
            frame[2] = (byte) (((int) Math.floor(length / Math.pow(2, 56))) & BYTE);
            frame[3] = (byte) (((int) Math.floor(length / Math.pow(2, 48))) & BYTE);
            frame[4] = (byte) (((int) Math.floor(length / Math.pow(2, 40))) & BYTE);
            frame[5] = (byte) (((int) Math.floor(length / Math.pow(2, 32))) & BYTE);
            frame[6] = (byte) (((int) Math.floor(length / Math.pow(2, 24))) & BYTE);
            frame[7] = (byte) (((int) Math.floor(length / Math.pow(2, 16))) & BYTE);
            frame[8] = (byte) (((int) Math.floor(length / Math.pow(2, 8))) & BYTE);
            frame[9] = (byte) (length & BYTE);
        }
        if (errorCode > 0) {
            frame[offset] = (byte) (((int) Math.floor(errorCode / 256)) & BYTE);
            frame[offset + 1] = (byte) (errorCode & BYTE);
        }
        System.arraycopy(buffer, 0, frame, offset + insert, buffer.length);
        if (mMasking) {
            byte[] mask = {
                    (byte) Math.floor(Math.random() * 256), (byte) Math.floor(Math.random() * 256),
                    (byte) Math.floor(Math.random() * 256), (byte) Math.floor(Math.random() * 256)
            };
            System.arraycopy(mask, 0, frame, header, mask.length);
            mask(frame, mask, offset);
        }
        return frame;
    }

    public void ping(String message) throws Exception {
        mClient.send(frame(message, OP_PING, -1));
    }

    public void close(int code, String reason) throws Exception {
        if (mClosed) return;
        mClient.send(frame(reason, OP_CLOSE, code));
        mClosed = true;
    }

    private void emitFrame() throws Exception {
        byte[] payload = mask(mPayload, mMask, 0);
        int opcode = mOpcode;

        if (opcode == OP_CONTINUATION) {
            if (mMode == 0) {
                throw new ProtocolError("Mode was not set.");
            }
            mBuffer.write(payload);
            if (mFinal) {
                byte[] message = mBuffer.toByteArray();
                if (mMode == MODE_TEXT) {
                    mClient.getListener().onMessage(encode(message));
                } else {
                    mClient.getListener().onMessage(message);
                }
                reset();
            }

        } else if (opcode == OP_TEXT) {
            if (mFinal) {
                String messageText = encode(payload);
                mClient.getListener().onMessage(messageText);
            } else {
                mMode = MODE_TEXT;
                mBuffer.write(payload);
            }

        } else if (opcode == OP_BINARY) {
            if (mFinal) {
                mClient.getListener().onMessage(payload);
            } else {
                mMode = MODE_BINARY;
                mBuffer.write(payload);
            }

        } else if (opcode == OP_CLOSE) {
            int code = (payload.length >= 2) ? 256 * payload[0] + payload[1] : 0;
            String reason = (payload.length > 2) ? encode(slice(payload, 2)) : null;
            Logger.i(TAG, "Got close op! " + code + " " + reason);
            mClient.getListener().onDisconnect(code, reason);

        } else if (opcode == OP_PING) {
            if (payload.length > 125) {
                throw new ProtocolError("Ping payload too large");
            }
            Logger.i(TAG, "Sending pong!!");
            mClient.sendFrame(frame(payload, OP_PONG, -1));

        } else if (opcode == OP_PONG) {
            String message = encode(payload);
            // FIXME: Fire callback...
            Logger.i(TAG, "Got pong! " + message);
        }
    }

    private void reset() {
        mMode = 0;
        mBuffer.reset();
    }

    private String encode(byte[] buffer) {
        try {
            return new String(buffer, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] decode(String string) {
        try {
            return (string).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private int getInteger(byte[] bytes) throws ProtocolError {
        long i = byteArrayToLong(bytes, 0, bytes.length);
        if (i < 0 || i > Integer.MAX_VALUE) {
            throw new ProtocolError("Bad integer: " + i);
        }
        return (int) i;
    }

    private byte[] slice(byte[] array, int start) {
        return Arrays.copyOfRange(array, start, array.length);
    }

    public static class ProtocolError extends IOException {
        public ProtocolError(String detailMessage) {
            super(detailMessage);
        }
    }

    private static long byteArrayToLong(byte[] b, int offset, int length) {
        if (b.length < length)
            throw new IllegalArgumentException("length must be less than or equal to b.length");

        long value = 0;
        for (int i = 0; i < length; i++) {
            int shift = (length - 1 - i) * 8;
            value += (b[i + offset] & 0x000000FF) << shift;
        }
        return value;
    }

    public static class HappyDataInputStream extends DataInputStream {
        public HappyDataInputStream(InputStream in) {
            super(in);
            this.in = in;
        }

        public byte[] readBytes(int length) throws IOException {
            byte[] buffer = new byte[length];
            readFully(buffer);
            return buffer;
        }
    }
}