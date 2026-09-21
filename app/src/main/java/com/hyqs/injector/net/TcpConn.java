package com.hyqs.injector.net;

import com.hyqs.injector.LogBus;

import java.io.ByteArrayOutputStream;

/**
 * One intercepted TCP connection. We are the server side: the game connects, sends one HTTP
 * request, we stream the response back, then close. Deliberately minimal: no options, no SACK,
 * no window scaling, stop-and-go retransmit of the oldest unacked window.
 */
public class TcpConn {

    public interface Output {
        void write(byte[] ipPacket);
    }

    public interface RequestHandler {
        /** may block: called on a worker thread */
        byte[] respond(byte[] request);
    }

    private static final int MSS = 1300;
    private static final int MAX_INFLIGHT = 32 * 1024;
    private static final int WINDOW = 65535;
    private static final long RTO_MS = 600;

    public final int clientIp, clientPort, serverIp, serverPort;
    private final Output out;
    private final RequestHandler handler;

    private int sndNext;        // next seq we will send
    private int sndUnacked;     // oldest unacked seq
    private int rcvNext;        // next seq we expect from client
    private int peerWindow = WINDOW;

    private final ByteArrayOutputStream request = new ByteArrayOutputStream();
    private byte[] response;
    private int sentOffset, ackedOffset;
    private boolean finSent, closed, responding, peerFin;
    private long lastSend;

    public TcpConn(int clientIp, int clientPort, int serverIp, int serverPort, Output out, RequestHandler handler) {
        this.clientIp = clientIp;
        this.clientPort = clientPort;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.out = out;
        this.handler = handler;
    }

    public boolean isClosed() {
        return closed;
    }

    private void send(int flags, byte[] data, int seq) {
        out.write(Packets.tcp(serverIp, serverPort, clientIp, clientPort, seq, rcvNext, flags, WINDOW, data));
        lastSend = System.currentTimeMillis();
    }

    public synchronized void onPacket(byte[] ip, int ipHdr, int tcpLen) {
        int seq = Packets.i32(ip, ipHdr + 4);
        int ack = Packets.i32(ip, ipHdr + 8);
        int flags = Packets.u8(ip, ipHdr + 13);
        int dataOff = (Packets.u8(ip, ipHdr + 12) >>> 4) * 4;
        int payloadLen = tcpLen - dataOff;
        peerWindow = Packets.u16(ip, ipHdr + 14);
        if (peerWindow <= 0) peerWindow = WINDOW;

        if ((flags & Packets.RST) != 0) {
            closed = true;
            return;
        }

        if ((flags & Packets.SYN) != 0 && (flags & Packets.ACK) == 0) {
            sndNext = (int) (System.nanoTime() & 0x7FFFFFFF);
            sndUnacked = sndNext;
            rcvNext = seq + 1;
            send(Packets.SYN | Packets.ACK, null, sndNext);
            sndNext++;
            sndUnacked = sndNext;
            return;
        }

        if ((flags & Packets.ACK) != 0) {
            int acked = ack - sndUnacked;
            if (acked > 0) {
                sndUnacked = ack;
                if (response != null) {
                    ackedOffset = Math.min(response.length, ackedOffset + acked);
                }
            }
        }

        if (payloadLen > 0) {
            if (seq == rcvNext) {
                request.write(ip, ipHdr + dataOff, payloadLen);
                rcvNext += payloadLen;
                send(Packets.ACK, null, sndNext);
                maybeRespond();
            } else {
                // out of order: just re-ack what we have
                send(Packets.ACK, null, sndNext);
            }
        }

        if ((flags & Packets.FIN) != 0) {
            peerFin = true;
            rcvNext = seq + payloadLen + 1;
            send(Packets.ACK, null, sndNext);
            if (response == null && !responding) {
                maybeRespond();
            }
        }

        pump();
    }

    private void maybeRespond() {
        if (responding || response != null) return;
        final byte[] req = request.toByteArray();
        String s = new String(req);
        if (!s.contains("\r\n\r\n") && !peerFin) return;
        responding = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] r;
                try {
                    r = handler.respond(req);
                } catch (Throwable t) {
                    LogBus.log("生成响应失败: " + t);
                    r = ("HTTP/1.1 500 Internal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes();
                }
                synchronized (TcpConn.this) {
                    response = r;
                    sentOffset = 0;
                    ackedOffset = 0;
                    pump();
                }
            }
        }, "resp-" + clientPort).start();
    }

    /** send whatever the window allows; called on ack, on data and from the retransmit ticker */
    public synchronized void pump() {
        if (closed || response == null) return;

        if (sentOffset > ackedOffset && System.currentTimeMillis() - lastSend > RTO_MS) {
            sentOffset = ackedOffset;                 // timeout: go back
            sndNext = sndUnacked;
        }

        int window = Math.min(peerWindow, MAX_INFLIGHT);
        while (sentOffset < response.length && (sentOffset - ackedOffset) < window) {
            int len = Math.min(MSS, response.length - sentOffset);
            byte[] chunk = new byte[len];
            System.arraycopy(response, sentOffset, chunk, 0, len);
            send(Packets.ACK | Packets.PSH, chunk, sndNext);
            sndNext += len;
            sentOffset += len;
        }

        if (!finSent && ackedOffset >= response.length) {
            send(Packets.ACK | Packets.FIN, null, sndNext);
            sndNext++;
            finSent = true;
        }
        if (finSent && peerFin && ackedOffset >= response.length) {
            closed = true;
        }
    }

    public synchronized void reset() {
        send(Packets.RST, null, sndNext);
        closed = true;
    }
}
