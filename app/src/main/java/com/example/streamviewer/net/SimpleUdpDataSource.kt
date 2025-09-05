package com.example.streamviewer.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
import com.example.streamviewer.util.Logger
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.net.SocketException

@UnstableApi
class SimpleUdpDataSource(private val appContext: Context) : BaseDataSource(/* isNetwork= */ true) {

    private var socket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var opened: Boolean = false
    private var uri: Uri? = null

    private val packetBufferSize: Int = 65536 // Large buffer to avoid truncation
    private val receiveBuffer = ByteArray(packetBufferSize)
    private var packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
    private var bufferReadOffset: Int = 0
    private var bufferValidBytes: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val requestedUri = uri ?: throw IOException("URI is null")

        val host = requestedUri.host ?: throw IOException("UDP URL must include host")
        val port = if (requestedUri.port != -1) requestedUri.port else 1234

        val address = InetAddress.getByName(host)
        val isMulticast = address.isMulticastAddress

        try {
            Logger.log("UDP: Opening socket for $host:$port (multicast: $isMulticast)")
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork: Network? = connectivityManager.activeNetwork
            if (isMulticast) {
                val mcast = MulticastSocket(null)
                mcast.reuseAddress = true
                mcast.bind(InetSocketAddress(port))
                try { activeNetwork?.bindSocket(mcast) } catch (_: Exception) {}
                Logger.log("UDP: Joining multicast group $address")
                mcast.joinGroup(address)
                multicastSocket = mcast
                socket = mcast
            } else {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(port))
                try { activeNetwork?.bindSocket(sock) } catch (_: Exception) {}
                socket = sock
            }

            try { socket?.receiveBufferSize = 1 shl 20 } catch (_: Exception) {}
            try { socket?.soTimeout = 5000 } catch (_: Exception) {} // 5 second timeout

            Logger.log("UDP: Socket opened successfully")
            opened = true
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        } catch (e: Exception) {
            Log.e("StreamViewer", "UDP: Failed to open socket: ${e.message}", e)
            close()
            throw IOException("Failed to open UDP socket: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return C.RESULT_END_OF_INPUT

        if (bufferReadOffset >= bufferValidBytes) {
            try {
                packet.length = receiveBuffer.size
                val sock = socket ?: return C.RESULT_END_OF_INPUT
                sock.receive(packet)
                bufferReadOffset = 0
                bufferValidBytes = packet.length

                // Log first few bytes to debug MPEG-TS format
                if (bufferValidBytes > 0) {
                    val firstBytes = receiveBuffer.take(minOf(16, bufferValidBytes))
                        .joinToString(" ") { "%02x".format(it) }
                    Logger.log("UDP: Received $bufferValidBytes bytes, first 16: $firstBytes")

                    // Check for MPEG-TS sync byte (0x47)
                    var syncFound = false
                    for (i in 0 until minOf(188, bufferValidBytes)) {
                        if (receiveBuffer[i] == 0x47.toByte()) {
                            syncFound = true
                            if (i > 0) {
                                Logger.log("UDP: MPEG-TS sync byte found at offset $i")
                                // Shift data to align with sync byte
                                System.arraycopy(receiveBuffer, i, receiveBuffer, 0, bufferValidBytes - i)
                                bufferValidBytes -= i
                            }
                            break
                        }
                    }
                    if (!syncFound) {
                        Logger.log("UDP: Warning - No MPEG-TS sync byte (0x47) found in packet")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.d("StreamViewer", "UDP: Socket timeout - no data received")
                return C.RESULT_END_OF_INPUT
            } catch (e: SocketException) {
                Log.e("StreamViewer", "UDP: Socket error during receive: ${e.message}", e)
                return C.RESULT_END_OF_INPUT
            } catch (e: Exception) {
                Log.e("StreamViewer", "UDP: Unexpected error during receive: ${e.message}", e)
                return C.RESULT_END_OF_INPUT
            }
        }

        val bytesToCopy = minOf(length, bufferValidBytes - bufferReadOffset)
        if (bytesToCopy <= 0) return C.RESULT_END_OF_INPUT

        System.arraycopy(receiveBuffer, bufferReadOffset, buffer, offset, bytesToCopy)
        bufferReadOffset += bytesToCopy
        bytesTransferred(bytesToCopy)
        return bytesToCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (!opened) return
        opened = false
        try {
            multicastSocket?.let { mcast ->
                try {
                    val host = uri?.host
                    if (host != null) {
                        mcast.leaveGroup(InetAddress.getByName(host))
                    }
                } catch (_: Exception) {}
                mcast.close()
            }
            socket?.close()
        } finally {
            multicastSocket = null
            socket = null
            uri = null
            transferEnded()
        }
    }

    class Factory(private val appContext: Context) : androidx.media3.datasource.DataSource.Factory {
        private var listener: TransferListener? = null
        override fun createDataSource(): androidx.media3.datasource.DataSource {
            Log.d("StreamViewer", "UDP Factory: Creating UDP data source")
            val ds = SimpleUdpDataSource(appContext)
            listener?.let { ds.addTransferListener(it) }
            return ds
        }
        fun setTransferListener(transferListener: TransferListener?): Factory {
            listener = transferListener
            return this
        }
    }
}


