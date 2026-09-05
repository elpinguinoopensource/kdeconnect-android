/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.backends.lan;

import static org.kde.kdeconnect.helpers.BoundedLineReaderKt.readLineBounded;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.kde.kdeconnect.backends.BaseLink;
import org.kde.kdeconnect.backends.BaseLinkProvider;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.DeviceInfo;
import org.kde.kdeconnect.helpers.security.SslHelper;
import org.kde.kdeconnect.helpers.ThreadHelper;
import org.kde.kdeconnect.NetworkPacket;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.NotYetConnectedException;
import java.security.cert.CertificateException;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

import kotlin.text.Charsets;
import org.kde.kdeconnect.helpers.LineTooLongException;

public class LanLink extends BaseLink {

    final static int MAX_PACKET_SIZE = 32 * 1024 * 1024;

    public enum ConnectionStarted {
        Locally, Remotely
    }

    private DeviceInfo deviceInfo;

    private volatile SSLSocket socket = null;

    @Override
    public void disconnect() {
        Log.i("LanLink/Disconnect","socket:"+ socket.hashCode());
        try {
            socket.close();
        } catch (IOException e) {
            Log.e("LanLink", "Error", e);
        }
    }

    //Returns the old socket
    @WorkerThread
    public SSLSocket reset(final SSLSocket newSocket, final DeviceInfo deviceInfo) throws IOException {

        this.deviceInfo = deviceInfo;

        SSLSocket oldSocket = socket;
        socket = newSocket;

        IOUtils.close(oldSocket); //This should cancel the readThread

        //Log.e("LanLink", "Start listening");
        //Create a thread to take care of incoming data for the new socket
        ThreadHelper.execute(() -> {
            try {
                BufferedInputStream stream = new BufferedInputStream(newSocket.getInputStream());
                while (true) {
                    String packet;
                    try {
                        packet = readLineBounded(stream, MAX_PACKET_SIZE);
                    } catch (LineTooLongException | SocketTimeoutException e) {
                        continue;
                    }
                    if (packet.isEmpty()) {
                        continue;
                    }
                    NetworkPacket np = NetworkPacket.unserialize(packet);
                    receivedNetworkPacket(np);
                }
            } catch (Exception e) {
                Log.i("LanLink", "Socket closed: " + newSocket.hashCode() + ". Reason: " + e.getMessage());
                try { Thread.sleep(300); } catch (InterruptedException ignored) {} // Wait a bit because we might receive a new socket meanwhile
                boolean thereIsaANewSocket = (newSocket != socket);
                if (!thereIsaANewSocket) {
                    Log.i("LanLink", "Socket closed and there's no new socket, disconnecting device");
                    getLinkProvider().onConnectionLost(LanLink.this);
                }
            }
        });

        return oldSocket;
    }

    @WorkerThread
    public LanLink(@NonNull Context context, @NonNull DeviceInfo deviceInfo, @NonNull BaseLinkProvider linkProvider, @NonNull SSLSocket socket) throws IOException {
        super(context, linkProvider);
        reset(socket, deviceInfo);
    }

    @Override
    public String getName() {
        return "LanLink";
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return deviceInfo;
    }

    @WorkerThread
    @Override
    public boolean sendPacket(@NonNull NetworkPacket np, @NonNull final Device.SendPacketStatusCallback callback, boolean sendPayloadFromSameThread) {
        if (socket == null) {
            Log.e("KDE/sendPacket", "Not yet connected");
            callback.onFailure(new NotYetConnectedException());
            return false;
        }

        try {

            //Prepare socket for the payload
            final ServerSocket server;
            if (np.hasPayload()) {
                server = LanLinkProvider.openServerSocketOnFreePort(LanLinkProvider.PAYLOAD_TRANSFER_MIN_PORT);
                JSONObject payloadTransferInfo = new JSONObject();
                payloadTransferInfo.put("port", server.getLocalPort());
                np.setPayloadTransferInfo(payloadTransferInfo);
            } else {
                server = null;
            }

            //Log.e("LanLink/sendPacket", np.getType());

            //Send body of the network packet
            try {
                OutputStream writer = socket.getOutputStream();
                writer.write(np.serialize().getBytes(Charsets.UTF_8));
                writer.flush();
            } catch (Exception e) {
                disconnect(); //main socket is broken, disconnect
                if (server != null) {
                    try { server.close(); } catch (Exception ignored) { }
                }
                throw e;
            }

            //Send payload
            if (server != null) {
                if (sendPayloadFromSameThread) {
                    sendPayload(np, callback, server);
                } else {
                    ThreadHelper.execute(() -> {
                        try {
                            sendPayload(np, callback, server);
                        } catch (IOException e) {
                            e.printStackTrace();
                            Log.e("LanLink/sendPacket", "Async sendPayload failed for packet of type " + np.getType() + ". The Plugin was NOT notified.");
                        }
                    });
                }
            }

            if (!np.isCanceled()) {
                callback.onSuccess();
            }
            return true;
        } catch (Exception e) {
            callback.onFailure(e);
            return false;
        } finally  {
            //Make sure we close the payload stream, if any
            if (np.hasPayload()) {
                np.getPayload().close();
            }
        }
    }

    private void sendPayload(NetworkPacket np, Device.SendPacketStatusCallback callback, ServerSocket server) throws IOException {
        Socket payloadSocket = null;
        OutputStream outputStream = null;
        InputStream inputStream;
        try {
            if (!np.isCanceled()) {
                //Wait a maximum of 10 seconds for the other end to establish a connection with our socket, close it afterwards
                server.setSoTimeout(10 * 1000);

                payloadSocket = server.accept();

                //Convert to SSL if needed
                payloadSocket = SslHelper.convertToSslSocket(context, payloadSocket, getDeviceId(), true, false);

                // Disable Nagle: the camera plugin streams live video over this
                // socket in 4 KB chunks; the trailing partial write of each
                // frame would otherwise wait up to ~40 ms for the previous
                // ACK before going out. Harmless for bulk transfers too.
                payloadSocket.setTcpNoDelay(true);

                outputStream = payloadSocket.getOutputStream();
                inputStream = np.getPayload().getInputStream();

                Log.i("KDE/LanLink", "Beginning to send payload for " + np.getType());
                // 64 KB pump buffer: fewer TLS records per video frame. A 1080p
                // keyframe costs 12-25 SSL_write calls at 4 KB but only 1-2 at
                // 64 KB, which cuts per-frame syscall/record-header overhead and
                // the latency spike at the start of every IDR. Harmless for bulk
                // file transfers (same total bytes, just fewer, larger writes).
                //
                // Coalescing note: each read() already returns one full buffer,
                // and StreamedPayloadInputStream enqueues exactly one access unit
                // per write(), so a single read() cannot return less than a whole
                // chunk unless the consumer is keeping up (in which case there is
                // nothing more to coalesce). Trying an extra read() to merge
                // adjacent chunks would block, because this InputStream only
                // returns -1 on end-of-stream, never "no data right now" — and
                // available() is unusable here (SSLSocket reports 0 even with data
                // pending in the SSL engine's internal buffer). True access-unit
                // coalescing would need a dedicated binary channel with a
                // non-blocking producer/consumer queue; out of scope.
                byte[] buffer = new byte[64 * 1024];
                int bytesRead;
                long size = np.getPayloadSize();
                long progress = 0;
                long timeSinceLastUpdate = -1;
                while (!np.isCanceled() && (bytesRead = inputStream.read(buffer)) != -1) {
                    //Log.e("ok",""+bytesRead);
                    progress += bytesRead;
                    outputStream.write(buffer, 0, bytesRead);
                    if (size > 0) {
                        if (timeSinceLastUpdate + 500 < System.currentTimeMillis()) { //Report progress every half a second
                            long percent = ((100 * progress) / size);
                            callback.onPayloadProgressChanged((int) percent);
                            timeSinceLastUpdate = System.currentTimeMillis();
                        }
                    }
                }
                outputStream.flush();
                Log.i("KDE/LanLink", "Finished sending payload (" + progress + " bytes written)");
            }
        } catch(SocketTimeoutException e) {
            Log.e("LanLink", "Socket for payload in packet " + np.getType() + " timed out. The other end didn't fetch the payload.");
        } catch(CertificateException | SSLHandshakeException e) {
            // The exception can be due to several causes. "Connection closed by peer" seems to be a common one.
            // If we could distinguish different cases we could react differently for some of them, but I haven't found how.
            Log.e("sendPacket","Payload SSLSocket failed");
            e.printStackTrace();
        } finally {
            try { server.close(); } catch (Exception ignored) { }
            try { IOUtils.close(payloadSocket); } catch (Exception ignored) { }
            np.getPayload().close();
            try { IOUtils.close(outputStream); } catch (Exception ignored) { }
        }
    }

    private void receivedNetworkPacket(NetworkPacket np) {

        if (np.hasPayloadTransferInfo()) {
            Socket payloadSocket = new Socket();
            try {
                int tcpPort = np.getPayloadTransferInfo().getInt("port");
                InetSocketAddress deviceAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
                payloadSocket.connect(new InetSocketAddress(deviceAddress.getAddress(), tcpPort));
                payloadSocket = SslHelper.convertToSslSocket(context, payloadSocket, getDeviceId(), true, true);
                np.setPayload(new NetworkPacket.Payload(payloadSocket, np.getPayloadSize()));
            } catch (Exception e) {
                try { payloadSocket.close(); } catch(Exception ignored) { }
                Log.e("KDE/LanLink", "Exception connecting to payload remote socket", e);
            }

        }

        packetReceived(np);
    }

}
